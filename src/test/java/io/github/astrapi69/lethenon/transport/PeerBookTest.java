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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the address book does with an address that fails (ADR 0003, #296): it waits, longer after
 * each counted failure, and leaves the book only after repeated failures over time. The clock is
 * moved by hand, so every rule is checked at its edge
 */
class PeerBookTest
{

	private static final PeerAddress FAILING = new PeerAddress("198.51.100.7", 18480);

	private static final PeerAddress OTHER = new PeerAddress("198.51.100.8", 18480);

	/** A clock that moves only when the test says so */
	private static final class ManualClock implements InstantSource
	{
		private Instant now = Instant.parse("2026-11-15T12:00:00Z");

		@Override
		public Instant instant()
		{
			return now;
		}

		void advance(final Duration by)
		{
			now = now.plus(by);
		}
	}

	private final ManualClock clock = new ManualClock();

	private final PeerBook book = new PeerBook(clock);

	@Test
	@DisplayName("an address heard of that fails once stays heard of, and waits")
	void anAddressHeardOf_thatFailsOnce_staysAndWaits()
	{
		book.heard(List.of(FAILING));

		PeerBook.Failure failure = book.failed(FAILING);

		assertEquals(new PeerBook.Failure(true, 1, false, Duration.ofSeconds(60)), failure);
		assertEquals(List.of(FAILING), book.heardOf());
		assertEquals(List.of(FAILING), book.waiting());
		assertEquals(Optional.empty(), book.candidate(Set.of()));
	}

	@Test
	@DisplayName("a confirmed address that fails is not passed on while it waits, and is again after")
	void aConfirmedAddress_thatFails_isNotPassedOnWhileItWaits()
	{
		book.confirmed(FAILING);
		book.confirmed(OTHER);

		book.failed(FAILING);

		assertEquals(List.of(OTHER), book.shareable());
		clock.advance(PeerBook.FIRST_PAUSE);
		assertEquals(List.of(FAILING, OTHER), book.shareable());
	}

	@Test
	@DisplayName("a waiting address stays waiting when a peer names it again: hearing it keeps its count")
	void aWaitingAddress_staysWaiting_whenHeardAgain()
	{
		book.heard(List.of(FAILING));
		book.failed(FAILING);

		book.heard(List.of(FAILING));

		assertEquals(Optional.empty(), book.candidate(Set.of()));
		assertEquals(List.of(FAILING), book.waiting());
	}

	@Test
	@DisplayName("the pause is respected to the second, and the address is offered again after it")
	void thePause_isRespected_andTheAddressIsOfferedAfterIt()
	{
		book.heard(List.of(FAILING));
		book.failed(FAILING);

		clock.advance(PeerBook.FIRST_PAUSE.minusSeconds(1));
		assertEquals(Optional.empty(), book.candidate(Set.of()));
		clock.advance(Duration.ofSeconds(1));
		assertEquals(Optional.of(FAILING), book.candidate(Set.of()));
	}

	static Stream<Arguments> pauses()
	{
		return Stream.of(Arguments.of(1, Duration.ofSeconds(60)),
			Arguments.of(2, Duration.ofSeconds(120)), Arguments.of(3, Duration.ofSeconds(240)),
			Arguments.of(6, Duration.ofSeconds(1_920)), Arguments.of(7, Duration.ofHours(1)),
			Arguments.of(10, Duration.ofHours(1)), Arguments.of(64, Duration.ofHours(1)));
	}

	@ParameterizedTest(name = "after {0} counted failure(s) an address waits {1}")
	@MethodSource("pauses")
	void thePause_doubles_upToAnHour(final int failures, final Duration expected)
	{
		assertEquals(expected, PeerBook.pause(failures));
	}

	@Test
	@DisplayName("an outage on this node's side counts once against each address, however often it fails")
	void anOutage_countsOnceAgainstEachAddress()
	{
		book.heard(List.of(FAILING));
		book.failed(FAILING);
		for (int attempt = 0; attempt < 50; attempt++)
		{
			clock.advance(PeerBook.LONGEST_PAUSE);
			PeerBook.Failure failure = book.failed(FAILING);
			assertFalse(failure.counted(), "attempt " + attempt + ": " + failure);
			assertEquals(1, failure.failures());
		}

		assertEquals(List.of(FAILING), book.heardOf());
	}

	@Test
	@DisplayName("an address that never succeeded is dropped at its third counted failure, not before")
	void anAddressThatNeverSucceeded_isDroppedAtItsThirdCountedFailure()
	{
		book.heard(List.of(FAILING));
		assertFalse(failCounted().dropped());
		assertFalse(failCounted().dropped());

		PeerBook.Failure third = failCounted();

		assertTrue(third.dropped(), third.toString());
		assertEquals(PeerBook.RETRIES, third.failures());
		assertEquals(List.of(), book.heardOf());
		assertEquals(List.of(), book.waiting());
	}

	@Test
	@DisplayName("ten counted failures drop an address only when its last success is more than a day old")
	void tenCountedFailures_dropAnAddress_onlyAfterADayWithoutSuccess()
	{
		book.confirmed(FAILING);
		Instant success = clock.instant();
		for (int failure = 1; failure < PeerBook.MAXIMUM_FAILURES; failure++)
		{
			assertFalse(failCounted().dropped(), "failure " + failure);
		}

		PeerBook.Failure tenthWithinTheDay = failCounted();
		Duration sinceTheSuccess = Duration.between(success, clock.instant());
		assertTrue(sinceTheSuccess.compareTo(PeerBook.FAILURE_WINDOW) < 0,
			"the ten failures took " + sinceTheSuccess);
		assertEquals(PeerBook.MAXIMUM_FAILURES, tenthWithinTheDay.failures());
		assertFalse(tenthWithinTheDay.dropped(), tenthWithinTheDay.toString());

		clock.advance(PeerBook.FAILURE_WINDOW);
		book.confirmed(OTHER);
		PeerBook.Failure afterTheDay = book.failed(FAILING);
		assertTrue(afterTheDay.dropped(), afterTheDay.toString());
		assertEquals(List.of(OTHER), book.shareable());
	}

	@Test
	@DisplayName("a success clears the count, and the address is offered and passed on at once")
	void aSuccess_clearsTheCount()
	{
		book.heard(List.of(FAILING));
		failCounted();
		failCounted();

		book.confirmed(FAILING);

		assertEquals(List.of(), book.waiting());
		assertEquals(Optional.of(FAILING), book.candidate(Set.of(OTHER)));
		assertEquals(new PeerBook.Failure(true, 1, false, PeerBook.FIRST_PAUSE),
			book.failed(FAILING));
	}

	@Test
	@DisplayName("a dropped address comes back fresh when a peer names it again")
	void aDroppedAddress_comesBackFresh_whenHeardAgain()
	{
		book.heard(List.of(FAILING));
		failCounted();
		failCounted();
		failCounted();

		book.heard(List.of(FAILING));

		assertEquals(Optional.of(FAILING), book.candidate(Set.of(OTHER)));
		assertEquals(new PeerBook.Failure(true, 1, false, PeerBook.FIRST_PAUSE),
			book.failed(FAILING));
	}

	@Test
	@DisplayName("an address the book does not hold is left alone when it fails")
	void anAddressNotInTheBook_isLeftAlone()
	{
		assertEquals(new PeerBook.Failure(false, 0, false, Duration.ZERO), book.failed(FAILING));
		assertEquals(List.of(), book.waiting());
		assertEquals(List.of(), book.heardOf());
	}

	/**
	 * A failure that counts: a connection to another address succeeds first, as it would on a node
	 * whose own network works, then the address is tried once its pause is over. That other address
	 * is confirmed by it, so a test that asks for a candidate counts it as busy
	 */
	private PeerBook.Failure failCounted()
	{
		clock.advance(PeerBook.LONGEST_PAUSE);
		book.confirmed(OTHER);
		clock.advance(Duration.ofSeconds(1));
		PeerBook.Failure failure = book.failed(FAILING);
		assertTrue(failure.counted(), failure.toString());
		return failure;
	}
}
