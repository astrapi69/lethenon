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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Known answers for the seed phrase: every English vector of the BIP-39 reference implementation
 * (src/test/resources/bip39/vectors-english.csv) - entropy to words, words back to entropy, and
 * words with the vectors' passphrase to the 64 byte seed.
 */
class SeedPhraseTest
{

	@ParameterizedTest(name = "{0}")
	@CsvFileSource(resources = "/bip39/vectors-english.csv", delimiter = '|')
	void entropyBecomesTheWordsOfBip39(final String entropy, final String words)
	{
		assertEquals(List.of(words.split(" ")),
			SeedPhrase.fromEntropy(Bytes.ofHex(entropy).toByteArray()));
	}

	@ParameterizedTest(name = "{0}")
	@CsvFileSource(resources = "/bip39/vectors-english.csv", delimiter = '|')
	void theWordsGiveTheirEntropyBack(final String entropy, final String words)
	{
		assertArrayEquals(Bytes.ofHex(entropy).toByteArray(),
			SeedPhrase.toEntropy(SeedPhrase.parse(words)));
	}

	@ParameterizedTest(name = "{0}")
	@CsvFileSource(resources = "/bip39/vectors-english.csv", delimiter = '|')
	void theWordsAndThePassphraseGiveTheSeedOfBip39(final String entropy, final String words,
		final String passphrase, final String seed)
	{
		assertEquals(Bytes.ofHex(seed),
			Bytes.of(SeedPhrase.seed(SeedPhrase.parse(words), passphrase)));
	}

	@Test
	void aPhraseWithAWrongChecksum_isRefused()
	{
		List<String> words = new ArrayList<>(SeedPhrase.fromEntropy(new byte[32]));
		words.set(23, "zoo");

		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> SeedPhrase.toEntropy(words));

		assertTrue(refused.getMessage().contains("checksum"), refused.getMessage());
	}

	@Test
	void aWordThatIsNotInTheList_isRefused_andNamed()
	{
		List<String> words = new ArrayList<>(SeedPhrase.fromEntropy(new byte[16]));
		words.set(4, "lethenon");

		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> SeedPhrase.toEntropy(words));

		assertTrue(refused.getMessage().contains("'lethenon'"), refused.getMessage());
		assertTrue(refused.getMessage().contains("word 5"), refused.getMessage());
	}

	@ParameterizedTest(name = "{0} words are refused")
	@ValueSource(ints = { 0, 11, 13, 25 })
	void aPhraseOfAnyOtherLength_isRefused(final int count)
	{
		List<String> words = new ArrayList<>();
		for (int index = 0; index < count; index++)
		{
			words.add("abandon");
		}

		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> SeedPhrase.toEntropy(words));

		assertTrue(refused.getMessage().contains(count + " words"), refused.getMessage());
	}

	@ParameterizedTest(name = "{0} bytes of entropy are refused")
	@ValueSource(ints = { 0, 12, 15, 17, 36 })
	void entropyOfAnyOtherLength_isRefused(final int length)
	{
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> SeedPhrase.fromEntropy(new byte[length]));

		assertTrue(refused.getMessage().contains(length + " bytes"), refused.getMessage());
	}

	@Test
	void aPhraseIsReadRegardlessOfSpacingAndCase()
	{
		assertEquals(SeedPhrase.fromEntropy(new byte[16]),
			SeedPhrase.parse("  ABANDON abandon\tabandon abandon abandon abandon abandon abandon "
				+ "abandon abandon abandon\n About "));
	}

	@Test
	void theWordListIsThePublishedOne_andAnAlteredOneIsRefused() throws Exception
	{
		byte[] published;
		try (var stream = SeedPhrase.class.getResourceAsStream("bip39-english.txt"))
		{
			published = stream.readAllBytes();
		}
		byte[] altered = new String(published, StandardCharsets.UTF_8)
			.replace("\nzoo\n", "\nzoo \n").getBytes(StandardCharsets.UTF_8);

		List<String> words = SeedPhrase.checkedWordList(published);
		IllegalStateException refused = assertThrows(IllegalStateException.class,
			() -> SeedPhrase.checkedWordList(altered));

		assertEquals(2048, words.size());
		assertEquals(List.of("abandon", "zoo"), List.of(words.getFirst(), words.getLast()));
		assertTrue(refused.getMessage().contains("instead of " + SeedPhrase.WORD_LIST_SHA_256),
			refused.getMessage());
	}

	@ParameterizedTest(name = "{0} is a word of the list: {1}")
	@CsvSource(value = { "abandon, true", "zoo, true", "Abandon, true", "ZOO, true", "'  zoo\t', true",
			"abandoned, false", "zo, false", "'abandon zoo', false", "'   ', false", "\u00e4bandon, false" })
	void isKnownWord_answersForTheBip39EnglishList(final String word, final boolean known)
	{
		assertEquals(known, SeedPhrase.isKnownWord(word), word);
	}

	@ParameterizedTest(name = "{0} is no word")
	@NullAndEmptySource
	void isKnownWord_isFalseForNoWordAtAll(final String word)
	{
		assertFalse(SeedPhrase.isKnownWord(word));
	}

	@Test
	void isKnownWord_knowsEveryWordAPhraseIsMadeOf()
	{
		byte[] entropy = new byte[32];
		for (int i = 0; i < entropy.length; i++)
		{
			entropy[i] = (byte)(i * 37 + 11);
		}
		List<String> words = SeedPhrase.fromEntropy(entropy);

		assertEquals(24, words.size());
		assertTrue(words.stream().allMatch(SeedPhrase::isKnownWord), words.toString());
	}
}
