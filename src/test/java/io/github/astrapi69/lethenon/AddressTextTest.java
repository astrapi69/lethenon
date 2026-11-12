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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The written form of an address, ADR 0007 (#309): a network, a version, the keys as hex and a
 * checksum, read in the order the record fixes - form, checksum, network, keys.
 * <p>
 * The sample keys come from a wallet of fixed entropy, so every run checks the same text.
 */
class AddressTextTest
{

	private static final Wallet SAMPLE = Wallet.ofEntropy(new byte[Wallet.ENTROPY_LENGTH]);

	private static final PublishedAddress ADDRESS = SAMPLE.address();

	private static final Bytes ACCOUNT = SAMPLE.spendKey(SignatureSuite.ED25519);

	private static final String HEX_DIGITS = "0123456789abcdef";

	@ParameterizedTest(name = "a published address on {0} comes back as itself")
	@ValueSource(strings = { Chain.IDENTIFIER, Chain.TEST_IDENTIFIER })
	@DisplayName("a published address survives being written and read on both chains")
	void aPublishedAddress_survivesItsWrittenForm(final String chain)
	{
		assertEquals(ADDRESS, AddressText.published(AddressText.of(ADDRESS, chain), chain));
	}

	@ParameterizedTest(name = "an account of {0} on both chains comes back as itself")
	@ValueSource(strings = { "ed25519", "ml-dsa-65" })
	@DisplayName("the address of an account survives being written and read on both chains")
	void anAccount_survivesItsWrittenForm(final String suite)
	{
		Bytes account = SAMPLE.spendKey(SignatureSuite.withIdentifier(suite));
		for (String chain : List.of(Chain.IDENTIFIER, Chain.TEST_IDENTIFIER))
		{
			assertEquals(account, AddressText.account(AddressText.ofAccount(account, chain), chain));
		}
	}

	@Test
	@DisplayName("the written form is the network, the version, the keys and 8 hex of SHA-256")
	void theWrittenForm_isWhatTheRecordSays() throws NoSuchAlgorithmException
	{
		String main = AddressText.of(ADDRESS, Chain.IDENTIFIER);
		String test = AddressText.of(ADDRESS, Chain.TEST_IDENTIFIER);
		String account = AddressText.ofAccount(ACCOUNT, Chain.IDENTIFIER);

		assertEquals("leth1:" + ADDRESS.viewKey() + ":" + ADDRESS.spendKey() + ":"
			+ checksumOf("leth1:" + ADDRESS.viewKey() + ":" + ADDRESS.spendKey()), main);
		assertEquals("tleth1:" + ADDRESS.viewKey() + ":" + ADDRESS.spendKey() + ":"
			+ checksumOf("tleth1:" + ADDRESS.viewKey() + ":" + ADDRESS.spendKey()), test);
		assertEquals("leth1:" + ACCOUNT + ":" + checksumOf("leth1:" + ACCOUNT), account);
		assertEquals(192, main.length(), main);
		assertEquals(103, account.length(), account);
	}

	@Test
	@DisplayName("every single substituted character is refused, every position against every other hex digit")
	void everySubstitution_isRefused()
	{
		String text = AddressText.of(ADDRESS, Chain.TEST_IDENTIFIER);
		List<String> accepted = new ArrayList<>();
		int tried = 0;
		for (int position = 0; position < text.length(); position++)
		{
			for (char digit : HEX_DIGITS.toCharArray())
			{
				if (digit == text.charAt(position))
				{
					continue;
				}
				String changed = text.substring(0, position) + digit + text.substring(position + 1);
				tried++;
				if (reads(changed))
				{
					accepted.add(changed);
				}
			}
		}
		assertTrue(tried > 2800, "the loop covers the address, " + tried + " substitutions");
		assertTrue(accepted.isEmpty(), accepted.size() + " of " + tried + " read: " + accepted);
	}

	@Test
	@DisplayName("every truncation is refused, from either end")
	void everyTruncation_isRefused()
	{
		String text = AddressText.of(ADDRESS, Chain.TEST_IDENTIFIER);
		List<String> accepted = new ArrayList<>();
		for (int cut = 1; cut < text.length(); cut++)
		{
			for (String truncated : List.of(text.substring(0, text.length() - cut),
				text.substring(cut)))
			{
				if (reads(truncated))
				{
					accepted.add(truncated);
				}
			}
		}
		assertTrue(accepted.isEmpty(), accepted.toString());
	}

	@Test
	@DisplayName("an unknown version is refused with its own message, before the checksum")
	void anUnknownVersion_isRefusedBeforeTheChecksum()
	{
		String body = "leth2:" + ADDRESS.viewKey() + ":" + ADDRESS.spendKey();

		for (String text : List.of(body + ":" + checksumOf(body), body + ":00000000"))
		{
			IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> AddressText.published(text, Chain.IDENTIFIER));
			assertEquals("version 2 of the address format is not known here; this build reads "
				+ "version 1", refused.getMessage());
		}
	}

	@Test
	@DisplayName("a tleth1 address without its t reports the checksum, not the network")
	void aTestAddressWithoutItsT_reportsTheChecksum()
	{
		String withoutT = AddressText.of(ADDRESS, Chain.TEST_IDENTIFIER).substring(1);

		for (String chain : List.of(Chain.IDENTIFIER, Chain.TEST_IDENTIFIER))
		{
			IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> AddressText.published(withoutT, chain));
			assertEquals("this address is mistyped or incomplete: its checksum does not match",
				refused.getMessage());
		}
	}

	@Test
	@DisplayName("an address of the other chain is refused, naming both")
	void anAddressOfTheOtherChain_isRefused()
	{
		IllegalArgumentException mainOnTest = assertThrows(IllegalArgumentException.class,
			() -> AddressText.published(AddressText.of(ADDRESS, Chain.IDENTIFIER),
				Chain.TEST_IDENTIFIER));
		IllegalArgumentException testOnMain = assertThrows(IllegalArgumentException.class,
			() -> AddressText.account(AddressText.ofAccount(ACCOUNT, Chain.TEST_IDENTIFIER),
				Chain.IDENTIFIER));

		assertEquals("this is an address of the main chain (leth1), and it is read for the test "
			+ "chain (tleth1)", mainOnTest.getMessage());
		assertEquals("this is an address of the test chain (tleth1), and it is read for the main "
			+ "chain (leth1)", testOnMain.getMessage());
	}

	@Test
	@DisplayName("a published address and the address of an account are not taken for each other")
	void theTwoKinds_areNotTakenForEachOther()
	{
		IllegalArgumentException accountAsPublished = assertThrows(
			IllegalArgumentException.class, () -> AddressText
				.published(AddressText.ofAccount(ACCOUNT, Chain.IDENTIFIER), Chain.IDENTIFIER));
		IllegalArgumentException publishedAsAccount = assertThrows(
			IllegalArgumentException.class, () -> AddressText
				.account(AddressText.of(ADDRESS, Chain.IDENTIFIER), Chain.IDENTIFIER));

		assertEquals("this is the address of an account (3 parts), not a published address "
			+ "(4 parts)", accountAsPublished.getMessage());
		assertEquals("this is a published address (4 parts), not the address of an account "
			+ "(3 parts)", publishedAsAccount.getMessage());
	}

	@Test
	@DisplayName("each step of the form is refused with its own message")
	void theForm_isCheckedPartByPart()
	{
		String view = ADDRESS.viewKey().toString();
		String spend = ADDRESS.spendKey().toString();

		assertRefused("an address was expected, and the text is empty", "");
		assertRefused("an address starts with leth1: or tleth1:, and this one starts with 'lath1'",
			"lath1:" + view + ":" + spend + ":00000000");
		assertRefused("an address has 3 parts (an account) or 4 (a published address), separated "
			+ "by ':', and this one has 5", "leth1:" + view + ":" + spend + ":00:00000000");
		assertRefused("part 2 is no key in hexadecimal: it needs an even number of the characters "
			+ "0-9 and a-f", "leth1:" + view.substring(1) + ":" + spend + ":00000000");
		assertRefused("part 3 is no key in hexadecimal: it needs an even number of the characters "
			+ "0-9 and a-f", "leth1:" + view + ":" + spend.replace('a', 'g') + ":00000000");
		assertRefused("the last part is the checksum, 8 hexadecimal characters, and this one is "
			+ "'0000000'", "leth1:" + view + ":" + spend + ":0000000");
	}

	@Test
	@DisplayName("keys that are not the keys their parts name are refused after the network")
	void keysThatAreNoKeys_areRefused()
	{
		String swapped = withChecksum("leth1:" + ADDRESS.spendKey() + ":" + ADDRESS.viewKey());
		String noKey = withChecksum("leth1:0102030405");

		IllegalArgumentException wrongKeys = assertThrows(IllegalArgumentException.class,
			() -> AddressText.published(swapped, Chain.IDENTIFIER));
		IllegalArgumentException noAccount = assertThrows(IllegalArgumentException.class,
			() -> AddressText.account(noKey, Chain.IDENTIFIER));

		assertEquals("part 2 is no X25519 public key", wrongKeys.getMessage());
		assertEquals("part 2 is no public key of a signature suite (ed25519, ml-dsa-65)",
			noAccount.getMessage());
	}

	@Test
	@DisplayName("the old forms are read as a recipient on the test chain and refused on the main chain")
	void theOldForms_areReadOnTheTestChainOnly()
	{
		String oldAddress = ADDRESS.toText();
		String oldAccount = ACCOUNT.toString();

		assertEquals(ADDRESS, AddressText.published(oldAddress, Chain.TEST_IDENTIFIER));
		assertEquals(ACCOUNT, AddressText.account(oldAccount, Chain.TEST_IDENTIFIER));
		assertTrue(AddressText.hasNoChecksum(oldAddress));
		assertTrue(AddressText.hasNoChecksum(oldAccount));
		assertFalse(AddressText.hasNoChecksum(AddressText.of(ADDRESS, Chain.TEST_IDENTIFIER)));
		IllegalArgumentException address = assertThrows(IllegalArgumentException.class,
			() -> AddressText.published(oldAddress, Chain.IDENTIFIER));
		IllegalArgumentException account = assertThrows(IllegalArgumentException.class,
			() -> AddressText.account(oldAccount, Chain.IDENTIFIER));
		for (IllegalArgumentException refused : List.of(address, account))
		{
			assertEquals("an address without a checksum is not taken on the main chain: it needs "
				+ "its leth1: form", refused.getMessage());
		}
	}

	@Test
	@DisplayName("a recorded address is read in either form and for either chain")
	void aRecordedAddress_isReadInEitherForm()
	{
		for (String text : List.of(ADDRESS.toText(), AddressText.of(ADDRESS, Chain.IDENTIFIER),
			AddressText.of(ADDRESS, Chain.TEST_IDENTIFIER)))
		{
			assertEquals(ADDRESS, AddressText.recorded(text), text);
			assertEquals(ADDRESS, PublishedAddress.parse(text), text);
		}
		String written = AddressText.of(ADDRESS, Chain.IDENTIFIER);
		char last = written.charAt(written.length() - 1);
		String lastDigitChanged = written.substring(0, written.length() - 1)
			+ (last == '0' ? '1' : '0');
		IllegalArgumentException mistyped = assertThrows(IllegalArgumentException.class,
			() -> AddressText.recorded(lastDigitChanged));
		assertEquals("this address is mistyped or incomplete: its checksum does not match",
			mistyped.getMessage());
	}

	@Test
	@DisplayName("a chain this build does not know has no address form")
	void anUnknownChain_hasNoAddressForm()
	{
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> AddressText.of(ADDRESS, "lethenon-1"));

		assertEquals("no address form for the chain 'lethenon-1': this build writes addresses of "
			+ "lethenon-2 and lethenon-test-2", refused.getMessage());
	}

	@Test
	@DisplayName("every space of the list is dropped at the start, inside and at the end, for both kinds")
	void everySpaceOfTheList_isDroppedAnywhere()
	{
		List<String> refused = new ArrayList<>();
		for (int space : TypedText.SPACES)
		{
			String character = Character.toString(space);
			for (String written : List.of(AddressText.of(ADDRESS, Chain.TEST_IDENTIFIER),
				AddressText.ofAccount(ACCOUNT, Chain.TEST_IDENTIFIER)))
			{
				for (String text : List.of(character + written,
					written.substring(0, 20) + character + written.substring(20),
					written + character))
				{
					if (!readsAs(written, text))
					{
						refused.add(String.format("U+%04X in %s", space, text));
					}
				}
			}
		}
		assertTrue(refused.isEmpty(), refused.toString());
	}

	@Test
	@DisplayName("upper case, groups of four, tabs, CRLF and spaces around read as the lower case form, with its checksum")
	void theUsualWaysOfWritingItDown_readAsTheLowerCaseForm()
	{
		for (String written : List.of(AddressText.of(ADDRESS, Chain.TEST_IDENTIFIER),
			AddressText.ofAccount(ACCOUNT, Chain.TEST_IDENTIFIER),
			AddressText.of(ADDRESS, Chain.IDENTIFIER),
			AddressText.ofAccount(ACCOUNT, Chain.IDENTIFIER)))
		{
			StringBuilder grouped = new StringBuilder();
			for (int start = 0; start < written.length(); start += 4)
			{
				grouped.append(written, start, Math.min(start + 4, written.length())).append(' ');
			}
			for (String text : List.of(written.toUpperCase(Locale.ROOT),
				grouped.toString(), written.substring(0, 30) + "\t" + written.substring(30),
				written.substring(0, 50) + "\r\n" + written.substring(50),
				"  \t" + written + "\r\n", "\n" + grouped.toString().toUpperCase(Locale.ROOT)))
			{
				assertTrue(readsAs(written, text), text);
			}
		}
	}

	@ParameterizedTest(name = "code point {0} (decimal) is refused with its position")
	@ValueSource(ints = { 0x200B, 0xFEFF, 0x202E })
	@DisplayName("an invisible character or a direction control is refused, naming its position and code point")
	void anInvisibleCharacter_isRefusedWithItsPositionAndCodePoint(final int codePoint)
	{
		String written = AddressText.of(ADDRESS, Chain.TEST_IDENTIFIER);
		for (int index : List.of(0, 17, written.length()))
		{
			String text = written.substring(0, index) + Character.toString(codePoint)
				+ written.substring(index);

			IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> AddressText.published(text, Chain.TEST_IDENTIFIER));

			assertEquals(String.format("position %d holds U+%04X, and an address holds no such "
				+ "character", index + 1, codePoint), refused.getMessage());
		}
	}

	@Test
	@DisplayName("a character outside the plane of most text is counted as one position")
	void aCharacterOutsideTheBasicPlane_isOnePosition()
	{
		String text = "\uD83D\uDE00" + "\u200B" + AddressText.of(ADDRESS, Chain.TEST_IDENTIFIER);

		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> AddressText.published(text, Chain.TEST_IDENTIFIER));

		assertEquals("position 1 holds U+1F600, and an address holds no such character",
			refused.getMessage());
	}

	@Test
	@DisplayName("text that is only spaces is no address")
	void onlySpaces_isNoAddress()
	{
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> AddressText.account(" \t\r\n\u00A0", Chain.TEST_IDENTIFIER));

		assertEquals("an address was expected, and the text is empty", refused.getMessage());
	}

	@Test
	@DisplayName("a recorded address and PublishedAddress.parse normalise as the recipient readers do")
	void theRecordedReader_normalisesTheSameWay()
	{
		String grouped = " " + AddressText.of(ADDRESS, Chain.IDENTIFIER).toUpperCase(Locale.ROOT)
			.replace(":", " : ") + "\n";

		assertEquals(ADDRESS, AddressText.recorded(grouped));
		assertEquals(ADDRESS, PublishedAddress.parse(grouped));
	}

	/**
	 * Whether a text reads as the given written form: the same keys, and written again the same
	 * text, so the checksum it was checked against is that of the lower case form
	 */
	private static boolean readsAs(final String written, final String text)
	{
		String chain = written.startsWith("t") ? Chain.TEST_IDENTIFIER : Chain.IDENTIFIER;
		try
		{
			if (written.split(":").length == 4)
			{
				return AddressText.of(AddressText.published(text, chain), chain).equals(written);
			}
			return AddressText.ofAccount(AddressText.account(text, chain), chain).equals(written);
		}
		catch (IllegalArgumentException refused)
		{
			return false;
		}
	}

	private static void assertRefused(final String message, final String text)
	{
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> AddressText.published(text, Chain.IDENTIFIER), text);
		assertEquals(message, refused.getMessage(), text);
	}

	private static boolean reads(final String text)
	{
		try
		{
			AddressText.published(text, Chain.TEST_IDENTIFIER);
			return true;
		}
		catch (IllegalArgumentException refused)
		{
			return false;
		}
	}

	private static String withChecksum(final String body)
	{
		return body + ":" + checksumOf(body);
	}

	private static String checksumOf(final String body)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(body.getBytes(StandardCharsets.US_ASCII));
			return HexFormat.of().formatHex(digest, 0, 4);
		}
		catch (NoSuchAlgorithmException never)
		{
			throw new IllegalStateException(never);
		}
	}
}
