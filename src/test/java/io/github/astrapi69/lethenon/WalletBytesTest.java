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
package io.github.astrapi69.lethenon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wallet as bytes, for a host that keeps the sealed wallet in its own store and never writes it
 * to a file (decision in mystic-crypt-ui, docs/decisions/lethenon-wallet-in-vault-2026-10-30.md).
 * The byte API and the path API seal the same format, so either one opens what the other wrote.
 */
class WalletBytesTest
{

	private final char[] password = Base64.getEncoder().encodeToString(randomBytes(18))
		.toCharArray();

	@TempDir
	Path directory;

	@Test
	void aWalletSealedToBytes_opensAgainWithEveryKey() throws Exception
	{
		Wallet original = Wallet.create();

		byte[] sealed = WalletFile.toBytes(original, password.clone());
		Wallet read = WalletFile.read(sealed, password.clone());

		assertEquals(original.phrase(), read.phrase());
		for (SignatureSuite suite : SignatureSuite.values())
		{
			assertEquals(original.spendKey(suite), read.spendKey(suite), suite.identifier());
		}
	}

	@Test
	void aFileWrittenByThePathApi_opensThroughTheBytesApi() throws Exception
	{
		Wallet original = Wallet.create();
		Path file = directory.resolve("wallet.lethenon");
		WalletFile.write(file, original, password.clone());

		Wallet read = WalletFile.read(Files.readAllBytes(file), password.clone());

		assertEquals(original.phrase(), read.phrase());
	}

	@Test
	void aWrongPassword_onBytes_isRefusedAsAWrongPassword() throws Exception
	{
		byte[] sealed = WalletFile.toBytes(Wallet.create(), password.clone());

		SecurityException refused = assertThrows(SecurityException.class,
			() -> WalletFile.read(sealed, (String.valueOf(password) + "x").toCharArray()));

		assertTrue(refused.getMessage().contains("password"), refused.getMessage());
	}

	@Test
	void theBytesCarryNeitherTheEntropyNorThePhraseInTheClear() throws Exception
	{
		Wallet wallet = Wallet.create();

		byte[] sealed = WalletFile.toBytes(wallet, password.clone());

		assertFalse(HexFormat.of().formatHex(sealed).contains(HexFormat.of().formatHex(wallet.entropy())),
			"the entropy is in the bytes in the clear");
		assertFalse(new String(sealed, StandardCharsets.ISO_8859_1)
			.contains(wallet.phrase().split(" ")[0] + " "), "the phrase is in the bytes");
	}

	private static byte[] randomBytes(int length)
	{
		byte[] bytes = new byte[length];
		new SecureRandom().nextBytes(bytes);
		return bytes;
	}
}
