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
package io.github.astrapi69.lethenon.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import io.github.astrapi69.mystic.crypt.secret.SecretBuffers;
import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.ChainRejected;
import io.github.astrapi69.lethenon.SignedTransaction;
import io.github.astrapi69.lethenon.Wallet;
import io.github.astrapi69.lethenon.WalletFile;
import io.github.astrapi69.lethenon.transport.PeerAddress;
import picocli.CommandLine.Option;

/**
 * What every command that works on a chain shares: the chain file, the transfers waiting for the
 * next block, the wallet and its password, and the one place where a refusal becomes exit code 1.
 * <p>
 * The transfers waiting for a block live next to the chain, in {@code <chain>.pending}: a format
 * version byte, then each signed transfer in its canonical encoding, length first. {@code send}
 * adds to it, {@code mine} empties it into a block. It is not part of the chain and nobody has to
 * trust it: a block made from it is replayed before it is written, and a transfer that does not
 * hold makes {@code mine} refuse rather than write a chain that would not verify.
 */
abstract class ChainCommand implements Callable<Integer>
{

	@Option(names = "--chain", required = true, description = "the chain file")
	Path chain;

	@Option(names = "--wallet", required = true,
		description = "the wallet file; its password is read from the first line of standard input")
	Path wallet;

	/**
	 * Runs the command, turning every refusal into its reason on standard error and exit code 1
	 */
	@Override
	public final Integer call()
	{
		try
		{
			return run(System.out);
		}
		catch (ChainRejected | IllegalArgumentException | IllegalStateException
			| SecurityException | IOException refused)
		{
			System.err.println(refused.getMessage());
			return 1;
		}
	}

	/**
	 * What the command does
	 *
	 * @param out
	 *            where to report
	 * @return the exit code
	 * @throws IOException
	 *             if a file cannot be read or written
	 */
	abstract int run(PrintStream out) throws IOException;

	/**
	 * Opens the wallet with the password on the first line of standard input
	 *
	 * @return the wallet
	 * @throws IOException
	 *             if the wallet file cannot be read
	 */
	Wallet openWallet() throws IOException
	{
		return openWallet(firstLineOfStandardInput());
	}

	/**
	 * Opens the wallet with the given password and overwrites it afterwards, on success and on
	 * failure. The array is this command's own, read from standard input; WalletFile reads it
	 * without touching it, in either layout (#45), so this is the one wipe there is. Package-visible
	 * so that the zeros can be asserted rather than assumed
	 *
	 * @param password
	 *            the password, zero-filled when this returns
	 * @return the wallet
	 * @throws IOException
	 *             if the wallet file cannot be read
	 */
	Wallet openWallet(final char[] password) throws IOException
	{
		return openWallet(wallet, password);
	}

	/**
	 * Opens a wallet file with the given password and overwrites the password afterwards, on
	 * success and on failure; for commands that name their wallet some other way
	 *
	 * @param file
	 *            the wallet file
	 * @param password
	 *            the password, zero-filled when this returns
	 * @return the wallet
	 * @throws IOException
	 *             if the wallet file cannot be read
	 */
	static Wallet openWallet(final Path file, final char[] password) throws IOException
	{
		try
		{
			return WalletFile.read(file, password);
		}
		finally
		{
			SecretBuffers.wipe(password);
		}
	}

	/**
	 * The chain as the file holds it, empty when there is no file yet
	 *
	 * @return the blocks, genesis first
	 * @throws IOException
	 *             if the file cannot be read
	 */
	List<BlockBody> readChain() throws IOException
	{
		return chainFile().read();
	}

	/**
	 * The chain, which has to exist for this command
	 *
	 * @return the blocks, genesis first
	 * @throws IOException
	 *             if the file cannot be read
	 */
	List<BlockBody> requireChain() throws IOException
	{
		return chainFile().require();
	}

	/**
	 * Writes the chain, atomically - see {@link ChainFile}
	 *
	 * @param blocks
	 *            the whole chain
	 * @throws IOException
	 *             if it cannot be written
	 */
	void writeChain(final List<BlockBody> blocks) throws IOException
	{
		chainFile().write(blocks);
	}

	/**
	 * The transfers waiting for the next block
	 *
	 * @return the transfers, in the order they were signed
	 * @throws IOException
	 *             if the file cannot be read
	 */
	List<SignedTransaction> readPending() throws IOException
	{
		return chainFile().readPending();
	}

	/**
	 * Replaces the transfers waiting for the next block
	 *
	 * @param transfers
	 *            all of them; none deletes the file
	 * @throws IOException
	 *             if it cannot be written
	 */
	void writePending(final List<SignedTransaction> transfers) throws IOException
	{
		chainFile().writePending(transfers);
	}

	/**
	 * Reads the next line of standard input
	 *
	 * @param input
	 *            standard input, as {@link #standardInput()} gave it
	 * @param what
	 *            what the line is expected to hold, for the message when it is missing
	 * @return the line
	 * @throws IllegalArgumentException
	 *             when there is no line, or an empty one
	 * @throws IOException
	 *             if standard input cannot be read
	 */
	static String nextLineOfStandardInput(final BufferedReader input, final String what)
		throws IOException
	{
		String line = input.readLine();
		if (line == null || line.isBlank())
		{
			throw new IllegalArgumentException("expected " + what + " on standard input");
		}
		return line;
	}

	/**
	 * Standard input as lines, for the commands that read more than the password
	 *
	 * @return a reader over standard input
	 */
	static BufferedReader standardInput()
	{
		return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
	}

	/**
	 * The hex of an account key, as every command prints and takes it
	 *
	 * @param account
	 *            the account key
	 * @return its hex
	 */
	static String hex(final Bytes account)
	{
		return account.toString();
	}

	/**
	 * Refuses an onion peer that no option of the command routes through Tor, naming those options.
	 * The library refuses it as well, in words without an option, since its callers have none
	 * (#135)
	 *
	 * @param peers
	 *            the peers given on the command line
	 * @param routed
	 *            whether an option routes onion peers through Tor
	 * @param options
	 *            the options that would, as the message names them
	 */
	static void refuseAnOnionPeerWithoutTor(final List<PeerAddress> peers, final boolean routed,
		final String options)
	{
		if (routed)
		{
			return;
		}
		peers.stream().filter(PeerAddress::isOnion).findFirst().ifPresent(onion -> {
			throw new IllegalArgumentException("the onion address " + onion
				+ " is reached only through Tor: give its SOCKS proxy with " + options);
		});
	}

	static char[] firstLineOfStandardInput() throws IOException
	{
		return nextLineOfStandardInput(standardInput(), "the wallet's password").toCharArray();
	}

	private ChainFile chainFile()
	{
		return new ChainFile(chain);
	}
}
