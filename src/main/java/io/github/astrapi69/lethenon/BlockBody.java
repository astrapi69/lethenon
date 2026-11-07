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

import java.util.List;

/**
 * A block before it was mined: everything that has to be fixed before anybody looks for a pun.
 *
 * @param chainIdentifier
 *            the chain this belongs to, inside the hash like everywhere else
 * @param height
 *            how many blocks precede this one; the genesis block is zero
 * @param previousHash
 *            the hash of the block before, which is what makes this a chain
 * @param beneficiary
 *            the account this block pays its reward to, the genesis block's included (#111). It is
 *            inside the
 *            hash, so it cannot be changed without mining the block again - the chain says who was
 *            paid, not whoever replays it (lethenon#23)
 * @param transactions
 *            the signed transfers this block carries, in the order they are applied
 * @param timestamp
 *            when the block was made, as milliseconds since the epoch - a parameter rather than a
 *            clock reading, so that a third party can recompute the hash
 * @param difficulty
 *            how many leading zero BITS the block hash needs, which is what mining looks for
 * @param pun
 *            the miner's wordplay. It is what varies while mining, instead of a numeric nonce, and
 *            it is covered by the hash like everything else - otherwise mining it would prove
 *            nothing
 */
public record BlockBody(String chainIdentifier, long height, Bytes previousHash,
	Bytes beneficiary, List<SignedTransaction> transactions, long timestamp, int difficulty,
	String pun)
{

	/** How long a pun may be, for the same reason a memo is bounded */
	public static final int PUN_LIMIT = 280;

	/**
	 * A block's parts, checked before the block exists
	 *
	 * @throws IllegalArgumentException
	 *             for a negative height or difficulty, a block that names no account it pays, or a
	 *             pun longer than {@value #PUN_LIMIT} characters
	 */
	public BlockBody
	{
		if (height < 0L)
		{
			throw new IllegalArgumentException("a height counts from zero, unlike " + height);
		}
		if (beneficiary.length() == 0)
		{
			throw new IllegalArgumentException("a block names the account it pays; block " + height
				+ " names none");
		}
		if (difficulty < 0)
		{
			throw new IllegalArgumentException(
				"a difficulty counts leading zero bits, unlike " + difficulty);
		}
		if (pun.length() > PUN_LIMIT)
		{
			throw new IllegalArgumentException(
				"a pun is at most " + PUN_LIMIT + " characters, and this one is " + pun.length());
		}
		transactions = List.copyOf(transactions);
	}
}
