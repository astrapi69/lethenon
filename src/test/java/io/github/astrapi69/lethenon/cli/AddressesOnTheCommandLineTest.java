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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.astrapi69.lethenon.AddressText;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.PublishedAddress;

/**
 * What the command line prints as an address and takes as one, in the written form of ADR 0007
 * (#309): with its network and a checksum, the old forms still taken on the test chain, and a
 * mistyped address refused before anything is signed.
 */
class AddressesOnTheCommandLineTest extends AbstractCliTest
{

	@TempDir
	Path directory;

	private Path chain;

	private String holderPassword;

	private String holderWallet;

	@BeforeEach
	void aTestChainWhoseBlock1PaysTheHolder()
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
	@DisplayName("a new wallet prints its account and its address for both chains, the same keys in each")
	void aNewWallet_printsBothChains()
	{
		assertEquals(0, run(aPassword(), "wallet", "create", "--wallet",
			directory.resolve("new.wallet").toString()), err);

		String testAccount = matchIn(ACCOUNT, out);
		String mainAccount = matchIn(
			Pattern.compile("account \\(ed25519\\), main chain: (leth1:[0-9a-f]+:[0-9a-f]{8})"), out);
		String testAddress = matchIn(ADDRESS, out);
		String mainAddress = matchIn(Pattern.compile(
			"address \\(publish this\\), main chain: (leth1:[0-9a-f]+:[0-9a-f]+:[0-9a-f]{8})"), out);

		assertEquals(AddressText.account(testAccount, Chain.TEST_IDENTIFIER),
			AddressText.account(mainAccount, Chain.IDENTIFIER));
		PublishedAddress published = AddressText.published(testAddress, Chain.TEST_IDENTIFIER);
		assertEquals(published, AddressText.published(mainAddress, Chain.IDENTIFIER));
		assertEquals(keyOf(testAccount), published.spendKey());
	}

	@Test
	@DisplayName("a bare account key without a checksum is still taken on the test chain, and the command says so")
	void aBareAccountKey_isTakenOnTheTestChain_withANotice() throws Exception
	{
		String payee = createdAccount("payee.wallet");
		String bareKey = keyOf(payee).toString();

		assertEquals(0, run(holderPassword, "send", "--chain", chain.toString(), "--wallet",
			holderWallet, "--to", bareKey, "--amount", "1"), err);

		assertTrue(err.contains("this address has no checksum, so a typing mistake in it would "
			+ "not have been noticed; ask for its tleth1: form"), err);
		assertTrue(out.contains("signed a transfer of 1.00000000 LETH to " + payee), out);
	}

	@Test
	@DisplayName("a mistyped address is refused with the checksum's message, and nothing waits")
	void aMistypedAddress_isRefused_andNothingIsSigned() throws Exception
	{
		String payee = createdAccount("mistyped.wallet");
		String mistyped = payee.substring(0, 10) + (payee.charAt(10) == '0' ? '1' : '0')
			+ payee.substring(11);

		int exit = run(holderPassword, "send", "--chain", chain.toString(), "--wallet",
			holderWallet, "--to", mistyped, "--amount", "1");

		assertEquals(1, exit, out);
		assertTrue(err.contains("this address is mistyped or incomplete: its checksum does not "
			+ "match"), err);
		assertFalse(Files.exists(Path.of(chain + ".pending"))
			&& !Files.readString(Path.of(chain + ".pending")).isBlank(),
			"nothing was signed for a mistyped address");
	}

	@Test
	@DisplayName("balance prints the account and the address in the form of the chain it replayed")
	void balance_printsTheChainsForm()
	{
		assertEquals(0, run(holderPassword, "balance", "--chain", chain.toString(), "--wallet",
			holderWallet), err);

		assertTrue(matchIn(ACCOUNT, out).startsWith("tleth1:"), out);
		assertTrue(matchIn(ADDRESS, out).startsWith("tleth1:"), out);
	}

	private String createdAccount(final String file)
	{
		assertEquals(0, run(aPassword(), "wallet", "create", "--wallet",
			directory.resolve(file).toString()), err);
		return matchIn(ACCOUNT, out);
	}
}
