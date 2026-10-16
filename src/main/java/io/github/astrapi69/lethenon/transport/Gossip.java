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
import java.util.List;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.CanonicalEncoding;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.ChainWork;
import io.github.astrapi69.lethenon.SignedTransaction;
import io.github.astrapi69.lethenon.TransactionPool.Admission;
import io.github.astrapi69.lethenon.TransactionPool.Outcome;

/**
 * What a node says and does after the handshake: it handles every frame a peer sends, relays what
 * it accepts, synchronises with peers that are ahead or on another fork, and writes its chain and
 * pool to the file it serves
 * <p>
 * It holds no socket of its own. The node reads frames and hands them over; frames go out through
 * each peer's queue.
 */
final class Gossip
{

	private final LocalChain local;

	private final ChainFile file;

	private final List<Peer> peers;

	private final Refusals refusals;

	private final Object writing = new Object();

	private final Discovery discovery;

	private volatile AnonymityZone zone;

	/**
	 * @param local
	 *            the node's chain and pool
	 * @param file
	 *            the chain file the node serves, or null for none
	 * @param peers
	 *            the node's peers after the handshake, read for relaying
	 * @param refusals
	 *            where reasons are recorded
	 * @param discovery
	 *            peer exchange
	 */
	Gossip(final LocalChain local, final ChainFile file, final List<Peer> peers,
		final Refusals refusals, final Discovery discovery)
	{
		this.discovery = discovery;
		this.local = local;
		this.file = file;
		this.peers = peers;
		this.refusals = refusals;
	}

	/**
	 * Sends the node's own transfers through the given zone from now on (ADR 0004, step 2)
	 */
	void anonymityZone(final AnonymityZone anonymous)
	{
		this.zone = anonymous;
	}

	/**
	 * Asks a peer that announced more cumulative work for its chain at once, instead of waiting
	 * for its next block (ADR 0003, handshake); what it then sends is verified like any fetch
	 */
	void askIfAhead(final Peer peer)
	{
		if (peer.hello().work().compareTo(ChainWork.of(local.blocks())) > 0)
		{
			synchroniseWith(peer);
		}
	}

	/**
	 * Offers a block this node mined or was handed
	 *
	 * @return whether the chain now ends with it; a refusal is recorded
	 */
	boolean submitBlock(final BlockBody block)
	{
		try
		{
			return acceptBlock(block, null);
		}
		catch (ProtocolViolation refused)
		{
			refusals.add("a submitted block was refused: " + refused.getMessage());
			return false;
		}
	}

	/**
	 * What a node does with a frame after the handshake
	 */
	void handle(final Peer peer, final Frame frame) throws ProtocolViolation
	{
		switch (frame.type())
		{
			case HELLO -> throw new ProtocolViolation("HELLO after the handshake");
			case BLOCK -> onBlock(peer, frame);
			case TRANSFER -> admit(decodeTransfer(frame.payload()), peer);
			case GET_CHAIN -> peer.send(new Frame(MessageType.CHAIN,
				local.answer(Locator.decode(frame.payload())).encode()));
			case CHAIN -> onChain(peer, ChainEntry.decode(frame.payload()));
			case GET_BLOCKS -> peer.send(new Frame(MessageType.BLOCKS,
				CanonicalEncoding.encodeChain(local.blocksFor(BlockRequest.decode(frame.payload())))));
			case BLOCKS -> onBlocks(peer, decodeBlocks(frame.payload()));
			case GET_PEERS -> discovery.answer(peer);
			case PEERS -> discovery.learn(peer, PeerList.decode(frame.payload()));
		}
	}

	/**
	 * What a node does with a frame from a peer of its anonymity zone: a transfer is offered to the
	 * pool, onion addresses are exchanged and only onion addresses kept, a block the peer relays is
	 * passed over, since the zone carries no chain, and anything else disconnects
	 */
	void handleAnonymous(final Peer peer, final Frame frame) throws ProtocolViolation
	{
		switch (frame.type())
		{
			case TRANSFER -> admit(decodeTransfer(frame.payload()), peer);
			case GET_PEERS -> zone.discovery().answer(peer);
			case PEERS -> zone.discovery().learn(peer, new PeerList(PeerList
				.decode(frame.payload()).addresses().stream().filter(PeerAddress::isOnion).toList()));
			case BLOCK -> {
				// a node that does not know it is in an anonymity zone relays its blocks to every
				// peer; the zone does not take part in the chain, so the block is passed over
			}
			default -> throw new ProtocolViolation(frame.type()
				+ " in the anonymity zone, which carries transfers and onion addresses only "
				+ "(ADR 0004)");
		}
	}

