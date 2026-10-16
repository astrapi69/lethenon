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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The peers a node was given on its command line, and when each is dialled again (#128).
 * <p>
 * Monero keeps its configured peers apart from the ones it learns and keeps reconnecting to them
 * from its idle loop; a peer given with {@code --peer} is the same here: one that could not be
 * reached, or whose connection ended, is dialled again while it is not connected. The pause before
 * the next attempt doubles with every attempt that fails, from {@link #FIRST} up to
 * {@link #LONGEST}, so that a peer that is down for an hour costs a dial every five minutes rather
 * than one every few seconds, and a connection that ends after a handshake is dialled again after
 * the first pause.
 */
final class Redials
{

	/** The pause after the first failed attempt */
	static final Duration FIRST = Duration.ofSeconds(5);

	/** The longest pause between two attempts */
	static final Duration LONGEST = Duration.ofMinutes(5);

	private final Duration first;

	private final Duration longest;

	/** In the order the peers were given */
	private final Map<PeerAddress, State> configured = new LinkedHashMap<>();

	private static final class State
	{
		private boolean busy;

		private int failures;

		private long dueAtMillis;

		/** Whether a refusal for this node's own limit was recorded since the peer was last connected */
		private boolean refusedForRoom;
	}

	Redials(final Duration first, final Duration longest)
	{
		if (first.isNegative() || first.isZero() || longest.compareTo(first) < 0)
		{
			throw new IllegalArgumentException("the pauses have to be positive and the longest not "
				+ "shorter than the first: " + first + ", " + longest);
		}
		this.first = first;
		this.longest = longest;
	}

	/**
	 * Five seconds first, five minutes at most
	 */
	static Redials standard()
	{
		return new Redials(FIRST, LONGEST);
	}

	/**
	 * The pause before the next attempt after the given number of failed ones in a row
	 */
	Duration pauseAfter(final int failures)
	{
		Duration pause = first;
		for (int doubled = 1; doubled < failures && pause.compareTo(longest) < 0; doubled++)
		{
			pause = pause.multipliedBy(2);
		}
		return pause.compareTo(longest) < 0 ? pause : longest;
	}

	/** How long the dialling loop sleeps between two looks at what is due */
	Duration tick()
	{
		return first.compareTo(Duration.ofSeconds(1)) < 0 ? first : Duration.ofSeconds(1);
	}

	/** A configured peer is being dialled now: it is neither due nor dialled a second time */
	synchronized void dialling(final PeerAddress address)
	{
		configured.computeIfAbsent(address, unused -> new State()).busy = true;
	}

	/**
	 * A dial or a handshake failed; the next attempt waits for the next, longer pause
	 *
	 * @return whether this is the first failure since the peer was last connected, the one worth
	 *         recording
	 */
	synchronized boolean failed(final PeerAddress address, final long nowMillis)
	{
		State state = configured.computeIfAbsent(address, unused -> new State());
		state.busy = false;
		state.failures++;
		state.dueAtMillis = nowMillis + pauseAfter(state.failures).toMillis();
		return state.failures == 1;
	}

	/**
	 * This node refused the dial for its own limit ({@link NoRoom}): no failure of the peer, so its
	 * pause stays as it was, and it is due again on the next round of the redial loop (#177)
	 *
	 * @return whether this is the first such refusal since the peer was last connected, the one
	 *         worth recording
	 */
	synchronized boolean full(final PeerAddress address, final long nowMillis)
	{
		State state = configured.computeIfAbsent(address, unused -> new State());
		state.busy = false;
		state.dueAtMillis = nowMillis;
		boolean first = !state.refusedForRoom;
		state.refusedForRoom = true;
		return first;
	}

	/** A configured peer completed its handshake: it is not dialled while it stays connected */
	synchronized void connected(final PeerAddress address)
	{
		State state = configured.get(address);
		if (state != null)
		{
			state.busy = true;
			state.failures = 0;
			state.refusedForRoom = false;
		}
	}

	/** The connection to a configured peer ended after its handshake: dialled after the first pause */
	synchronized void disconnected(final PeerAddress address, final long nowMillis)
	{
		State state = configured.get(address);
		if (state != null)
		{
			state.busy = false;
			state.dueAtMillis = nowMillis + first.toMillis();
		}
	}

	/**
	 * A configured peer turned out to be this node itself: it is never dialled again, as discovery
	 * never dials such an address again (#205). A seed node given no peer dials the seed nodes in the
	 * code, itself among them.
	 */
	synchronized void isItself(final PeerAddress address)
	{
		configured.remove(address);
	}

	/** Whether the address is one the node was given, rather than one it learnt */
	synchronized boolean isConfigured(final PeerAddress address)
	{
		return configured.containsKey(address);
	}

	/**
	 * The configured peers that are not connected or being dialled and whose pause is over, in the
	 * order they were given; each is marked as being dialled
	 */
	synchronized List<PeerAddress> due(final long nowMillis)
	{
		List<PeerAddress> due = new ArrayList<>();
		configured.forEach((address, state) -> {
			if (!state.busy && state.dueAtMillis <= nowMillis)
			{
				state.busy = true;
				due.add(address);
			}
		});
		return due;
	}
}
