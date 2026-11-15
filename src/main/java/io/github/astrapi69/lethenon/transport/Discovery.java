/*
 * The MIT License
 *
 * Copyright (C) 2015 Asterios Raptis
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.astrapi69.lethenon.transport;

import java.io.IOException;
import java.time.InstantSource;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Peer exchange (ADR 0003): after a handshake a node asks its peer for addresses, passes on the
 * addresses of nodes it has itself been connected to, and connects to addresses it learns about
 * while it has room for outgoing connections
 * <p>
 * Only addresses that a node announced as listening are ever passed on. A caller that does not
 * listen - a command handing over a transfer, a node taking its genesis block - announces port 0
 * and is never named to anybody.
 */
final class Discovery
{

	/**
	 * What discovery needs from the node: whether there is room for another outgoing connection,
	 * and a way to open one
	 */
	interface Dialer
	{
		int outgoing();

		/**
		 * The most connections this dialer opens: {@link Node#MAXIMUM_OUTGOING} for clearnet
		 * peers, the zone's maximum in the anonymity zone
		 */
		int maximum();

		/**
		 * Opens a connection to the address
		 *
		 * @throws NoRoom
		 *             when this node's own limit is reached: the address stays in the book (#126)
		 * @throws IOException
		 *             when the address cannot be dialled: it waits and counts a failure (#296)
		 */
		void dial(PeerAddress address) throws IOException;

		/**
		 * The addresses whose connection is open and whose handshake has not ended yet: not peers
		 * yet, and not to be dialled again (#117)
		 */
		Set<PeerAddress> handshaking();
	}

	private final PeerBook book;

	private final Set<PeerAddress> dialing = ConcurrentHashMap.newKeySet();

	private final List<Peer> peers;

	private final Refusals refusals;

	private final Dialer dialer;

	private final ExecutorService threads;

	private volatile boolean dialling = true;

	Discovery(final List<Peer> peers, final Refusals refusals, final Dialer dialer,
		final ExecutorService threads)
	{
		this(peers, refusals, dialer, threads, InstantSource.system());
	}

	/**
	 * Discovery whose address book reads the given clock, which the tests move by hand (#296)
	 */
	Discovery(final List<Peer> peers, final Refusals refusals, final Dialer dialer,
		final ExecutorService threads, final InstantSource clock)
	{
		this.book = new PeerBook(clock);
		this.peers = peers;
		this.refusals = refusals;
		this.dialer = dialer;
		this.threads = threads;
	}

	/**
	 * A handshake succeeded: a listening peer's address is confirmed, and the peer is asked for
	 * the addresses it knows. A peer that does not listen is not a node but a command, and is
	 * not asked.
	 */
	void connected(final Peer peer)
	{
		peer.listening().ifPresent(book::confirmed);
		if (peer.hello().listenPort() > 0)
		{
			peer.expectPeers();
			peer.send(new Frame(MessageType.GET_PEERS, new byte[0]));
		}
	}

	/**
	 * A connection this node opened led back to itself: that address is never dialled again
	 */
	void isItself(final PeerAddress address)
	{
		book.isItself(address);
	}

	/**
	 * A connection to this address opened and ended without a handshake: it waits and counts a
	 * failure, as an address that could not be dialled does, so that filling a slot again tries
	 * another one (#121, #296)
	 */
	void unreachable(final PeerAddress address)
	{
		book.failed(address);
	}

	void answer(final Peer peer)
	{
		peer.send(new Frame(MessageType.PEERS, new PeerList(book.shareable()).encode()));
	}

	/**
	 * Addresses a peer passed on in answer to GET_PEERS: kept as heard of, and dialled while
	 * there is room
	 *
	 * @throws ProtocolViolation
	 *             for PEERS nobody asked for
	 */
	void learn(final Peer peer, final PeerList list) throws ProtocolViolation
	{
		if (!peer.takeExpectedPeers())
		{
			throw new ProtocolViolation("PEERS nobody asked for");
		}
		book.heard(list.addresses());
		fill();
	}

