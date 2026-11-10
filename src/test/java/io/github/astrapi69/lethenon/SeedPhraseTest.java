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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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

	/**
	 * A phrase in one of the forms people paste - numbered as a backup sheet shows it, with commas,
	 * or with non-breaking spaces - parses to its words and gives its entropy back (#286)
	 */
	@ParameterizedTest(name = "{argumentSetName}")
	@MethodSource("pastedForms")
	void aPastedPhrase_parsesToItsWords(final Function<List<String>, String> form)
	{
		byte[] entropy = entropyOfTwentyFourWords();
		List<String> words = SeedPhrase.fromEntropy(entropy);

		List<String> parsed = SeedPhrase.parse(form.apply(words));

		assertEquals(words, parsed);
		assertArrayEquals(entropy, SeedPhrase.toEntropy(parsed));
	}

	/** The forms a phrase arrives in when it is pasted rather than typed (#286) */
	static Stream<Arguments> pastedForms()
	{
		return Stream.of(
			Arguments.argumentSet("separated by non-breaking spaces",
				joined("\u00A0", (number, word) -> word)),
			Arguments.argumentSet("numbered 1. on one line", joined(" ", (number, word) -> number
				+ ". " + word)),
			Arguments.argumentSet("numbered 1. one per line", joined("\n", (number, word) -> number
				+ ". " + word)),
			Arguments.argumentSet("numbered 1) on one line", joined(" ", (number, word) -> number
				+ ") " + word)),
			Arguments.argumentSet("numbered 01 one per line", joined("\n",
				(number, word) -> String.format("%02d", number) + " " + word)),
			Arguments.argumentSet("numbered 1: on one line", joined(" ", (number, word) -> number
				+ ": " + word)),
			Arguments.argumentSet("numbered with a bare number", joined(" ", (number, word) -> number
				+ " " + word)),
			Arguments.argumentSet("numbered 1. without a space", joined(" ", (number, word) -> number
				+ "." + word)),
			Arguments.argumentSet("comma and space", joined(", ", (number, word) -> word)),
			Arguments.argumentSet("comma alone", joined(",", (number, word) -> word)));
	}

	private static Function<List<String>, String> joined(final String separator,
		final BiFunction<Integer, String, String> eachWord)
	{
		return words -> IntStream.range(0, words.size())
			.mapToObj(index -> eachWord.apply(index + 1, words.get(index)))
			.collect(Collectors.joining(separator));
	}

	/** 32 bytes of entropy, 24 words, every one of them different from its neighbours */
	private static byte[] entropyOfTwentyFourWords()
	{
		byte[] entropy = new byte[32];
		for (int index = 0; index < entropy.length; index++)
		{
			entropy[index] = (byte)(index * 37 + 11);
		}
		return entropy;
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

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = { "the first word of the list|abandon|true",
			"the last word of the list|zoo|true", "a word in capitals|Abandon|true",
			"a word with spaces around it|'  zoo '|true", "a word that is not in the list|lethenon|false",
			"one letter short of a word|abando|false", "an empty word|''|false",
			"two words of the list|abandon zoo|false", "a number alone|1.|false" })
	void aWordIsKnownOnlyWhenItIsInTheList(final String name, final String word,
		final boolean known)
	{
		assertEquals(known, SeedPhrase.isKnownWord(word));
	}

	/**
	 * A word entered with what {@link SeedPhrase#parse} drops around it - a non-breaking space, a
	 * number in front, a comma - is a known word, read as parse reads it
	 */
	@ParameterizedTest(name = "{argumentSetName}")
	@MethodSource("enteredWords")
	void aWordAsParseReadsIt_isKnown(final String entered, final String word)
	{
		assertEquals(List.of(word), SeedPhrase.parse(entered));
		assertTrue(SeedPhrase.isKnownWord(entered));
	}

	/** One word as a program that asks for the words one at a time may get it, pasted */
	static Stream<Arguments> enteredWords()
	{
		return Stream.of(
			Arguments.argumentSet("a non-breaking space in front (U+00A0)", " abandon",
				"abandon"),
			Arguments.argumentSet("a figure space behind (U+2007)", "zoo ", "zoo"),
			Arguments.argumentSet("narrow no-break spaces around (U+202F)", " abandon ",
				"abandon"),
			Arguments.argumentSet("numbered 1. without a space", "1.abandon", "abandon"),
			Arguments.argumentSet("numbered 1. with a space", "1. zoo", "zoo"),
			Arguments.argumentSet("numbered 01", "01 abandon", "abandon"),
			Arguments.argumentSet("a comma behind", "zoo,", "zoo"));
	}

	@Test
	void everyWordOfAPhraseIsKnown()
	{
		for (String word : SeedPhrase.fromEntropy(new byte[32]))
		{
			assertTrue(SeedPhrase.isKnownWord(word), word);
		}
	}
	@Test
	void wordsStartingWith_givesTheWordsOfTheListThatStartSo_inTheOrderOfTheList()
	{
		assertEquals(List.of("act", "action", "actor", "actress", "actual"),
			SeedPhrase.wordsStartingWith("act"));
		assertEquals(List.of("zebra", "zero"), SeedPhrase.wordsStartingWith("ze"));
	}

	/**
	 * What is typed is read as {@link SeedPhrase#parse} reads a word, so a program can hand over
	 * a field as the person typed into it
	 */
	@ParameterizedTest(name = "{argumentSetName}")
	@MethodSource("typedStarts")
	void wordsStartingWith_readsWhatIsTypedAsParseReadsIt(final String typed)
	{
		assertEquals(List.of("act", "action", "actor", "actress", "actual"),
			SeedPhrase.wordsStartingWith(typed));
	}

	/** The start "act" as it may come out of a field */
	static Stream<Arguments> typedStarts()
	{
		return Stream.of(Arguments.argumentSet("upper case", "ACT"),
			Arguments.argumentSet("spaces around", "  act "),
			Arguments.argumentSet("a non-breaking space in front (U+00A0)", "\u00A0act"),
			Arguments.argumentSet("numbered 3. with a space", "3. act"),
			Arguments.argumentSet("numbered 3) without a space", "3)act"),
			Arguments.argumentSet("a comma behind", "act,"));
	}

	/** Nothing to start from, a start no word has, and two words give no word */
	@ParameterizedTest(name = "{argumentSetName}")
	@MethodSource("startsWithoutAWord")
	void wordsStartingWith_givesNoWord_forNoStartOrMoreThanOne(final String typed)
	{
		assertEquals(List.of(), SeedPhrase.wordsStartingWith(typed));
	}

	static Stream<Arguments> startsWithoutAWord()
	{
		return Stream.of(Arguments.argumentSet("nothing typed", ""),
			Arguments.argumentSet("only spaces", " \t\n"),
			Arguments.argumentSet("only a number", "12."),
			Arguments.argumentSet("a start no word has", "qq"),
			Arguments.argumentSet("a whole word with more behind", "zoom"),
			Arguments.argumentSet("two words", "act zoo"));
	}

	/**
	 * BIP-39 lays its English list out so that the first four letters name one word: every word
	 * of four letters or more is the only word its first four letters give, and every shorter word
	 * comes first among the words it starts
	 */
	@Test
	void theFirstFourLettersOfEveryWord_giveThatWordAlone() throws IOException
	{
		List<String> list;
		try (InputStream stream = SeedPhrase.class.getResourceAsStream("bip39-english.txt"))
		{
			list = List.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
		}
		assertEquals(2048, list.size());
		for (String word : list.stream().filter(listed -> listed.length() >= 4).toList())
		{
			assertEquals(List.of(word), SeedPhrase.wordsStartingWith(word.substring(0, 4)), word);
		}
		for (String word : list.stream().filter(listed -> listed.length() < 4).toList())
		{
			assertEquals(word, SeedPhrase.wordsStartingWith(word).getFirst(), word);
		}
	}

	@Test
	void wordsStartingWith_givesAListNobodyCanChange()
	{
		List<String> started = SeedPhrase.wordsStartingWith("act");

		assertThrows(UnsupportedOperationException.class, () -> started.add("zoo"));
	}
}