	private void onBlock(final Peer peer, final Frame frame) throws ProtocolViolation
	{
		List<BlockBody> blocks = decodeBlocks(frame.payload());
		if (blocks.size() != 1)
		{
			throw new ProtocolViolation("a BLOCK message carries " + blocks.size() + " blocks");
		}
		if (!acceptBlock(blocks.getFirst(), peer) && !local.knows(blocks.getFirst()))
		{
			synchroniseWith(peer);
		}
	}

	/**
	 * Adopts a block that extends the chain and relays it
	 *
	 * @return whether it was adopted; false for a known block and for one whose parent is not the
	 *         tip
	 * @throws ProtocolViolation
	 *             when it extends the chain and the chain then does not verify
	 */
	private boolean acceptBlock(final BlockBody block, final Peer source) throws ProtocolViolation
	{
		if (!local.extendWith(block))
		{
			return false;
		}
		writeChain();
		relay(new Frame(MessageType.BLOCK, CanonicalEncoding.encodeChain(List.of(block))), source);
		return true;
	}

	/**
	 * Offers a transfer to the pool, writes and relays it when it is admitted, and records a
	 * refusal
	 */
	Admission admit(final SignedTransaction transfer, final Peer source)
	{
		Admission admission = local.pool().offer(transfer);
		if (admission.outcome() == Outcome.ADMITTED)
		{
			writePool();
			AnonymityZone anonymous = zone;
			if (anonymous != null && originatesHere(source, anonymous))
			{
				if (!anonymous.send(transfer))
				{
					refusals.add("an own transfer waits for an anonymity peer and is not sent in "
						+ "the clear (ADR 0004)");
				}
			}
			else
			{
				relay(new Frame(MessageType.TRANSFER, CanonicalEncoding.encode(transfer)), source);
			}
		}
		else if (admission.outcome() == Outcome.REFUSED)
		{
			refusals.add((source == null ? "a submitted" : source.address() + " sent a")
				+ " transfer that was refused: " + admission.reason());
		}
		return admission;
	}

	/**
	 * Whether a transfer is the node's own: submitted on the node, or handed over by a command, a
	 * caller without a node identity, as {@link Handover} is; a node always draws one, whether it
	 * listens or not. A transfer from the anonymity zone is not, it came from there.
	 */
	private static boolean originatesHere(final Peer source, final AnonymityZone anonymous)
	{
		return source == null || !anonymous.isMember(source) && source.hello().nodeId() == 0L;
	}

	private void onChain(final Peer peer, final ChainEntry entry) throws ProtocolViolation
	{
		if (!peer.synchronising())
		{
			throw new ProtocolViolation("a CHAIN answer nobody asked for");
		}
		switch (local.plan(entry))
		{
			case LocalChain.Plan.Nothing nothing -> endSynchronising(peer);
			case LocalChain.Plan.Fetching fetching -> {
				peer.fetch(fetching.fetch());
				peer.send(new Frame(MessageType.GET_BLOCKS,
					fetching.fetch().nextRequest().encode()));
			}
		}
	}