	/**
	 * Dials addresses heard of or confirmed while this node has room for outgoing connections;
	 * each dial runs on a thread of its own, so that a slow address holds up nothing else. Taking
	 * an address starts the attempt on it, which sets its last try (#296): a retry waits again from
	 * then, so a fill that runs before the outcome is recorded does not take it a second time. Two
	 * fills at once never take the same address, a first attempt included ({@code take()})
	 */
	void fill()
	{
		while (dialling)
		{
			Optional<PeerAddress> next = take();
			if (next.isEmpty())
			{
				return;
			}
			PeerAddress address = next.get();
			Optional<PeerBook.Attempt> attempt = book.attempted(address);
			threads.submit(() -> dial(address, attempt));
		}
	}

	/**
	 * Takes the next address for a dial, if this node has room for one, and puts it on
	 * {@code dialing} in one step: busy() is a copy, and a fill that ran at the same time and
	 * copied {@code dialing} before the other put the address on it took the address as well
	 * (#296). The room is counted inside the step too, {@code dialing} before the outgoing
	 * connections: a dial counts as outgoing before it leaves {@code dialing}, so read in that order
	 * the two never miss a dial under way, and two fills at once never take more addresses than
	 * there are slots; read the other way, or outside the step, the last free slot went to both,
	 * and one dial was refused for this node's own limit (#325). The step holds this discovery's
	 * lock and, inside it, the address book's; nothing that holds either calls back into
	 * discovery, and no caller of fill holds a lock of its own
	 *
	 * @return the address, now on {@code dialing}, or empty when there is none or no room
	 */
	private synchronized Optional<PeerAddress> take()
	{
		int underWay = dialing.size();
		if (underWay + dialer.outgoing() >= dialer.maximum())
		{
			return Optional.empty();
		}
		Optional<PeerAddress> next = book.candidate(busy());
		next.ifPresent(dialing::add);
		return next;
	}

	/**
	 * Dials one address, and fills again once it is no longer counted as dialling, unless the node
	 * had no room: between the slot a dial takes and its leaving {@code dialing} it is counted twice,
	 * so a fill that ran meanwhile stopped one slot early, and only this fill sees the true count
	 * (#278). After a failed dial it also takes the next address in place of the one that now
	 * waits (#296). A dial this node had no room for was not an attempt on the address, and gives
	 * its last try back (#126, #296)
	 *
	 * @param attempt
	 *            the attempt the fill started, empty for an address the book no longer held
	 */
	private void dial(final PeerAddress address, final Optional<PeerBook.Attempt> attempt)
	{
		boolean noRoom = false;
		try
		{
			dialer.dial(address);
		}
		catch (NoRoom busy)
		{
			// this node was full, the address is not at fault: it stays to be dialled when there is room
			attempt.ifPresent(book::withdrawn);
			refusals.add(address + ", learnt from a peer, was not dialled, it stays in the book: "
				+ busy.getMessage());
			noRoom = true;
		}
		catch (IOException failed)
		{
			PeerBook.Failure failure = book.failed(address);
			refusals.add(address + ", learnt from a peer, was not connected: "
				+ failed.getMessage() + "; " + failure.describe());
		}
		finally
		{
			dialing.remove(address);
		}
		if (!noRoom)
		{
			fill();
		}
	}

	private Set<PeerAddress> busy()
	{
		Set<PeerAddress> busy = new HashSet<>(dialing);
		busy.addAll(dialer.handshaking());
		for (Peer peer : peers)
		{
			peer.listening().ifPresent(busy::add);
		}
		return busy;
	}

	/**
	 * Whether this node connects to addresses it learns; it still passes on what it knows
	 */
	void dialling(final boolean enabled)
	{
		dialling = enabled;
	}

	List<PeerAddress> heardOf()
	{
		return book.heardOf();
	}

	/**
	 * The addresses that wait after a failed attempt before they are tried again (#296)
	 */
	List<PeerAddress> waiting()
	{
		return book.waiting();
	}

	/**
	 * Keeps and passes on local addresses as well, for nodes that find each other on one machine
	 * (#194)
	 */
	void keepLocalAddresses()
	{
		book.keepLocalAddresses();
	}
}
