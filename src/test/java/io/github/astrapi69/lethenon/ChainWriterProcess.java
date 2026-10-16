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

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

/**
 * The process the crash tests kill (#202): it writes chains into a {@link ChainFile} as fast as it
 * can and says on standard output what it is about to write and what it has written, so that the
 * test knows which states a database may hold after the kill.
 * <p>
 * {@code grow <database> <chain>}: writes the first 2, 3, 4, ... blocks of the chain, each with
 * {@link #waitingFor(int, List)} transfers waiting, one transaction per block. {@code fork
 * <database> <first> <second>}: writes the second chain, the first, the second, ... over each
 * other, each a rollback over several blocks, with none waiting beside the first and three beside
 * the second. Every step prints {@code begin <step>} before the write and {@code done <step>}
 * after it. The chains come in their canonical encoding, written by the test.
 */
public final class ChainWriterProcess
{

	/** How many transfers wait beside a chain of the given length when growing */
	static final int WAITING_CYCLE = 4;

	private ChainWriterProcess()
	{
	}

	/**
	 * Writes until killed or out of blocks
	 *
	 * @param arguments
	 *            the mode, the database, the chain or chains
	 * @throws Exception
	 *             on anything; the test reads it as a failed run
	 */
	public static void main(final String[] arguments) throws Exception
	{
		ChainFile file = new ChainFile(Path.of(arguments[1]));
		List<SignedTransaction> transfers = transfers(WAITING_CYCLE - 1);
		PrintStream out = System.out;
		out.println("ready");
		out.flush();
		if ("grow".equals(arguments[0]))
		{
			List<BlockBody> chain = read(arguments[2]);
			for (int length = 2; length <= chain.size(); length++)
			{
				int grown = length;
				step(out, grown, () -> file.write(chain.subList(0, grown), waitingFor(grown,
					transfers)));
			}
		}
		else
		{
			List<BlockBody> first = read(arguments[2]);
			List<BlockBody> second = read(arguments[3]);
			for (int step = 1; step < Integer.MAX_VALUE; step++)
			{
				boolean toSecond = step % 2 == 1;
				int taken = step;
				step(out, taken, () -> file.write(toSecond ? second : first,
					toSecond ? transfers : List.of()));
			}
		}
	}

	/**
	 * The transfers that wait beside a grown chain of the given length
	 *
	 * @param length
	 *            the chain's length
	 * @param transfers
	 *            the transfers to take them from, at least {@link #WAITING_CYCLE} - 1
	 * @return the first {@code length % WAITING_CYCLE} of them
	 */
	static List<SignedTransaction> waitingFor(final int length,
		final List<SignedTransaction> transfers)
	{
		return transfers.subList(0, length % WAITING_CYCLE);
	}

	/**
	 * Signed transfers of a fresh key, to wait beside a chain; their content does not matter, only
	 * that they are written with it
	 *
	 * @param count
	 *            how many
	 * @return the transfers
	 */
	static List<SignedTransaction> transfers(final int count)
	{
		KeyPair key = TransactionSigner.newKeyPair(SignatureSuite.ED25519);
		Bytes sender = TransactionSigner.asBytes(key.getPublic());
		List<SignedTransaction> transfers = new ArrayList<>();
		for (int nonce = 0; nonce < count; nonce++)
		{
			transfers.add(TransactionSigner.sign(
				new TransactionBody(Chain.TEST_IDENTIFIER, nonce, sender,
					Destination.direct(Bytes.of(new byte[] { 7 })), Amount.ofLeth(1L), Amount.ZERO,
					"waiting " + nonce),
				SignatureSuite.ED25519, key.getPrivate()));
		}
		return transfers;
	}

	private static List<BlockBody> read(final String chain) throws Exception
	{
		return CanonicalEncoding.readChain(Files.readAllBytes(Path.of(chain)));
	}

	private static void step(final PrintStream out, final int step, final Write write)
		throws Exception
	{
		out.println("begin " + step);
		out.flush();
		write.run();
		out.println("done " + step);
		out.flush();
	}

	@FunctionalInterface
	private interface Write
	{
		void run() throws Exception;
	}
}
