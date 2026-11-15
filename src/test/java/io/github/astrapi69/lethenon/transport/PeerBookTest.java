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

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the address book does with an address that fails, pinned as it is before #296: forgotten at
 * once, wherever it stood, and offered again the moment a peer names it again
 */
class PeerBookTest
{

	private static final PeerAddress FAILING = new PeerAddress("198.51.100.7", 18480);

	private static final PeerAddress OTHER = new PeerAddress("198.51.100.8", 18480);

	@Test
	@DisplayName("an address heard of that fails once is gone from the book (before #296)")
	void anAddressHeardOf_thatFailsOnce_isGone()
	{
		PeerBook book = new PeerBook();
		book.heard(List.of(FAILING));

		book.forget(FAILING);

		assertEquals(List.of(), book.heardOf());
		assertEquals(Optional.empty(), book.candidate(Set.of()));
	}

	@Test
	@DisplayName("a confirmed address that fails once is no longer passed on (before #296)")
	void aConfirmedAddress_thatFailsOnce_isNoLongerPassedOn()
	{
		PeerBook book = new PeerBook();
		book.confirmed(FAILING);
		book.confirmed(OTHER);

		book.forget(FAILING);

		assertEquals(List.of(OTHER), book.shareable());
	}

	@Test
	@DisplayName("an address that failed is offered again at once when a peer names it again (before #296)")
	void aFailedAddress_isOfferedAgainAtOnce_whenHeardAgain()
	{
		PeerBook book = new PeerBook();
		book.heard(List.of(FAILING));
		book.forget(FAILING);

		book.heard(List.of(FAILING));

		assertEquals(Optional.of(FAILING), book.candidate(Set.of()));
	}
}
