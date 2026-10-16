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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.Amount;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.Genesis;
import io.github.astrapi69.lethenon.Mining;
import io.github.astrapi69.lethenon.Destination;
import io.github.astrapi69.lethenon.Replay;
import io.github.astrapi69.lethenon.SignatureSuite;
import io.github.astrapi69.lethenon.SignedTransaction;
import io.github.astrapi69.lethenon.Transfers;
import io.github.astrapi69.lethenon.Wallet;
import io.github.astrapi69.lethenon.WalletFile;

/**
 * {@code mine --testnet} on the command line (lethenon#50): the flag chooses the test chain when the
 * genesis block is mined, the genesis block decides from then on, and the flag on a main chain is
 * an error rather than a switch.
 */
class TestnetOnTheCommandLineTest extends AbstractCliTest
{

	@TempDir
	Path directory;

	private Path chain;

	private String password;

	private String wallet;

	private String account;

	@BeforeEach
	void aWallet()
	{
		chain = directory.resolve("chain.lethenon");
		password = aPassword();
		wallet = directory.resolve("holder.wallet").toString();
		assertEquals(0, run(password, "wallet", "create", "--wallet", wallet), err);
		account = matchIn(ACCOUNT, out);
	}

	@Test
	@DisplayName("--testnet mines a test genesis, and later commands stay on the test chain")
	void testnet_startsATestChain_andTheGenesisDecidesFromThenOn() throws Exception
	{
		assertEquals(0, run(password, "mine", "--testnet", "--chain", chain.toString(), "--wallet",
			wallet), err);
		assertTrue(out.contains("chain " + Chain.TEST_IDENTIFIER), out);
		assertEquals(0, run(password, "mine", "--chain", chain.toString(), "--wallet", wallet),
			err);
		assertEquals(0, run(password, "send", "--chain", chain.toString(), "--wallet", wallet,
			"--to", account, "--amount", "1"), err);
		assertEquals(0, run(password, "mine", "--chain", chain.toString(), "--wallet", wallet),
			err);

		List<BlockBody> blocks = new ChainFile(chain).read();
		assertEquals(List.of(Chain.TEST_IDENTIFIER, Chain.TEST_IDENTIFIER, Chain.TEST_IDENTIFIER),
			blocks.stream().map(BlockBody::chainIdentifier).toList());
		assertEquals(Chain.TEST_IDENTIFIER,
			blocks.getLast().transactions().getFirst().body().chainIdentifier());
		assertEquals(1L, Replay.verify(blocks).transactions());
	}

	@Test
	@DisplayName("without --testnet a new chain is refused: this build starts no main chain")
	void withoutTheFlag_aNewChainIsRefused_untilTheMainChainHasItsAnchor()
	{
		int exit = run(password, "mine", "--chain", chain.toString(), "--wallet", wallet);

		assertEquals(1, exit, out);
		assertTrue(err.contains("'" + Chain.IDENTIFIER + "' starts only from the genesis block "
			+ "fixed in the code"), err);
		assertTrue(err.contains("--testnet"), err);
		assertFalse(Files.exists(chain), "nothing is written");
	}

	@Test
	@DisplayName("a main chain started without the anchor is refused by balance and by mine, and the file stays as it was (#161)")
	void aMainChainWithoutTheAnchor_isRefused() throws Exception
	{
		// mined through the library rather than by this build's mine, which starts no main chain
		new ChainFile(chain).write(List.of(Genesis.candidate(Chain.IDENTIFIER, "mined elsewhere",
			System.currentTimeMillis())));
		byte[] before = Files.readAllBytes(chain);

		assertEquals(1, run(password, "balance", "--chain", chain.toString(), "--wallet", wallet),
			out);
		assertTrue(err.contains(Genesis.NO_MAIN_CHAIN_WITHOUT_ITS_ANCHOR), err);
		assertEquals(1, run(password, "mine", "--chain", chain.toString(), "--wallet", wallet),
			out);
		assertTrue(err.contains(Genesis.NO_MAIN_CHAIN_WITHOUT_ITS_ANCHOR), err);
		assertArrayEquals(before, Files.readAllBytes(chain), "the file stays as it was");
	}

	@Test
	@DisplayName("--testnet on a main chain file is refused with a message, and the file is unchanged")
	void testnet_onAMainChain_isRefused() throws Exception
	{
		// a main chain file mined elsewhere: this build has no anchor for the main chain, so the
		// file is refused for that before --testnet is looked at (#161); with the anchor of 1.0.0 the
		// refusal names the test chain as well
		new ChainFile(chain).write(List.of(Blocks.mine(Mining.nextBlock(Chain.IDENTIFIER, List.of(),
			Genesis.NOBODY, List.of(), "main", System.currentTimeMillis()), 1_000_000L).orElseThrow()));
		byte[] before = Files.readAllBytes(chain);

		int exit = run(password, "mine", "--testnet", "--chain", chain.toString(), "--wallet",
			wallet);

		assertEquals(1, exit, out);
		assertTrue(err.contains("'" + Chain.IDENTIFIER + "'"), err);
		assertTrue(err.contains(Genesis.NO_MAIN_CHAIN_WITHOUT_ITS_ANCHOR), err);
		assertArrayEquals(before, Files.readAllBytes(chain));
	}

	@Test
	@DisplayName("mine on the test chain carries what fits and keeps the rest waiting")
	void mine_carriesWhatFits_andKeepsTheRestWaiting() throws Exception
	{
		assertEquals(0, run(password, "mine", "--testnet", "--chain", chain.toString(), "--wallet",
			wallet), err);
		assertEquals(0, run(password, "mine", "--chain", chain.toString(), "--wallet", wallet),
			err);
		Wallet opened = WalletFile.read(Path.of(wallet), password.toCharArray());
		Bytes postQuantum = opened.spendKey(SignatureSuite.ML_DSA_65);
		assertEquals(0, run(password, "send", "--chain", chain.toString(), "--wallet", wallet,
			"--to", postQuantum.toString(), "--amount", "100"), err);
		assertEquals(0, run(password, "mine", "--chain", chain.toString(), "--wallet", wallet),
			err);
		ChainFile file = new ChainFile(chain);
		List<SignedTransaction> waiting = new ArrayList<>();
		for (int index = 0; index < 60; index++)
		{
			waiting.add(Transfers.prepare(opened, SignatureSuite.ML_DSA_65, file.require(), waiting,
				Destination.direct(Bytes.of(new byte[] { 7 })), Amount.ofLethe(1L), Amount.ZERO,
				""));
		}
		file.writePending(waiting);

		assertEquals(0, run(password, "mine", "--chain", chain.toString(), "--wallet", wallet),
			err);

		int carried = file.require().getLast().transactions().size();
		assertTrue(carried > 0 && carried < 60, carried + " carried");
		assertEquals(waiting.subList(carried, 60), file.readPending());
		assertTrue(out.contains((60 - carried) + " did not fit"), out);
	}
}
