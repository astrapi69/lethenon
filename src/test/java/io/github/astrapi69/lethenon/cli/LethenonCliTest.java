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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.Genesis;
import io.github.astrapi69.lethenon.DifficultyRule;
import io.github.astrapi69.lethenon.Replay;

/**
 * The command line of milestone 4 (lethenon#2): {@code wallet}, {@code mine}, {@code faucet},
 * {@code send} and {@code balance}, driven the way a shell drives them, against real files.
 * <p>
 * Exit codes are the contract: 0 done, 1 refused for a reason the message names (a chain that
 * does not verify, a wrong password, a transfer the account cannot cover), 2 a command line that
 * was not understood.
 */
class LethenonCliTest extends AbstractCliTest
{

	@Test
	@DisplayName("--version prints the version of the build, not a fixed string (#193)")
	void version_isTheBuildsVersion()
	{
		String expected = System.getProperty("lethenon.projectVersion");
		assertTrue(expected != null && !expected.isBlank(),
			"the build passes the project version to the tests");

		assertEquals(0, run("", "--version"), err);

		assertEquals("lethenon " + expected, out.strip());
	}


	private static final Pattern PHRASE = Pattern.compile("^((?:[a-z]+ ){23}[a-z]+)$",
		Pattern.MULTILINE);

	@TempDir
	Path directory;

	private Path chain;

	private String holderPassword;

	private String holderWallet;

	private String holderAccount;

	@BeforeEach
	void aChainWhoseGenesisAllocationGoesToTheHolder()
	{
		chain = directory.resolve("chain.lethenon");
		holderPassword = aPassword();
		holderWallet = directory.resolve("holder.wallet").toString();
		holderAccount = createWallet(holderWallet, holderPassword);
		// block 0 pays the burn account (#148), block 1 the holder, who then runs the faucet
		assertEquals(0, run(holderPassword, "mine", "--testnet", "--chain", chain.toString(),
			"--wallet", holderWallet), err);
		assertEquals(0, run(holderPassword, "mine", "--chain", chain.toString(), "--wallet",
			holderWallet), err);
	}

	@Test
	void theAcceptanceOfMilestone4_aWalletRestoredFromItsPhraseAlone_sendsWhatTheReplayAccepts()
		throws Exception
	{
		String password = aPassword();
		String wallet = directory.resolve("alice.wallet").toString();
		assertEquals(0, run(password, "wallet", "create", "--wallet", wallet), err);
		String alice = matchIn(ACCOUNT, out);
		String phrase = phraseIn(out);
		assertEquals(0, run(holderPassword, "faucet", "--chain", chain.toString(), "--wallet",
			holderWallet, "--to", alice), err);
		assertEquals(0, run(holderPassword, "mine", "--chain", chain.toString(), "--wallet",
			holderWallet), err);
		Files.delete(Path.of(wallet));

		String restoredPassword = aPassword();
		String restored = directory.resolve("alice-restored.wallet").toString();
		assertEquals(0, run(phrase + "\n" + restoredPassword, "wallet", "restore", "--wallet",
			restored), err);
		assertEquals(alice, matchIn(ACCOUNT, out), "the phrase brings back the same account");
		assertEquals(0, run(restoredPassword, "send", "--chain", chain.toString(), "--wallet",
			restored, "--to", holderAccount, "--amount", "12.5", "--memo", "back from the words"),
			err);
		assertEquals(0, run(holderPassword, "mine", "--chain", chain.toString(), "--wallet",
			holderWallet), err);

		Replay replay = Replay.verify(new ChainFile(chain).read());
		assertEquals(4L, replay.blocks(), replay.describe());
		assertEquals(2L, replay.transactions(), replay.describe());
		assertEquals(0, run(restoredPassword, "balance", "--chain", chain.toString(), "--wallet",
			restored), err);
		assertTrue(out.contains("account (ed25519): " + alice + " holds 987.50000000 LETH"), out);
	}

