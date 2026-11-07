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
 * A transfer, before anybody signed it.
 * <p>
 * The account model: one signature per transaction rather than one per input, which is what makes a
 * 3309 byte post-quantum signature affordable at all. The nonce is the account's own counter and is
 * what stops the same transfer from being replayed on this chain; the chain identifier stops it
 * from being replayed on another one.
 *
 * @param chainIdentifier
 *            the chain this belongs to
 * @param nonce
 *            the sender account's counter, one higher than its last accepted transaction
 * @param sender
 *            the sender's public key
 * @param recipient
 *            where the transfer goes: a destination with its scheme, key, ephemeral key and view
 *            tag, one-time when the scheme is
 * @param amount
 *            what is transferred
 * @param fee
 *            what the miner keeps
 * @param memo
 *            the text that is signed with the transfer - the protest is inside the chain, not
 *            beside it
 */
public record TransactionBody(String chainIdentifier, long nonce, Bytes sender, Destination recipient,
	Amount amount, Amount fee, String memo)
{

	/**
	 * How long a memo may be. A block carries many transactions, and a memo nobody bounded is a
	 * block size nobody bounded
	 */
	public static final int MEMO_LIMIT = 280;

	/**
	 * How this transfer carries its amount: in the clear, which is what version 1 of the
	 * transaction encoding means, so the wire does not name it
	 *
	 * @return {@link AmountScheme#PLAIN}
	 */
	public AmountScheme amountScheme()
	{
		return AmountScheme.PLAIN;
	}

	/**
	 * A transfer's parts, checked before the transfer exists
	 *
	 * @throws IllegalArgumentException
	 *             for a negative nonce or a memo longer than {@value #MEMO_LIMIT} characters
	 */
	public TransactionBody
	{
		if (nonce < 0L)
		{
			throw new IllegalArgumentException("a nonce counts upwards from zero, unlike " + nonce);
		}
		if (memo.length() > MEMO_LIMIT)
		{
			throw new IllegalArgumentException(
				"a memo is at most " + MEMO_LIMIT + " characters, and this one is " + memo.length());
		}
	}
}
