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

import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A node keeps the most recent refusals and counts all of them (#183): a seed node runs for months,
 * and an unbounded list grew with every refused connection and kept every refused address for the
 * node's whole run
 */
class RefusalsTest
{

	@Test
	@DisplayName("past the limit the oldest refusals go, the most recent stay in order, and all are counted")
	void pastTheLimit_theMostRecentStay_andAllAreCounted()
	{
		Refusals refusals = refusalsAdded(Refusals.KEPT + 50);

		assertEquals(Refusals.KEPT, refusals.recent().size());
		assertEquals("refusal 50", refusals.recent().get(0));
		assertEquals("refusal " + (Refusals.KEPT + 49), refusals.recent().get(Refusals.KEPT - 1));
		assertEquals(Refusals.KEPT + 50, refusals.count());
	}

	@ParameterizedTest(name = "{0} refusal(s): {1} kept")
	@CsvSource({ "0, 0", "1, 1", "99, 99", "100, 100", "101, 100", "1000, 100" })
	@DisplayName("at most KEPT are kept, whatever the count")
	void atMostKept_areKept(int added, int kept)
	{
		Refusals refusals = refusalsAdded(added);

		assertEquals(kept, refusals.recent().size());
		assertEquals(added, refusals.count());
	}

	private static Refusals refusalsAdded(int count)
	{
		Refusals refusals = new Refusals();
		IntStream.range(0, count).forEach(index -> refusals.add("refusal " + index));
		return refusals;
	}
}
