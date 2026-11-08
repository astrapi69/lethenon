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
package io.github.astrapi69.lethenon;

/**
 * The limits a chain puts on each block beyond the scheme activations: how far its timestamp may
 * run ahead of the verifying node's clock, and how large it may be
 *
 * @param chainIdentifier
 *            the chain the limits belong to
 * @param futureMillis
 *            how many milliseconds a block's timestamp may lie after the verifying node's clock;
 *            Monero's {@code CRYPTONOTE_BLOCK_FUTURE_TIME_LIMIT} is two hours
 *            ({@code src/cryptonote_config.h:47}, #96)
 * @param maximumBytes
 *            the largest a block may be, as {@link CanonicalEncoding#blockSize} counts it
 */
public record BlockLimits(String chainIdentifier, long futureMillis, int maximumBytes)
{

	/** Two hours, Monero's value, at the same two-minute target block time */
	public static final long TWO_HOURS = 2L * 60L * 60L * 1_000L;

	/**
	 * 300,000 bytes: Monero's {@code CRYPTONOTE_BLOCK_GRANTED_FULL_REWARD_ZONE_V5}
	 * ({@code src/cryptonote_config.h:60}), taken as a hard limit; lethenon has no reward penalty
	 * for a dynamic one to act on (#99)
	 */
	public static final int MAXIMUM_BLOCK_BYTES = 300_000;

	/**
	 * The limits, checked before they are used
	 *
	 * @throws IllegalArgumentException
	 *             for a negative limit
	 */
	public BlockLimits
	{
		if (futureMillis < 0L)
		{
			throw new IllegalArgumentException(
				"a future limit of " + futureMillis + " ms; it is 0 or more");
		}
		if (maximumBytes < 1)
		{
			throw new IllegalArgumentException(
				"a block size limit of " + maximumBytes + " bytes; it is 1 or more");
		}
	}
}
