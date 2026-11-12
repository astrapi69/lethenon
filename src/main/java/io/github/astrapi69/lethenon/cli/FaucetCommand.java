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

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.SignatureSuite;
import io.github.astrapi69.lethenon.Wallet;
import picocli.CommandLine.Command;

/**
 * Hands out a fixed amount from the first miner's account (lethenon#2, decision 7).
 * <p>
 * Not a mint and no rule of the chain: the chain sees an ordinary signed transfer. It used to pay
 * from the genesis block's beneficiary; since a genesis block pays its reward to the burn account
 * (#148), it pays from the account block 1 paid, the first wallet that mined on the chain. Only
 * that wallet can run it, and it pays out of what that account holds.
 */
@Command(name = "faucet", description = "Send " + FaucetCommand.AMOUNT_TEXT + " LETH from the "
	+ "wallet that mined block 1 to an account. The password is the first line of standard input.")
class FaucetCommand extends TransferCommand
{

	/**
	 * What the faucet hands out: less than one reward of block 1, which pays 1,983.998016 LETH under
	 * the declining reward (#133), so that the faucet works once block 1 is mined (#148)
	 */
	static final String AMOUNT_TEXT = "1000";

	/**
	 * Creates the command; picocli instantiates it reflectively
	 */
	FaucetCommand()
	{
	}

	@Override
	int run(final PrintStream out) throws IOException
	{
		Wallet holder = openWallet();
		List<BlockBody> blocks = requireChain();
		if (blocks.size() < 2)
		{
			throw new IllegalArgumentException("the faucet pays from the account block 1 paid, and "
				+ "this chain has no block 1 yet: mine it first");
		}
		Bytes firstMiner = blocks.get(1).beneficiary();
		if (!firstMiner.equals(holder.spendKey(SignatureSuite.ED25519)))
		{
			throw new IllegalArgumentException("the faucet pays from the first miner's account "
				+ accountText(firstMiner, chainOf(blocks))
				+ ", and this wallet is not that account");
		}
		return transfer(out, holder, blocks, amountOf(AMOUNT_TEXT), "from the faucet");
	}
}
