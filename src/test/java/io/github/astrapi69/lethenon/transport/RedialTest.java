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

import static io.github.astrapi69.lethenon.transport.Networks.await;
import static io.github.astrapi69.lethenon.transport.Networks.testGenesis;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Bytes;

/**
 * A configured peer that could not be reached is dialled again, after a pause that grows with every
 * failed attempt, as Monero keeps redialling its priority peers (#128). An attempt this node refused
 * for its own limit is no failure of the peer: its pause stays, and it is dialled on the next round
 * (#177).
 */
class RedialTest
{

	private static final Duration FIRST = Duration.ofMillis(100);

	private static final Duration LONGEST = Duration.ofMillis(800);

	private final List<BlockBody> genesis = testGenesis(Bytes.of(new byte[] { 1 }));

	/** A port nothing listens on: bound once to find a free one, then closed */
	private static int aFreePort() throws IOException
	{
		try (ServerSocket probe = new ServerSocket(0))
		{
			return probe.getLocalPort();
		}
	}

	@Test
	@DisplayName("a configured peer that is reachable only after the first attempt is connected once it is")
	void aPeerReachableOnlyAfterTheFirstAttempt_isConnectedOnceItIs()
		throws IOException, InterruptedException
	{
		int port = aFreePort();
		PeerAddress later = new PeerAddress("127.0.0.1", port);
		try (Node node = Node.on(genesis).redialingAfter(FIRST, LONGEST))
		{
			node.connectAll(List.of(later));
			assertTrue(node.peers().isEmpty());
			// the attempts after 100 and 300 ms fail as well, before the peer starts listening
			Thread.sleep(FIRST.multipliedBy(5).toMillis());
			assertTrue(node.peers().isEmpty());
			assertEquals(1, refusalsOf(node, later), node.refusals().toString());

			try (Node peer = Node.on(genesis))
			{
				assertEquals(port, peer.listen(port));

				await("the configured peer, dialled again", () -> node.peers().size() == 1);
				assertEquals(1, refusalsOf(node, later),
					"the refusal is recorded once, not on every attempt: " + node.refusals());
			}
		}
	}

	@Test
	@DisplayName("a configured peer that disconnects is dialled again")
	void aConfiguredPeerThatDisconnects_isDialledAgain() throws IOException
	{
		try (Node node = Node.on(genesis).redialingAfter(FIRST, LONGEST))
		{
			int port;
			try (Node first = Node.on(genesis))
			{
				port = first.listen(0);
				node.connectAll(List.of(new PeerAddress("127.0.0.1", port)));
				await("the first connection", () -> node.peers().size() == 1);
			}
			await("the disconnect", () -> node.peers().isEmpty());

			try (Node again = Node.on(genesis))
			{
				assertEquals(port, again.listen(port));
				await("the configured peer, dialled again", () -> node.peers().size() == 1);
			}
		}
	}

	@Test
	@DisplayName("a configured peer that turns out to be this node is dialled once, and never again (#205)")
	void aConfiguredPeerThatIsThisNode_isNotDialledAgain() throws IOException, InterruptedException
	{
		try (Node node = Node.on(genesis).redialingAfter(FIRST, LONGEST))
		{
			PeerAddress itself = new PeerAddress("127.0.0.1", node.listen(0));
			node.connectAll(List.of(itself));
			await("the first attempt, recognised as this node",
				() -> countItself(node) == 2);

			// long enough for five rounds of the pauses, which grow from 100 to 800 ms
			Thread.sleep(LONGEST.multipliedBy(3).toMillis());

			assertEquals(2, countItself(node),
				"one attempt, seen from both ends of its connection: " + node.refusals());
			assertTrue(node.peers().isEmpty());
		}
	}

	private static long countItself(final Node node)
	{
		return node.refusals().stream().filter(reason -> reason.endsWith(" is this node itself"))
			.count();
	}