	@Test
	void mine_makesTheGenesisBlock_andThenEachBlockPaysTheWalletThatMinedIt() throws Exception
	{
		String minerPassword = aPassword();
		String minerWallet = directory.resolve("miner.wallet").toString();
		String minerAccount = createWallet(minerWallet, minerPassword);

		assertEquals(0, run(minerPassword, "mine", "--chain", chain.toString(), "--wallet",
			minerWallet), err);

		List<BlockBody> blocks = new ChainFile(chain).read();
		assertEquals(List.of(Genesis.NOBODY.toString(), holderAccount, minerAccount),
			blocks.stream().map(block -> block.beneficiary().toString()).toList());
		assertTrue(out.contains("mined block 2"), out);
	}

	@Test
	void mine_asksTheRuleHowHard_andAfterThirtyFastBlocksMinesTwoBitsHarder() throws Exception
	{
		List<BlockBody> fast = new ArrayList<>(new ChainFile(chain).read());
		while (fast.size() < DifficultyRule.INTERVAL)
		{
			BlockBody last = fast.getLast();
			fast.add(Blocks.mine(new BlockBody(Chain.TEST_IDENTIFIER, last.height() + 1L,
				Blocks.hashOf(last), last.beneficiary(), List.of(), last.timestamp() + 1_000L,
				DifficultyRule.requiredFor(fast), "fast"), 1_000_000L).orElseThrow());
		}
		new ChainFile(chain).write(fast);

		assertEquals(0, run(holderPassword, "mine", "--chain", chain.toString(), "--wallet",
			holderWallet), err);

		List<BlockBody> blocks = new ChainFile(chain).read();
		assertEquals(DifficultyRule.MINIMUM + 2, blocks.getLast().difficulty());
		assertEquals(31L, Replay.verify(blocks).blocks());
	}

	@Test
	void balance_isComputedFromTheChainFile_andSaysWhatItReplayed()
	{
		assertEquals(0, run(holderPassword, "balance", "--chain", chain.toString(), "--wallet",
			holderWallet), err);

		assertTrue(out.contains(
			"account (ed25519): " + holderAccount + " holds 1983.99801600 LETH"), out);
		assertTrue(out.contains("replayed 2 blocks"), out);
	}

	@Test
	void faucet_fromAWalletThatIsNotTheFirstMiner_isRefused_andNothingIsWaiting() throws Exception
	{
		String password = aPassword();
		String wallet = directory.resolve("somebody.wallet").toString();
		String somebody = createWallet(wallet, password);

		int exit = run(password, "faucet", "--chain", chain.toString(), "--wallet", wallet, "--to",
			somebody);

		assertEquals(1, exit, out);
		assertTrue(err.contains("first miner"), err);
		assertEquals(List.of(), new ChainFile(chain).readPending(),
			"a refused faucet leaves no transfer waiting");
	}

	@Test
	void send_ofMoreThanTheAccountHolds_isRefused_beforeAnythingIsSigned() throws Exception
	{
		String password = aPassword();
		String wallet = directory.resolve("poor.wallet").toString();
		createWallet(wallet, password);

		int exit = run(password, "send", "--chain", chain.toString(), "--wallet", wallet, "--to",
			holderAccount, "--amount", "1");

		assertEquals(1, exit, out);
		assertTrue(err.contains("holds 0.00000000"), err);
		assertEquals(List.of(), new ChainFile(chain).readPending());
	}

	@Test
	void aWrongPassword_isRefusedWithExitCode1() throws Exception
	{
		byte[] before = Files.readAllBytes(chain);

		int exit = run(aPassword(), "mine", "--chain", chain.toString(), "--wallet", holderWallet);

		assertEquals(1, exit, out);
		assertTrue(err.contains("password is wrong"), err);
		assertArrayEquals(before, Files.readAllBytes(chain));
	}

