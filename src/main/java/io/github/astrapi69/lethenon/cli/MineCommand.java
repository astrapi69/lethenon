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

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.ConsensusRules;
import io.github.astrapi69.lethenon.DifficultyRule;
import io.github.astrapi69.lethenon.Genesis;
import io.github.astrapi69.lethenon.Mining;
import io.github.astrapi69.lethenon.Replay;
import io.github.astrapi69.lethenon.SignatureSuite;
import io.github.astrapi69.lethenon.SignedTransaction;
import io.github.astrapi69.lethenon.Wallet;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Mines the next block - the genesis block when there is no chain yet - with every transfer that
 * is waiting, paying the wallet that mined it.
 * <p>
 * The new chain is replayed before it is written. A waiting transfer that does not hold refuses
 * the whole block, and the chain file stays as it was.
 * <p>
 * How hard the block is, and how late its timestamp has to be, is not a choice: both come from
 * {@link DifficultyRule} (lethenon#24), which the replay checks.
 */
@Command(name = "mine", description = "Mine the next block with every waiting transfer, paying "
	+ "this wallet. With no chain yet, start it: with the genesis block fixed in the code where "
	+ "there is one, otherwise with a newly mined one; a genesis block pays its reward to the burn "
	+ "account.")
class MineCommand extends ChainCommand
{

	@Option(names = "--pun", defaultValue = "watching is not protecting",
		description = "the words mining varies; default: ${DEFAULT-VALUE}")
	String pun;

	@Option(names = "--testnet", description = "mine the genesis block of a test chain, "
		+ Chain.TEST_IDENTIFIER + "; on an existing chain its genesis block decides, and the flag on a main "
		+ "chain is an error")
	boolean testnet;

	@Option(names = "--attempts", defaultValue = "10000000",
		description = "how many puns to try before giving up; default: ${DEFAULT-VALUE}")
	long attempts;

	/**
	 * Creates the command; picocli instantiates it reflectively
	 */
	MineCommand()
	{
	}

	@Override
	int run(final PrintStream out) throws IOException
	{
		Wallet miner = openWallet();
		Bytes beneficiary = miner.spendKey(SignatureSuite.ED25519);
		List<BlockBody> blocks = readChain();
		List<SignedTransaction> waiting = blocks.isEmpty() ? List.of() : readPending();
		if (!blocks.isEmpty())
		{
			Replay.verify(blocks);
		}
		long now = System.currentTimeMillis();
		BlockBody mined = blocks.isEmpty()
			? Genesis.start(testnet ? Chain.TEST_IDENTIFIER : Chain.IDENTIFIER, pun, now)
			: Blocks.mine(testnet
				? Mining.nextBlock(Chain.TEST_IDENTIFIER, blocks, beneficiary, waiting, pun, now)
				: Mining.nextBlock(blocks, beneficiary, waiting, pun, now), attempts)
				.orElseThrow(() -> new IllegalStateException("no pun of " + attempts
					+ " attempts reached difficulty " + DifficultyRule.requiredFor(blocks)
					+ "; try more attempts"));
		List<BlockBody> extended = new ArrayList<>(blocks);
		extended.add(mined);
		Replay replay = Replay.verify(extended);
		List<SignedTransaction> left = waiting.subList(mined.transactions().size(), waiting.size());
		writeChain(extended, left);
		if (ConsensusRules.LETHENON.anchorFor(mined.chainIdentifier()).map(Blocks::hashOf)
			.filter(Blocks.hashOf(mined)::equals).isPresent())
		{
			out.println("started the chain with its genesis block fixed in the code, "
				+ Blocks.hashOf(mined) + ", which pays the burn account; the next mine mines "
				+ "block 1");
		}
		else if (mined.height() == 0L)
		{
			out.println("mined block 0: \"" + mined.pun() + "\", paying its reward to the burn "
				+ "account, which nobody can spend (#148); the next mine mines block 1 for this "
				+ "wallet");
		}
		else
		{
			out.println("mined block " + mined.height() + " with " + mined.transactions().size()
				+ " transfer(s), paying " + hex(beneficiary) + ": \"" + mined.pun() + "\""
				+ (left.isEmpty() ? "" : "; " + left.size() + " did not fit and wait for the next"));
		}
		out.println("chain " + mined.chainIdentifier());
		out.println(replay.describe());
		return 0;
	}
}