	@Test
	@DisplayName("without a configured peer nothing is dialled")
	void withoutConfiguredPeers_nothingIsDialled() throws IOException
	{
		try (Node node = Node.on(genesis).redialingAfter(FIRST, LONGEST))
		{
			node.connectAll(List.of());
			assertTrue(node.refusals().isEmpty());
			assertTrue(node.peers().isEmpty());
		}
	}

	@ParameterizedTest(name = "after {0} failed attempt(s) the pause is {1} ms")
	@CsvSource({ "1, 100", "2, 200", "3, 400", "4, 800", "5, 800", "40, 800" })
	void thePause_doublesUpToTheLongest(final int failures, final long millis)
	{
		assertEquals(Duration.ofMillis(millis), new Redials(FIRST, LONGEST).pauseAfter(failures));
	}

	/** How one attempt at a configured peer ended */
	enum Outcome
	{
		/** the peer could not be reached */
		UNREACHABLE,
		/** this node refused the dial for its own limit: NoRoom */
		FULL
	}

	/** A sequence of attempts and the pause before each next one, in milliseconds */
	record Attempts(String name, List<Outcome> outcomes, List<Long> pauses)
	{
		@Override
		public String toString()
		{
			return name;
		}
	}

	static Stream<Attempts> attempts()
	{
		return Stream.of(
			new Attempts("unreachable three times: the pause grows, 100, 200, 400 ms",
				List.of(Outcome.UNREACHABLE, Outcome.UNREACHABLE, Outcome.UNREACHABLE),
				List.of(100L, 200L, 400L)),
			new Attempts("unreachable twice, then this node is full: dialled on the next round, and the pause does not grow",
				List.of(Outcome.UNREACHABLE, Outcome.UNREACHABLE, Outcome.FULL,
					Outcome.UNREACHABLE),
				List.of(100L, 200L, 0L, 400L)),
			new Attempts("full twice, then unreachable: the first real failure waits the first pause",
				List.of(Outcome.FULL, Outcome.FULL, Outcome.UNREACHABLE),
				List.of(0L, 0L, 100L)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("attempts")
	void thePause_growsOnlyWithRealFailures(final Attempts attempts)
	{
		Redials redials = new Redials(FIRST, LONGEST);
		PeerAddress peer = new PeerAddress("127.0.0.1", 18480);
		redials.dialling(peer);
		long now = 1_000_000L;
		List<Long> pauses = new ArrayList<>();
		for (Outcome outcome : attempts.outcomes())
		{
			if (outcome == Outcome.FULL)
			{
				redials.full(peer, now);
			}
			else
			{
				redials.failed(peer, now);
			}
			// the earliest moment the redial loop finds the peer due again; due marks it as dialled
			long pause = 0;
			while (!redials.due(now + pause).contains(peer))
			{
				pause++;
				assertTrue(pause <= LONGEST.toMillis(), "never due again after " + pauses);
			}
			pauses.add(pause);
			now += pause;
		}
		assertEquals(attempts.pauses(), pauses);
	}

	@Test
	@DisplayName("a full node's refusal is worth recording once, not on every round")
	void aFullNodesRefusal_isRecordedOnce()
	{
		Redials redials = new Redials(FIRST, LONGEST);
		PeerAddress peer = new PeerAddress("127.0.0.1", 18480);
		redials.dialling(peer);

		assertTrue(redials.full(peer, 0L), "the first refusal is recorded");
		redials.due(0L);
		assertFalse(redials.full(peer, 1L), "the next one on the next round is not");
	}

	@Test
	@DisplayName("the default pauses: five seconds first, five minutes at most")
	void theDefaultPauses()
	{
		assertEquals(Duration.ofSeconds(5), Redials.FIRST);
		assertEquals(Duration.ofMinutes(5), Redials.LONGEST);
		assertEquals(Duration.ofSeconds(80), Redials.standard().pauseAfter(5));
		assertEquals(Duration.ofMinutes(5), Redials.standard().pauseAfter(7));
	}

	private static long refusalsOf(final Node node, final PeerAddress address)
	{
		return node.refusals().stream().filter(refusal -> refusal.startsWith(address.toString()))
			.count();
	}
}
