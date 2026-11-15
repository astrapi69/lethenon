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
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a node knows about other nodes' addresses, after Monero's white and gray peer lists: an
 * address it has itself been connected to, and one it has only heard of from a peer
 * <p>
 * Only the first kind is passed on. An address heard of becomes the first kind when a connection
 * to it succeeds. A failed attempt does not forget it (ADR 0003, #296): it waits before it is tried
 * again, longer after each counted failure, and is dropped only after {@link #RETRIES} counted
 * failures without ever a success, or after {@link #MAXIMUM_FAILURES} with no success for
 * {@link #FAILURE_WINDOW}. The first failure counts; a further one counts only once a connection to
 * any address has succeeded since the last counted one, so an outage on this node's own side counts
 * once against each address instead of draining the book. A waiting address is neither offered for
 * a dial nor passed on. A local address ({@link PeerAddress#isLocal()}) is neither kind: no other
 * node reaches it, so it is not kept and never passed on (#194).
 */
final class PeerBook
{

	/** Monero's {@code P2P_LOCAL_WHITE_PEERLIST_LIMIT 1000} ({@code src/cryptonote_config.h:138}) */
	static final int CONFIRMED_LIMIT = 1_000;

	/** Monero's {@code P2P_LOCAL_GRAY_PEERLIST_LIMIT 5000} ({@code src/cryptonote_config.h:139}) */
	static final int HEARD_LIMIT = 5_000;

	/**
	 * Counted failures without ever a success that drop an address: Bitcoin Core's
	 * {@code ADDRMAN_RETRIES{3}} ({@code src/addrman.cpp:36}, v29.0)
	 */
	static final int RETRIES = 3;

	/**
	 * Counted failures since the last success that drop an address once that success is older than
	 * {@link #FAILURE_WINDOW}: Bitcoin Core's {@code ADDRMAN_MAX_FAILURES{10}}
	 * ({@code src/addrman.cpp:38})
	 */
	static final int MAXIMUM_FAILURES = 10;

	/**
	 * Bitcoin Core's {@code ADDRMAN_MIN_FAIL} of seven days, scaled to a day: the book lives in
	 * memory, and test nodes run for days rather than months
	 */
	static final Duration FAILURE_WINDOW = Duration.ofHours(24);

	/** The wait after the first counted failure; it doubles with each further one */
	static final Duration FIRST_PAUSE = Duration.ofSeconds(60);

	/** The longest wait, Monero's hour ({@code P2P_FAILED_ADDR_FORGET_SECONDS}) */
	static final Duration LONGEST_PAUSE = Duration.ofHours(1);

	private final InstantSource clock;

	private final LinkedHashSet<PeerAddress> confirmed = new LinkedHashSet<>();

	private final LinkedHashSet<PeerAddress> heard = new LinkedHashSet<>();

	private final Set<PeerAddress> itself = new LinkedHashSet<>();

	private final Map<PeerAddress, Attempts> attempts = new HashMap<>();

	/** When a connection to any address last succeeded, or null before the first one */
	private Instant lastGood;

	private boolean keepsLocal;

	/**
	 * What became of an address after a failed attempt
	 *
	 * @param counted
	 *            whether this failure counted against the address
	 * @param failures
	 *            the failures counted since its last success
	 * @param dropped
	 *            whether the address left the book
	 * @param pause
	 *            how long it waits before it is tried again, zero when dropped
	 */
	record Failure(boolean counted, int failures, boolean dropped, Duration pause)
	{
		/**
		 * The failure as the refusal log names it
		 *
		 * @return for example "it waits 60 s before the next attempt (1 counted failure)"
		 */
		String describe()
		{
			String count = failures + " counted failure" + (failures == 1 ? "" : "s");
			if (dropped)
			{
				return "it is dropped from the book (" + count + ")";
			}
			return "it waits " + pause.toSeconds() + " s before the next attempt (" + count
				+ (counted ? "" : ", this one not counted: no connection succeeded since the last")
				+ ")";
		}
	}

	/** What is known of the attempts on one address */
	private static final class Attempts
	{
		Instant lastTry;

		Instant lastSuccess;

		Instant lastCounted;

		int failures;
	}

	/** A book on the system clock */
	PeerBook()
	{
		this(InstantSource.system());
	}

	/**
	 * A book on the given clock, which the tests move by hand
	 *
	 * @param clock
	 *            where the book reads the time of an attempt
	 */
	PeerBook(final InstantSource clock)
	{
		this.clock = clock;
	}

	/**
	 * Keeps local addresses as well, which a book otherwise refuses (#194): for nodes that find
	 * each other on one machine, which only the tests run
	 */
	synchronized void keepLocalAddresses()
	{
		keepsLocal = true;
	}

	/**
	 * An address this node has been connected to; it moves to the newest end, and its count of
	 * failures is cleared. A local one is not kept.
	 */
	synchronized void confirmed(final PeerAddress address)
	{
		Instant now = clock.instant();
		lastGood = now;
		if (!admits(address))
		{
			return;
		}
		Attempts known = attempts.computeIfAbsent(address, key -> new Attempts());
		known.lastTry = now;
		known.lastSuccess = now;
		known.lastCounted = null;
		known.failures = 0;
		heard.remove(address);
		confirmed.remove(address);
		confirmed.add(address);
		trim(confirmed, CONFIRMED_LIMIT);
	}

	/**
	 * Addresses a peer passed on; an address already confirmed, or known to be this node, is not
	 * added again, and a local one not at all. An address already in the book keeps its count.
	 */
	synchronized void heard(final List<PeerAddress> addresses)
	{
		for (PeerAddress address : addresses)
		{
			if (admits(address) && !confirmed.contains(address) && !itself.contains(address))
			{
				heard.add(address);
			}
		}
		trim(heard, HEARD_LIMIT);
	}

	/**
	 * An attempt on the address failed: a dial was refused, or a connection ended without a
	 * handshake. The address waits, or is dropped once it has failed often enough; an address the
	 * book does not hold is left alone
	 *
	 * @param address
	 *            the address that failed
	 * @return what became of it
	 */
	synchronized Failure failed(final PeerAddress address)
	{
		if (!confirmed.contains(address) && !heard.contains(address))
		{
			return new Failure(false, 0, false, Duration.ZERO);
		}
		Instant now = clock.instant();
		Attempts known = attempts.computeIfAbsent(address, key -> new Attempts());
		boolean counted = known.failures == 0
			|| lastGood != null && known.lastCounted != null && lastGood.isAfter(known.lastCounted);
		known.lastTry = now;
		if (counted)
		{
			known.failures++;
			known.lastCounted = now;
		}
		if (droppable(known, now))
		{
			forget(address);
			return new Failure(counted, known.failures, true, Duration.ZERO);
		}
		return new Failure(counted, known.failures, false, pause(known.failures));
	}

	/**
	 * An address that turned out to be this node itself: forgotten, and never taken up again
	 */
	synchronized void isItself(final PeerAddress address)
	{
		forget(address);
		itself.add(address);
	}

	synchronized void forget(final PeerAddress address)
	{
		confirmed.remove(address);
		heard.remove(address);
		attempts.remove(address);
	}

	/**
	 * What this node passes on: the newest {@link PeerList#LIMIT} confirmed addresses that are not
	 * waiting
	 */
	synchronized List<PeerAddress> shareable()
	{
		Instant now = clock.instant();
		List<PeerAddress> all = new ArrayList<>();
		for (PeerAddress address : confirmed)
		{
			if (!waiting(address, now))
			{
				all.add(address);
			}
		}
		return List.copyOf(all.subList(Math.max(0, all.size() - PeerList.LIMIT), all.size()));
	}

	/**
	 * An address to connect to that is not busy and not waiting: a confirmed one first, then one
	 * heard of
	 */
	synchronized Optional<PeerAddress> candidate(final Set<PeerAddress> busy)
	{
		Instant now = clock.instant();
		for (PeerAddress address : confirmed)
		{
			if (!busy.contains(address) && !waiting(address, now))
			{
				return Optional.of(address);
			}
		}
		for (PeerAddress address : heard)
		{
			if (!busy.contains(address) && !waiting(address, now))
			{
				return Optional.of(address);
			}
		}
		return Optional.empty();
	}

	synchronized List<PeerAddress> heardOf()
	{
		return List.copyOf(heard);
	}

	/**
	 * The addresses that wait before they are tried again, confirmed ones and ones heard of
	 *
	 * @return a snapshot
	 */
	synchronized List<PeerAddress> waiting()
	{
		Instant now = clock.instant();
		List<PeerAddress> waiting = new ArrayList<>();
		for (PeerAddress address : attempts.keySet())
		{
			if (waiting(address, now))
			{
				waiting.add(address);
			}
		}
		return List.copyOf(waiting);
	}

	/**
	 * How long an address waits after its counted failures: {@link #FIRST_PAUSE}, doubled with
	 * each further one, never longer than {@link #LONGEST_PAUSE}
	 *
	 * @param failures
	 *            the failures counted since the last success, at least one
	 * @return the wait
	 */
	static Duration pause(final int failures)
	{
		Duration pause = FIRST_PAUSE;
		for (int counted = 1; counted < failures && pause.compareTo(LONGEST_PAUSE) < 0; counted++)
		{
			pause = pause.multipliedBy(2);
		}
		return pause.compareTo(LONGEST_PAUSE) > 0 ? LONGEST_PAUSE : pause;
	}

	private boolean waiting(final PeerAddress address, final Instant now)
	{
		Attempts known = attempts.get(address);
		return known != null && known.failures > 0
			&& now.isBefore(known.lastTry.plus(pause(known.failures)));
	}

	/**
	 * Bitcoin Core's {@code IsTerrible} without its horizon, which a book in memory does not need:
	 * {@link #RETRIES} counted failures and never a success, or {@link #MAXIMUM_FAILURES} and no
	 * success for longer than {@link #FAILURE_WINDOW}
	 */
	private static boolean droppable(final Attempts known, final Instant now)
	{
		if (known.lastSuccess == null)
		{
			return known.failures >= RETRIES;
		}
		return known.failures >= MAXIMUM_FAILURES
			&& Duration.between(known.lastSuccess, now).compareTo(FAILURE_WINDOW) > 0;
	}

	/**
	 * Monero's {@code is_host_allowed} and {@code sanitize_peerlist}: no loopback or private
	 * address, by which no other node reaches the one it names
	 */
	private boolean admits(final PeerAddress address)
	{
		return keepsLocal || !address.isLocal();
	}

	private void trim(final LinkedHashSet<PeerAddress> addresses, final int limit)
	{
		Iterator<PeerAddress> oldest = addresses.iterator();
		while (addresses.size() > limit && oldest.hasNext())
		{
			PeerAddress trimmed = oldest.next();
			oldest.remove();
			if (!confirmed.contains(trimmed) && !heard.contains(trimmed))
			{
				attempts.remove(trimmed);
			}
		}
	}
}
