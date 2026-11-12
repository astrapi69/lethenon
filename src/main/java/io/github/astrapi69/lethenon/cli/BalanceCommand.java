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

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

import io.github.astrapi69.lethenon.AddressText;
import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.Replay;
import io.github.astrapi69.lethenon.SignatureSuite;
import io.github.astrapi69.lethenon.Wallet;
import io.github.astrapi69.lethenon.WalletScan;
import picocli.CommandLine.Command;

/**
 * What this wallet holds, computed from the chain file and asked of nobody.
 * <p>
 * The chain is replayed first; a chain that does not verify has no balance, and the reason is
 * printed instead. Then the wallet's two accounts are read from the replayed state, and the
 * payments to its one-time destinations are found with the view key.
 */
@Command(name = "balance", description = "Replay the chain file and print what this wallet holds. "
	+ "The password is the first line of standard input.")
class BalanceCommand extends ChainCommand
{

	/**
	 * Creates the command; picocli instantiates it reflectively
	 */
	BalanceCommand()
	{
	}

	@Override
	int run(final PrintStream out) throws IOException
	{
		Wallet owner = openWallet();
		List<BlockBody> blocks = requireChain();
		Replay replay = Replay.verify(blocks);
		out.println(replay.describe());
		String chain = chainOf(blocks);
		for (SignatureSuite suite : SignatureSuite.values())
		{
			Bytes account = owner.spendKey(suite);
			out.println("account (" + suite.identifier() + "): " + accountText(account, chain)
				+ " holds " + replay.finalState().balanceOf(account) + " LETH");
		}
		out.println("address (publish this): " + AddressText.of(owner.address(), chain));
		out.println("one-time payments: " + WalletScan
			.over(blocks, owner.address(), owner.viewKeyPair().getPrivate()).describe());
		return 0;
	}
}
