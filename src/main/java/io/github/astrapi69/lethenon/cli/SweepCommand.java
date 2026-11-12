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
import java.util.ArrayList;
import java.util.List;

import io.github.astrapi69.lethenon.Amount;
import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.SignatureSuite;
import io.github.astrapi69.lethenon.SignedTransaction;
import io.github.astrapi69.lethenon.Sweeps;
import io.github.astrapi69.lethenon.Wallet;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Moves what was paid to this wallet's one-time destinations onto its own account.
 * <p>
 * One transfer per destination, because each destination is an account of its own. The command says
 * out loud what sweeping costs: the transfers name those destinations and the account together, so
 * whoever reads the chain afterwards knows they belong to one holder. Receiving is unlinkable;
 * spending is the moment that ends (#37).
 */
@Command(name = "sweep",
	description = "Move what was paid to this wallet's one-time destinations onto its own "
		+ "account, one transfer per destination. This publishes the link between those "
		+ "destinations and the account, which is what spending them costs.")
class SweepCommand extends ChainCommand
{

	@Option(names = "--fee", defaultValue = "0",
		description = "the fee of each transfer in LETH, taken out of what that destination "
			+ "holds; default: 0")
	String fee;

	@Option(names = "--memo", defaultValue = "swept from a one-time destination",
		description = "the text signed with each transfer")
	String memo;

	SweepCommand()
	{
	}

	@Override
	int run(final PrintStream out) throws IOException
	{
		Wallet wallet = openWallet();
		List<SignedTransaction> waiting = new ArrayList<>(readPending());
		List<BlockBody> blocks = requireChain();
		List<SignedTransaction> sweep = Sweeps.prepare(wallet, blocks, waiting,
			Amount.parseLeth(fee), memo);
		if (sweep.isEmpty())
		{
			out.println("nothing to sweep: no one-time payment of this wallet holds more than "
				+ "the fee of " + Amount.parseLeth(fee) + " LETH");
			return 0;
		}
		Amount total = Amount.ZERO;
		for (SignedTransaction transfer : sweep)
		{
			total = total.plus(transfer.body().amount());
		}
		waiting.addAll(sweep);
		writePending(waiting);
		out.println("signed " + sweep.size() + " transfer" + (sweep.size() == 1 ? "" : "s")
			+ " sweeping " + total + " LETH onto "
			+ accountText(wallet.spendKey(SignatureSuite.ED25519), chainOf(blocks))
			+ "; they wait for the next block (" + waiting.size() + " waiting)");
		out.println("the chain will show those destinations and this account together - that is "
			+ "what spending a one-time payment costs");
		return 0;
	}
}