	private void onBlocks(final Peer peer, final List<BlockBody> blocks) throws ProtocolViolation
	{
		Fetch fetch = peer.fetch();
		if (fetch == null)
		{
			throw new ProtocolViolation("BLOCKS that were not asked for");
		}
		if (blocks.isEmpty())
		{
			// the peer no longer has what it announced
			endSynchronising(peer);
			return;
		}
		BlockRequest asked = fetch.nextRequest();
		if (blocks.size() > asked.count())
		{
			throw new ProtocolViolation("BLOCKS with " + blocks.size() + " blocks for a request of "
				+ asked.count());
		}
		for (int index = 0; index < blocks.size(); index++)
		{
			if (!Blocks.hashOf(blocks.get(index)).equals(fetch.expected().get(index)))
			{
				throw new ProtocolViolation("BLOCKS carries at height " + (asked.first() + index)
					+ " a block whose hash the peer did not announce");
			}
		}
		switch (local.consider(fetch.with(blocks)))
		{
			// the chain moved while the blocks were on their way, and the fork point went with it
			case LocalChain.Outcome.Stale stale -> endSynchronising(peer);
			case LocalChain.Outcome.Waiting waiting -> continueFetching(peer, waiting.fetch());
			case LocalChain.Outcome.Switched switched -> {
				writeChain();
				relay(new Frame(MessageType.BLOCK,
					CanonicalEncoding.encodeChain(List.of(local.blocks().getLast()))), peer);
				continueFetching(peer, switched.rest());
			}
		}
	}

	private void continueFetching(final Peer peer, final Fetch rest)
	{
		if (!rest.expected().isEmpty())
		{
			peer.fetch(rest);
			peer.send(new Frame(MessageType.GET_BLOCKS, rest.nextRequest().encode()));
		}
		else if (!rest.fetched().isEmpty())
		{
			// the candidate never carried more work: on equal work the first chain seen stays,
			// and a fork that has not overtaken within one CHAIN answer is not followed, so that a
			// peer cannot make this node hold more than that many blocks (ADR 0003)
			refusals.add(peer.address() + "'s fork from height " + (rest.base() + 1) + " was not "
				+ "followed: " + rest.fetched().size() + " blocks carry no more work"
				+ (rest.more() ? " within one CHAIN answer" : ""));
			endSynchronising(peer);
		}
		else if (rest.more())
		{
			peer.fetch(null);
			peer.send(new Frame(MessageType.GET_CHAIN, local.locator().encode()));
		}
		else
		{
			endSynchronising(peer);
		}
	}

	/**
	 * Asks a peer for its chain, unless a synchronisation with it is in flight; then one more
	 * follows when that one ends (#85)
	 */
	private void synchroniseWith(final Peer peer)
	{
		if (peer.startSynchronising())
		{
			peer.send(new Frame(MessageType.GET_CHAIN, local.locator().encode()));
		}
	}

	private void endSynchronising(final Peer peer)
	{
		if (peer.endSynchronising())
		{
			peer.send(new Frame(MessageType.GET_CHAIN, local.locator().encode()));
		}
	}

	/**
	 * Writes the chain and the pool to the served file; the snapshot is taken under the same lock
	 * as the write, so the last write always carries the latest state
	 */
	private void writeChain()
	{
		if (file == null)
		{
			return;
		}
		synchronized (writing)
		{
			try
			{
				file.write(local.blocks(), local.pool().waiting());
			}
			catch (IOException unwritable)
			{
				refusals.add("the chain could not be written: " + unwritable.getMessage());
			}
		}
	}

	void writePool()
	{
		if (file == null)
		{
			return;
		}
		synchronized (writing)
		{
			try
			{
				file.writePending(local.pool().waiting());
			}
			catch (IOException unwritable)
			{
				refusals.add("the pool could not be written: " + unwritable.getMessage());
			}
		}
	}

	private void relay(final Frame frame, final Peer source)
	{
		for (Peer peer : peers)
		{
			if (peer != source)
			{
				peer.send(frame);
			}
		}
	}

	private static List<BlockBody> decodeBlocks(final byte[] payload) throws ProtocolViolation
	{
		try
		{
			return CanonicalEncoding.readChain(payload);
		}
		catch (IllegalArgumentException undecodable)
		{
			throw new ProtocolViolation("bytes that do not decode as blocks: "
				+ undecodable.getMessage());
		}
	}

	private static SignedTransaction decodeTransfer(final byte[] payload) throws ProtocolViolation
	{
		try
		{
			return CanonicalEncoding.readSignedTransaction(payload);
		}
		catch (IllegalArgumentException undecodable)
		{
			throw new ProtocolViolation("bytes that do not decode as a transfer: "
				+ undecodable.getMessage());
		}
	}
}
