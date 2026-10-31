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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The words a person writes down to get a wallet back: BIP-39, English word list.
 * <p>
 * The entropy is turned into words with its own checksum - the first bits of its SHA-256 - so a
 * mistyped or swapped word is refused rather than restoring some other wallet. The words, and an
 * optional passphrase, are then stretched with PBKDF2-HMAC-SHA512 into the 64 byte seed the key
 * derivation starts from.
 * <p>
 * BIP-39 rather than an own scheme for the same reason the derivation is SLIP-0010: a backup is
 * read years later, maybe by other software, and a published standard with test vectors is what
 * makes that checkable. Nothing here is a primitive: SHA-256 is the chain's own digest, PBKDF2 is
 * the platform's. The family has no seed phrase.
 * <p>
 * The word list is the BIP-39 English list, carried as a resource and checked against its
 * published SHA-256 before it is used. A list that was altered - one word, one line ending - would
 * turn every phrase into a different wallet, so it fails closed instead.
 *
 * @see <a href="https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki">BIP-39</a>
 */
public final class SeedPhrase
{

	/** The SHA-256 of the English word list as BIP-39 publishes it */
	static final String WORD_LIST_SHA_256 =
		"2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda";

	/**
	 * PBKDF2 over HMAC-SHA512. A literal because crypt-api names no PBKDF2 factory
	 * (crypt-api#15)
	 */
	private static final String KEY_STRETCHING = "PBKDF2WithHmacSHA512";

	private static final int ITERATIONS = 2048;

	private static final int SEED_BITS = 512;

	private static final int BITS_PER_WORD = 11;

	private static final List<String> WORDS = loadWordList();

	private static final Map<String, Integer> INDEX = indexOf(WORDS);

	private SeedPhrase()
	{
	}

	/**
	 * The words for some entropy
	 *
	 * @param entropy
	 *            16, 20, 24, 28 or 32 bytes
	 * @return 12 to 24 words, the last one carrying the checksum
	 */
	public static List<String> fromEntropy(final byte[] entropy)
	{
		if (entropy.length < 16 || entropy.length > 32 || entropy.length % 4 != 0)
		{
			throw new IllegalArgumentException("BIP-39 takes 16, 20, 24, 28 or 32 bytes of "
				+ "entropy, and this is " + entropy.length + " bytes");
		}
		int checksumBits = entropy.length * 8 / 32;
		byte[] checksum = SigningPayload.digestOf(entropy);
		int total = entropy.length * 8 + checksumBits;
		List<String> words = new ArrayList<>(total / BITS_PER_WORD);
		for (int offset = 0; offset < total; offset += BITS_PER_WORD)
		{
			int index = 0;
			for (int bit = offset; bit < offset + BITS_PER_WORD; bit++)
			{
				boolean set = bit < entropy.length * 8
					? bitOf(entropy, bit)
					: bitOf(checksum, bit - entropy.length * 8);
				index = (index << 1) | (set ? 1 : 0);
			}
			words.add(WORDS.get(index));
		}
		return List.copyOf(words);
	}

	/**
	 * The entropy some words stand for, after checking that every word is in the list and the
	 * checksum holds
	 *
	 * @param words
	 *            12, 15, 18, 21 or 24 words
	 * @return the entropy
	 * @throws IllegalArgumentException
	 *             naming the word that is not in the list, a wrong word count, or a checksum that
	 *             does not match - a phrase with one wrong word is never read as another wallet
	 */
	public static byte[] toEntropy(final List<String> words)
	{
		int count = words.size();
		if (count < 12 || count > 24 || count % 3 != 0)
		{
			throw new IllegalArgumentException(
				"a BIP-39 phrase has 12, 15, 18, 21 or 24 words, and this one has " + count
					+ " words");
		}
		int total = count * BITS_PER_WORD;
		int checksumBits = total / 33;
		byte[] entropy = new byte[(total - checksumBits) / 8];
		int checksum = 0;
		for (int position = 0; position < count; position++)
		{
			int index = indexOfWord(words.get(position), position);
			for (int bit = 0; bit < BITS_PER_WORD; bit++)
			{
				int target = position * BITS_PER_WORD + bit;
				boolean set = ((index >> (BITS_PER_WORD - 1 - bit)) & 1) == 1;
				if (target < entropy.length * 8)
				{
					if (set)
					{
						entropy[target / 8] |= (byte)(0x80 >>> (target % 8));
					}
				}
				else
				{
					checksum = (checksum << 1) | (set ? 1 : 0);
				}
			}
		}
		int expected = (SigningPayload.digestOf(entropy)[0] & 0xFF) >>> (8 - checksumBits);
		if (checksum != expected)
		{
			throw new IllegalArgumentException("the phrase's checksum does not match its words: "
				+ "one of them is wrong or two are swapped");
		}
		return entropy;
	}

