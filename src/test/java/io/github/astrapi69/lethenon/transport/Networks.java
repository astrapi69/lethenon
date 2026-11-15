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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.DifficultyRule;
import io.github.astrapi69.lethenon.Mining;
import io.github.astrapi69.lethenon.SignedTransaction;

/**
 * Chains and waiting for the test networks: nodes run on their own threads, so a test waits for a
 * condition with a deadline instead of sleeping
 */
final class Networks
{

	static final long GENESIS_TIME = 1_759_500_000_000L;

	private Networks()
	{
	}

	/**
	 * A test chain of its genesis block, which pays the burn account (#148), and block 1, which
	 * pays the given holder, so that the holder can spend. The genesis pun names the holder: a
	 * genesis block pays nobody it could tell apart by, so two holders still mean two chains
	 */
	static List<BlockBody> testGenesis(final Bytes holder)
	{
		return genesis(Chain.TEST_IDENTIFIER, holder);
	}

	static List<BlockBody> genesis(final String chainIdentifier, final Bytes holder)
	{
		List<BlockBody> genesis = List.of(Blocks.mine(Mining.nextBlock(chainIdentifier, List.of(),
			holder, List.of(), "in the beginning of " + holder, GENESIS_TIME), 1_000_000L).orElseThrow());
		return extended(genesis, holder, List.of());
	}

	/**
	 * The chain extended by one mined block carrying the given transfers, at the target block
	 * time after the one before, so that a long chain keeps the minimum difficulty
	 */
	static List<BlockBody> extended(final List<BlockBody> chain, final Bytes miner,
		final List<SignedTransaction> transfers)
	{
		List<BlockBody> extended = new ArrayList<>(chain);
		extended.add(Blocks.mine(Mining.nextBlock(chain, miner, transfers, "block " + chain.size(),
			GENESIS_TIME + DifficultyRule.TARGET_BLOCK_MILLIS * chain.size()), 1_000_000L)
			.orElseThrow());
		return List.copyOf(extended);
	}

	/**
	 * Starts listening nodes that do not discover peers and connects them to a hub one after the
	 * other, each once the hub holds the one before it. That fixes the order in which the hub was
	 * connected to them, which is the order it passes their addresses on in (ADR 0003), and so the
	 * order a newcomer dials them in (#278)
	 *
	 * @param genesis
	 *            the chain the nodes start on
	 * @param hub
	 *            the hub, listening
	 * @param hubPort
	 *            the hub's port
	 * @param count
	 *            how many nodes
	 * @return the nodes, in the order they were connected; the caller closes them
	 * @throws IOException
	 *             when a node cannot listen or connect
	 */
	static List<Node> connectedInTurn(final List<BlockBody> genesis, final Node hub,
		final int hubPort, final int count) throws IOException
	{
		List<Node> others = new ArrayList<>();
		for (int index = 0; index < count; index++)
		{
			Node other = Node.on(genesis).discoverPeers(false);
			others.add(other);
			int listening = other.listen(0);
			other.connect("127.0.0.1", hubPort);
			int expected = index + 1;
			await(() -> "node " + expected + " at the hub, listening on " + listening,
				() -> hub.peers().size() == expected);
		}
		return others;
	}

	/**
	 * The address book of a node's clearnet discovery, or of its anonymity zone's, as the object
	 * its methods lock: a test that holds it stops every thread of the node at its next look into
	 * the book. Read by reflection, because nothing else hands it out and nothing should
	 *
	 * @param ofTheZone
	 *            the zone's book rather than the clearnet's
	 * @return the lock
	 */
	static Object addressBookOf(final Node node, final boolean ofTheZone)
	{
		try
		{
			Object discovery;
			if (ofTheZone)
			{
				java.lang.reflect.Field zone = Node.class.getDeclaredField("zone");
				zone.setAccessible(true);
				discovery = ((AnonymityZone)zone.get(node)).discovery();
			}
			else
			{
				java.lang.reflect.Field clearnet = Node.class.getDeclaredField("discovery");
				clearnet.setAccessible(true);
				discovery = clearnet.get(node);
			}
			java.lang.reflect.Field book = Discovery.class.getDeclaredField("book");
			book.setAccessible(true);
			return book.get(discovery);
		}
		catch (ReflectiveOperationException missing)
		{
			throw new AssertionError("no address book to hold", missing);
		}
	}

	/**
	 * Closes the far end of a connection the node dialled, before HELLO, while the node's address
	 * book is held, and watches the address for a second. Holding the book stops the connection's
	 * thread where it records the failure in the book, so an address that leaves its handshake
	 * meanwhile left it before its failure was recorded, and was neither busy nor waiting then
	 * (#296). A second is far more than the end of a local connection takes; on a machine slow
	 * enough to need more, a wrong order passes unseen, a right one never fails
	 *
	 * @param book
	 *            from {@link #addressBookOf(Node, boolean)}
	 * @return what was waiting the moment the address left its handshake, or empty when it did not
	 *         leave it while the book was held
	 */
	static Optional<List<PeerAddress>> leftItsHandshakeWhileTheBookWasHeld(final Object book,
		final java.net.Socket farEnd, final PeerAddress address,
		final Supplier<Set<PeerAddress>> handshaking, final Supplier<List<PeerAddress>> waiting)
		throws IOException
	{
		synchronized (book)
		{
			farEnd.close();
			long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
			while (System.nanoTime() < deadline)
			{
				if (!handshaking.get().contains(address))
				{
					return Optional.of(waiting.get());
				}
				Thread.onSpinWait();
			}
			return Optional.empty();
		}
	}