	@Test
	void aChainFileThatWasTamperedWith_hasNoBalance_andTheReasonIsPrinted() throws Exception
	{
		assertEquals(0, run(holderPassword, "send", "--chain", chain.toString(), "--wallet",
			holderWallet, "--to", holderAccount, "--amount", "1"), err);
		assertEquals(0, run(holderPassword, "mine", "--chain", chain.toString(), "--wallet",
			holderWallet), err);
		// the stored block ends with the signature of its transfer, which the block's hash does not
		// cover: the database's own checks pass, and the replay is what refuses it
		flipTheLastByte("block", "height", chain);

		int exit = run(holderPassword, "balance", "--chain", chain.toString(), "--wallet",
			holderWallet);

		assertEquals(1, exit, out);
		assertFalse(out.contains("holds"), "no balance from a chain that does not verify: " + out);
		assertTrue(err.contains("signature"), err);
	}

	@Test
	void twoTransfersBeforeTheNextBlock_getConsecutiveNonces_andBothAreMined() throws Exception
	{
		assertEquals(0, run(holderPassword, "send", "--chain", chain.toString(), "--wallet",
			holderWallet, "--to", holderAccount, "--amount", "1", "--memo", "first"), err);
		assertTrue(out.contains("with nonce 0"), out);
		assertEquals(0, run(holderPassword, "send", "--chain", chain.toString(), "--wallet",
			holderWallet, "--to", holderAccount, "--amount", "2", "--memo", "second"), err);
		assertTrue(out.contains("with nonce 1"), out);

		assertEquals(0, run(holderPassword, "mine", "--chain", chain.toString(), "--wallet",
			holderWallet), err);

		Replay replay = Replay.verify(new ChainFile(chain).read());
		assertEquals(2L, replay.transactions(), replay.describe());
		assertEquals(List.of(), new ChainFile(chain).readPending(), "a mined transfer no longer waits");
	}

	@Test
	void aWaitingTransferThatWasTamperedWith_refusesTheBlock_andTheChainStaysAsItWas()
		throws Exception
	{
		assertEquals(0, run(holderPassword, "send", "--chain", chain.toString(), "--wallet",
			holderWallet, "--to", holderAccount, "--amount", "1"), err);
		flipTheLastByte("waiting", "position", chain);
		byte[] before = Files.readAllBytes(chain);

		int exit = run(holderPassword, "mine", "--chain", chain.toString(), "--wallet",
			holderWallet);

		assertEquals(1, exit, out);
		assertTrue(err.contains("signature"), err);
		assertArrayEquals(before, Files.readAllBytes(chain),
			"the chain and what waits beside it, the tampered transfer included, stay as they were");
		assertEquals(1, new ChainFile(chain).readPending().size(), "nothing waiting is lost");
	}

	@Test
	void anUnknownCommand_isAUsageError_withExitCode2()
	{
		assertEquals(2, run("", "print-money"));
	}

	/**
	 * Flips the last byte of the newest row of a table in the chain's database, the way a disk or
	 * a person could
	 */
	private static void flipTheLastByte(final String table, final String key, final Path chain)
		throws java.sql.SQLException
	{
		try (java.sql.Connection connection = java.sql.DriverManager
			.getConnection("jdbc:sqlite:" + chain);
			java.sql.Statement select = connection.createStatement();
			java.sql.ResultSet newest = select.executeQuery("SELECT " + key + ", body FROM " + table
				+ " ORDER BY " + key + " DESC LIMIT 1");
			java.sql.PreparedStatement update = connection
				.prepareStatement("UPDATE " + table + " SET body = ? WHERE " + key + " = ?"))
		{
			newest.next();
			byte[] body = newest.getBytes(2);
			body[body.length - 1] ^= 1;
			update.setBytes(1, body);
			update.setLong(2, newest.getLong(1));
			update.executeUpdate();
		}
	}

	private String createWallet(final String wallet, final String password)
	{
		assertEquals(0, run(password, "wallet", "create", "--wallet", wallet), err);
		return matchIn(ACCOUNT, out);
	}

	private static String phraseIn(final String output)
	{
		Matcher matcher = PHRASE.matcher(output);
		assertTrue(matcher.find(), output);
		return matcher.group(1);
	}

}