	/**
	 * The words of a phrase as somebody typed it: any whitespace between them, any case
	 *
	 * @param phrase
	 *            the phrase
	 * @return the words, lower case
	 */
	public static List<String> parse(final String phrase)
	{
		String trimmed = phrase.strip();
		if (trimmed.isEmpty())
		{
			return List.of();
		}
		return List.of(trimmed.toLowerCase(Locale.ROOT).split("\\s+"));
	}

	/**
	 * Whether a word is in the BIP-39 English word list, read as {@link #parse} reads it: in lower
	 * case and without the spaces around it. A program that asks for words one at a time can name a
	 * misspelled word at once, without reading an error message
	 *
	 * @param word
	 *            the word
	 * @return true for a word of the list
	 */
	public static boolean isKnownWord(final String word)
	{
		return INDEX.containsKey(word.strip().toLowerCase(Locale.ROOT));
	}

	/**
	 * The 64 byte seed of BIP-39: PBKDF2-HMAC-SHA512 over the words, salted with "mnemonic" and
	 * the passphrase, 2048 rounds, both in Unicode NFKD
	 *
	 * @param words
	 *            the phrase
	 * @param passphrase
	 *            the optional passphrase, empty for none
	 * @return the seed
	 */
	static byte[] seed(final List<String> words, final String passphrase)
	{
		String mnemonic = Normalizer.normalize(String.join(" ", words), Normalizer.Form.NFKD);
		byte[] salt = Normalizer.normalize("mnemonic" + passphrase, Normalizer.Form.NFKD)
			.getBytes(StandardCharsets.UTF_8);
		PBEKeySpec spec = new PBEKeySpec(mnemonic.toCharArray(), salt, ITERATIONS, SEED_BITS);
		try
		{
			return SecretKeyFactory.getInstance(KEY_STRETCHING).generateSecret(spec).getEncoded();
		}
		catch (GeneralSecurityException missing)
		{
			throw new IllegalStateException("this Java runtime has no " + KEY_STRETCHING, missing);
		}
		finally
		{
			spec.clearPassword();
		}
	}

	private static int indexOfWord(final String word, final int position)
	{
		Integer index = INDEX.get(word);
		if (index == null)
		{
			throw new IllegalArgumentException("word " + (position + 1) + ", '" + word
				+ "', is not in the BIP-39 English word list");
		}
		return index;
	}

	private static boolean bitOf(final byte[] bytes, final int bit)
	{
		return ((bytes[bit / 8] >> (7 - bit % 8)) & 1) == 1;
	}

	private static List<String> loadWordList()
	{
		try (InputStream stream = SeedPhrase.class.getResourceAsStream("bip39-english.txt"))
		{
			if (stream == null)
			{
				throw new IllegalStateException("the BIP-39 word list is missing from the build");
			}
			return checkedWordList(stream.readAllBytes());
		}
		catch (IOException unreadable)
		{
			throw new IllegalStateException("the BIP-39 word list cannot be read", unreadable);
		}
	}

	/**
	 * The words of a word list, after checking that it is the published one
	 *
	 * @param content
	 *            the bytes of the list
	 * @return the 2048 words in order
	 * @throws IllegalStateException
	 *             when the bytes are not exactly the published list
	 */
	static List<String> checkedWordList(final byte[] content)
	{
		String digest = Bytes.of(SigningPayload.digestOf(content)).toString();
		if (!WORD_LIST_SHA_256.equals(digest))
		{
			throw new IllegalStateException("the BIP-39 word list has SHA-256 " + digest
				+ " instead of " + WORD_LIST_SHA_256
				+ "; every phrase would name another wallet");
		}
		return List.of(new String(content, StandardCharsets.UTF_8).strip().split("\n"));
	}

	private static Map<String, Integer> indexOf(final List<String> words)
	{
		Map<String, Integer> index = new HashMap<>();
		for (int position = 0; position < words.size(); position++)
		{
			index.put(words.get(position), position);
		}
		return Map.copyOf(index);
	}
}