	/**
	 * A peer at the hub that says HELLO with the given listening port and nothing more: the hub
	 * confirms the address 127.0.0.1 and that port, which nothing has to listen on, and passes it
	 * on; this returns once the hub has asked it for addresses, which it does after confirming it. It saves a node listening there that closes, whose port the system may hand out again
	 * before a test binds it
	 *
	 * @return the connection, open; the caller closes it
	 */
	static java.net.Socket announcedAtTheHub(final List<BlockBody> genesis, final int hubPort,
		final int listening) throws IOException
	{
		java.net.Socket announcing = new java.net.Socket("127.0.0.1", hubPort);
		java.io.DataOutputStream out = new java.io.DataOutputStream(announcing.getOutputStream());
		java.io.DataInputStream in = new java.io.DataInputStream(announcing.getInputStream());
		Frames.write(out, new Frame(MessageType.HELLO,
			Hello.of(genesis, listening, new java.security.SecureRandom().nextLong(1, Long.MAX_VALUE))
				.encode()));
		Frame answer = Frames.read(in, Frames.MAXIMUM_FRAME);
		if (answer.type() != MessageType.HELLO)
		{
			announcing.close();
			throw new AssertionError("the hub answered " + answer.type() + " instead of HELLO");
		}
		// the hub asks a listening peer for addresses once it has confirmed it (Discovery.connected)
		for (int frames = 0; answer.type() != MessageType.GET_PEERS; frames++)
		{
			if (frames == 8)
			{
				announcing.close();
				throw new AssertionError("the hub did not ask for addresses in 8 frames");
			}
			answer = Frames.read(in, Frames.MAXIMUM_FRAME);
		}
		return announcing;
	}

	/**
	 * Waits until the condition holds, at most ten seconds
	 *
	 * @throws AssertionError
	 *             naming what was waited for, when the deadline passes
	 */
	static void await(final String what, final BooleanSupplier condition)
	{
		await(what, condition, Duration.ofSeconds(10));
	}

	/**
	 * Waits until the condition holds, at most the given time.
	 * <p>
	 * When the time runs out, the message says how far the wall clock moved meanwhile and where
	 * every thread stands (#212). The deadline runs on the monotonic clock, which stands still while
	 * the machine sleeps, and a test report's duration on the wall clock, which does not: a wall
	 * clock far ahead of the limit is a machine that slept or a JVM that stood still, and the
	 * threads tell a hang from both.
	 *
	 * @throws AssertionError
	 *             naming what was waited for, when the deadline passes
	 */
	static void await(final String what, final BooleanSupplier condition, final Duration limit)
	{
		await(() -> what, condition, limit);
	}

	/**
	 * Waits until the condition holds, at most ten seconds, and says what was waited for only when
	 * the time runs out: the description reads the state at that moment, so a failure shows what
	 * stood in the way (#278)
	 *
	 * @throws AssertionError
	 *             with the description as it reads when the deadline passes
	 */
	static void await(final Supplier<String> what, final BooleanSupplier condition)
	{
		await(what, condition, Duration.ofSeconds(10));
	}

	private static void await(final Supplier<String> what, final BooleanSupplier condition,
		final Duration limit)
	{
		long wallStart = System.currentTimeMillis();
		long deadline = System.nanoTime() + limit.toNanos();
		while (!condition.getAsBoolean())
		{
			if (System.nanoTime() > deadline)
			{
				throw new AssertionError("waited " + readable(limit) + " for " + what.get()
					+ "; the wall clock moved " + (System.currentTimeMillis() - wallStart)
					+ " ms meanwhile\n" + threads());
			}
			Thread.onSpinWait();
			try
			{
				Thread.sleep(10);
			}
			catch (InterruptedException interrupted)
			{
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted while waiting for " + what.get());
			}
		}
	}

	private static String readable(final Duration limit)
	{
		return limit.toMillis() % 1000 == 0 ? limit.toSeconds() + " s" : limit.toMillis() + " ms";
	}

	/**
	 * Every thread of this JVM with its state and the top of its stack
	 */
	private static String threads()
	{
		StringBuilder report = new StringBuilder("threads:");
		for (java.util.Map.Entry<Thread, StackTraceElement[]> thread : Thread.getAllStackTraces()
			.entrySet())
		{
			report.append("\n\"").append(thread.getKey().getName()).append("\" ")
				.append(thread.getKey().getState());
			StackTraceElement[] stack = thread.getValue();
			for (int frame = 0; frame < Math.min(stack.length, 12); frame++)
			{
				report.append("\n    at ").append(stack[frame]);
			}
		}
		return report.toString();
	}
}
