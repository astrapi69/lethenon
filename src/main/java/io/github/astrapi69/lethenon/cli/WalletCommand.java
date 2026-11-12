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
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import io.github.astrapi69.mystic.crypt.secret.SecretBuffers;
import io.github.astrapi69.lethenon.AddressText;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.SignatureSuite;
import io.github.astrapi69.lethenon.Wallet;
import io.github.astrapi69.lethenon.WalletFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Makes a wallet file: a new wallet, or one brought back from its 24 words
 */
@Command(name = "wallet", description = "Create a wallet, or restore one from its 24 words.",
	subcommands = { WalletCommand.Create.class, WalletCommand.Restore.class })
class WalletCommand
{

	/**
	 * Creates the command; picocli instantiates it reflectively
	 */
	WalletCommand()
	{
	}

	/**
	 * A new wallet, written to a new file, its 24 words printed once
	 */
	@Command(name = "create", description = "Create a new wallet file. The password is the first "
		+ "line of standard input. Prints the account and the 24 words to write down.")
	static class Create implements Callable<Integer>
	{

		@Option(names = "--wallet", required = true, description = "the wallet file to create")
		Path wallet;

		/**
		 * Creates the command; picocli instantiates it reflectively
		 */
		Create()
		{
		}

		@Override
		public Integer call()
		{
			try
			{
				BufferedReader input = ChainCommand.standardInput();
				char[] password = ChainCommand
					.nextLineOfStandardInput(input, "the new wallet's password").toCharArray();
				Wallet created = Wallet.create();
				write(created, password);
				System.out.println("write these 24 words down - with them, or with enough "
					+ "shares, the wallet comes back without this file:");
				System.out.println(created.phrase());
				return 0;
			}
			catch (IllegalArgumentException | IOException refused)
			{
				System.err.println(refused.getMessage());
				return 1;
			}
		}

		/**
		 * Writes the new wallet's file and overwrites the password afterwards, on success and on
		 * failure. Package-visible so that the zeros can be asserted (#45)
		 *
		 * @param created
		 *            the wallet
		 * @param password
		 *            the password, zero-filled when this returns
		 * @throws IOException
		 *             if the file cannot be written
		 */
		void write(final Wallet created, final char[] password) throws IOException
		{
			try
			{
				WalletFile.write(wallet, created, password);
			}
			finally
			{
				SecretBuffers.wipe(password);
			}
			System.out.println("wallet written to " + wallet);
			printAddresses(created);
		}
	}

	/**
	 * A wallet brought back from its 24 words, written to a new file
	 */
	@Command(name = "restore", description = "Restore a wallet from its 24 words into a new file. "
		+ "Standard input: the words on the first line, the new password on the second.")
	static class Restore implements Callable<Integer>
	{

		@Option(names = "--wallet", required = true, description = "the wallet file to create")
		Path wallet;

		/**
		 * Creates the command; picocli instantiates it reflectively
		 */
		Restore()
		{
		}

		@Override
		public Integer call()
		{
			try
			{
				BufferedReader input = ChainCommand.standardInput();
				Wallet restored = Wallet
					.fromPhrase(ChainCommand.nextLineOfStandardInput(input, "the 24 words"));
				char[] password = ChainCommand
					.nextLineOfStandardInput(input, "the new wallet's password").toCharArray();
				write(restored, password);
				System.out.println("wallet restored to " + wallet);
				printAddresses(restored);
				return 0;
			}
			catch (IllegalArgumentException | IOException refused)
			{
				System.err.println(refused.getMessage());
				return 1;
			}
		}

		/**
		 * Writes the restored wallet's file and overwrites the password afterwards, on success and
		 * on failure. Package-visible so that the zeros can be asserted (#45)
		 *
		 * @param restored
		 *            the wallet
		 * @param password
		 *            the password, zero-filled when this returns
		 * @throws IOException
		 *             if the file cannot be written
		 */
		void write(final Wallet restored, final char[] password) throws IOException
		{
			try
			{
				WalletFile.write(wallet, restored, password);
			}
			finally
			{
				SecretBuffers.wipe(password);
			}
		}
	}

	/**
	 * Prints the account and the published address of a wallet for both chains, in the form --to
	 * and --to-address take (#37): the keys of a wallet are the same on both, and only the network
	 * and the checksum of the written form tell them apart (ADR 0007, #309)
	 *
	 * @param wallet
	 *            the wallet
	 */
	static void printAddresses(final Wallet wallet)
	{
		Bytes account = wallet.spendKey(SignatureSuite.ED25519);
		for (String chain : List.of(Chain.TEST_IDENTIFIER, Chain.IDENTIFIER))
		{
			String name = Chain.IDENTIFIER.equals(chain) ? "main chain" : "test chain";
			System.out.println("account (ed25519), " + name + ": "
				+ ChainCommand.accountText(account, chain));
		}
		for (String chain : List.of(Chain.TEST_IDENTIFIER, Chain.IDENTIFIER))
		{
			String name = Chain.IDENTIFIER.equals(chain) ? "main chain" : "test chain";
			System.out.println("address (publish this), " + name + ": "
				+ AddressText.of(wallet.address(), chain));
		}
	}
}
