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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.astrapi69.lethenon.Amount;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.PublishedAddress;
import io.github.astrapi69.lethenon.Replay;

/**
 * The acceptance of #37: a user pays a published address and the recipient spends what arrived,
 * both through the command line, with nothing but what one command printed and another took.
 */
class StealthOnTheCommandLineTest extends AbstractCliTest
{

	@TempDir
	Path directory;

	private Path chain;

	private String holderPassword;

	private String holderWallet;

	@BeforeEach
	void aChainWhoseGenesisAllocationGoesToTheHolder()
	{
		chain = directory.resolve("chain.lethenon");
		holderPassword = aPassword();
		holderWallet = directory.resolve("holder.wallet").toString();
		assertEquals(0, run(holderPassword, "wallet", "create", "--wallet", holderWallet), err);
		// block 0 pays the burn account (#148), block 1 the holder
		assertEquals(0, run(holderPassword, "mine", "--testnet", "--chain", chain.toString(),
			"--wallet", holderWallet), err);
		assertEquals(0, run(holderPassword, "mine", "--chain", chain.toString(), "--wallet",
			holderWallet), err);
	}

	@Test
	@DisplayName("a payment to a published address is swept onto the recipient's account")
	void aPaymentToAnAddress_isSweptOntoTheAccount() throws Exception
	{
		String payeePassword = aPassword();
		String payeeWallet = directory.resolve("payee.wallet").toString();
		assertEquals(0, run(payeePassword, "wallet", "create", "--wallet", payeeWallet), err);
		String address = matchIn(ADDRESS, out);
		String payeeAccount = matchIn(ACCOUNT, out);

		assertEquals(0, run(holderPassword, "send", "--chain", chain.toString(), "--wallet",
			holderWallet, "--to-address", address, "--amount", "20", "--memo",
			"to an address that appears once"), err);
		assertTrue(out.contains("a one-time destination of"),
			"the sender's own terminal does not write down which destination it derived: " + out);
		assertEquals(0, run(holderPassword, "mine", "--chain", chain.toString(), "--wallet",
			holderWallet), err);

		assertEquals(0, run(payeePassword, "balance", "--chain", chain.toString(), "--wallet",
			payeeWallet), err);
		assertTrue(out.contains("recognised 1"), out);

		assertEquals(0, run(payeePassword, "sweep", "--chain", chain.toString(), "--wallet",
			payeeWallet), err);
		assertTrue(out.contains("signed 1 transfer sweeping 20.00000000 LETH"), out);
		assertEquals(0, run(holderPassword, "mine", "--chain", chain.toString(), "--wallet",
			holderWallet), err);

		Replay replay = Replay.verify(new ChainFile(chain).read());

		assertEquals(Amount.ofLeth(20L), replay.finalState().balanceOf(Bytes.ofHex(payeeAccount)),
			"the money reached the account the recipient can spend from: " + replay.describe());
		assertEquals(2L, replay.transactions(), "two transfers: the payment to the one-time "
			+ "destination, and the sweep out of it - " + replay.describe());
	}

	@Test
	@DisplayName("what the wallet prints as its address is what a transfer takes")
	void theAddressPrinted_isTheAddressTaken()
	{
		String password = aPassword();
		assertEquals(0, run(password, "wallet", "create", "--wallet",
			directory.resolve("printed.wallet").toString()), err);

		String address = matchIn(ADDRESS, out);
		String account = matchIn(ACCOUNT, out);

		PublishedAddress parsed = PublishedAddress.parse(address);

		assertEquals(account, parsed.spendKey().toString(),
			"the address carries the account's own signing key as its spend half");
		assertNotEquals(parsed.spendKey(), parsed.viewKey());
	}

	@Test
	@DisplayName("a transfer that names both kinds of recipient, or neither, is refused")
	void aTransferNamingBothOrNeither_isRefused()
	{
		assertEquals(1, run(holderPassword, "send", "--chain", chain.toString(), "--wallet",
			holderWallet, "--amount", "1"), out);
		assertTrue(err.contains("name the recipient once"), err);

		assertEquals(1, run(holderPassword, "send", "--chain", chain.toString(), "--wallet",
			holderWallet, "--amount", "1", "--to", "aabb", "--to-address", "aabb:ccdd"), out);
		assertTrue(err.contains("name the recipient once"), err);
	}

	@Test
	@DisplayName("a sweep with nothing to sweep says so and signs nothing")
	void aSweepWithNothingToSweep_saysSo()
	{
		String password = aPassword();
		String wallet = directory.resolve("empty.wallet").toString();
		assertEquals(0, run(password, "wallet", "create", "--wallet", wallet), err);

		assertEquals(0,
			run(password, "sweep", "--chain", chain.toString(), "--wallet", wallet), err);

		assertTrue(out.contains("nothing to sweep"), out);
	}
}
