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
		 *             when the address cannot be dialled: it is forgotten
		 */
		void dial(PeerAddress address) throws IOException;

		/**
		 * The addresses whose connection is open and whose handshake has not ended yet: not peers
		 * yet, and not to be dialled again (#117)
		 */
		Set<PeerAddress> handshaking();
	}

	private final PeerBook book = new PeerBook();

	private final Set<PeerAddress> dialing = ConcurrentHashMap.newKeySet();

	private final List<Peer> peers;

	private final Refusals refusals;

	private final Dialer dialer;

	private final ExecutorService threads;

	private volatile boolean dialling = true;

	Discovery(final List<Peer> peers, final Refusals refusals, final Dialer dialer,
		final ExecutorService threads)
	{
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
	 * A connection to this address opened and ended without a handshake: it is forgotten, as an
	 * address that could not be dialled is, so that filling a slot again tries another one (#121)
	 */
	void unreachable(final PeerAddress address)
	{
		book.forget(address);
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
	 * each dial runs on a thread of its own, so that a slow address holds up nothing else
	 */
	void fill()
	{
		while (dialling && dialer.outgoing() + dialing.size() < dialer.maximum())
		{
			Optional<PeerAddress> next = book.candidate(busy());
			if (next.isEmpty())
			{
				return;
			}
			PeerAddress address = next.get();
			dialing.add(address);
			threads.submit(() -> dial(address));
		}
	}

	private void dial(final PeerAddress address)
	{
		boolean failedToDial = false;
		try
		{
			dialer.dial(address);
		}
		catch (NoRoom busy)
		{
			// this node was full, the address is not at fault: it stays to be dialled when there is room
			refusals.add(address + ", learnt from a peer, was not dialled, it stays in the book: "
				+ busy.getMessage());
		}
		catch (IOException failed)
		{
			book.forget(address);
			refusals.add(address + ", learnt from a peer, was not connected: "
				+ failed.getMessage());
			failedToDial = true;
		}
		finally
		{
			dialing.remove(address);
		}
		if (failedToDial)
		{
			// the slot it held is free again; the address is forgotten, so this tries the next
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
	 * Keeps and passes on local addresses as well, for nodes that find each other on one machine
	 * (#194)
	 */
	void keepLocalAddresses()
	{
		book.keepLocalAddresses();
	}
}
