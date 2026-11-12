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

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.astrapi69.crypt.api.algorithm.HashAlgorithm;
import io.github.astrapi69.crypt.api.algorithm.key.KeyPairGeneratorAlgorithm;
import io.github.astrapi69.crypt.data.hash.HashExtensions;

/**
 * The written form of an address, as ADR 0007 decides it (#309): the network and the version, the
 * keys as the hex of their X.509 encoding, and 8 hex characters of SHA-256 as a checksum.
 *
 * <pre>
 * leth1:&lt;view key&gt;:&lt;spend key&gt;:&lt;checksum&gt;    a published address on the main chain
 * tleth1:&lt;account key&gt;:&lt;checksum&gt;           the address of an account on the test chain
 * </pre>
 * <p>
 * A reader takes the steps of the record in its order and stops at the first that fails, each
 * with its own message: normalise (drop the spaces of {@link TypedText#SPACES}, refuse any other
 * character outside ASCII letters, digits and {@code :}, lower-case), split at {@code :}, the form
 * (the prefix, a known version, the number of parts, keys in hex of even length), the checksum,
 * the network, the keys. The published and the account reader normalise through the same step. The command line and
 * the desktop plugin read through these methods, so what one prints the other takes (#37).
 * <p>
 * The old forms, {@code <view key>:<spend key>} and a bare account key, have neither a checksum
 * nor a network. They are read as a recipient on the test chain and as a recorded address on either
 * chain, and refused as a recipient on the main chain, where no address was ever handed out
 * without a checksum.
 */
public final class AddressText
{

	/** What separates the parts of an address */
	public static final String SEPARATOR = ":";

	/** The version of the format this build writes and reads */
	public static final int VERSION = 1;

	private static final String MAIN_NETWORK = "leth";

	private static final String TEST_NETWORK = "tleth";

	private static final int CHECKSUM_BYTES = 4;

	private static final int PUBLISHED_PARTS = 4;

	private static final int ACCOUNT_PARTS = 3;

	/** The first part of the new form: the network and the version */
	private static final Pattern PREFIX = Pattern.compile("(t?leth)(\\d+)");

	/** A key: hex of even length, never empty */
	private static final Pattern KEY = Pattern.compile("(?:[0-9a-f]{2})+");

	/** The checksum: exactly 8 hex characters */
	private static final Pattern CHECKSUM = Pattern.compile("[0-9a-f]{8}");

	private AddressText()
	{
	}

	/**
	 * A published address in its written form
	 *
	 * @param address
	 *            the address
	 * @param chainIdentifier
	 *            the chain it is written for, {@link Chain#IDENTIFIER} or
	 *            {@link Chain#TEST_IDENTIFIER}
	 * @return the address with its network and checksum
	 * @throws IllegalArgumentException
	 *             for a chain this build writes no addresses of
	 */
	public static String of(final PublishedAddress address, final String chainIdentifier)
	{
		return withChecksum(prefixFor(chainIdentifier) + SEPARATOR + address.viewKey() + SEPARATOR
			+ address.spendKey());
	}

	/**
	 * The address of an account in its written form, what a direct transfer is sent to
	 *
	 * @param accountKey
	 *            the account key, as a transfer names it
	 * @param chainIdentifier
	 *            the chain it is written for
	 * @return the address with its network and checksum
	 * @throws IllegalArgumentException
	 *             for a chain this build writes no addresses of
	 */
	public static String ofAccount(final Bytes accountKey, final String chainIdentifier)
	{
		return withChecksum(prefixFor(chainIdentifier) + SEPARATOR + accountKey);
	}

	/**
	 * Reads a published address that a transfer on the given chain is to pay
	 *
	 * @param text
	 *            the address as a person typed or pasted it
	 * @param chainIdentifier
	 *            the chain of the transfer
	 * @return the address
	 * @throws IllegalArgumentException
	 *             at the first step that fails, with its reason
	 */
	public static PublishedAddress published(final String text, final String chainIdentifier)
	{
		Written written = read(text, Kind.PUBLISHED);
		written.requireNetwork(chainIdentifier);
		return written.publishedAddress();
	}

	/**
	 * Reads the address of an account that a direct transfer on the given chain is to pay
	 *
	 * @param text
	 *            the address as a person typed or pasted it
	 * @param chainIdentifier
	 *            the chain of the transfer
	 * @return the account key
	 * @throws IllegalArgumentException
	 *             at the first step that fails, with its reason
	 */
	public static Bytes account(final String text, final String chainIdentifier)
	{
		Written written = read(text, Kind.ACCOUNT);
		written.requireNetwork(chainIdentifier);
		return written.accountKey();
	}

	/**
	 * Reads a published address that was recorded earlier, in a wallet entry for example: in
	 * either form and for either chain, because the keys of a wallet are the same on both. The
	 * checksum of the new form is still checked
	 *
	 * @param text
	 *            the recorded address
	 * @return the address
	 * @throws IllegalArgumentException
	 *             at the first step that fails, with its reason
	 */
	public static PublishedAddress recorded(final String text)
	{
		return read(text, Kind.PUBLISHED).publishedAddress();
	}

	/**
	 * Whether a text is in one of the old forms, which carry no checksum: a reader that takes
	 * one says so
	 *
	 * @param text
	 *            the address as given
	 * @return true when the text does not start with a network and a version
	 */
	public static boolean hasNoChecksum(final String text)
	{
		StringBuilder first = new StringBuilder();
		text.codePoints().filter(codePoint -> !TypedText.isSpace(codePoint))
			.takeWhile(codePoint -> codePoint != SEPARATOR.charAt(0))
			.forEach(codePoint -> first.appendCodePoint(Character.toLowerCase(codePoint)));
		return !PREFIX.matcher(first).matches();
	}

	/**
	 * Step 1 of reading: drops the spaces of {@link TypedText#SPACES} wherever they stand and
	 * lower-cases the ASCII letters, refusing every other character that is not an ASCII letter, an
	 * ASCII digit or the separator, with its position, counted in code points from 1, and its code
	 * point
	 *
	 * @param text
	 *            the address as given
	 * @return the normalised text, over which the checksum is computed
	 */
	static String normalised(final String text)
	{
		StringBuilder kept = new StringBuilder(text.length());
		int position = 0;
		for (int index = 0; index < text.length();)
		{
			int codePoint = text.codePointAt(index);
			index += Character.charCount(codePoint);
			position++;
			if (TypedText.isSpace(codePoint))
			{
				continue;
			}
			boolean asciiLetterOrDigit = codePoint < 0x80 && Character.isLetterOrDigit(codePoint);
			if (!asciiLetterOrDigit && codePoint != SEPARATOR.charAt(0))
			{
				throw new IllegalArgumentException(String.format(Locale.ROOT,
					"position %d holds U+%04X, and an address holds no such character", position,
					codePoint));
			}
			kept.append(Character.toLowerCase((char)codePoint));
		}
		return kept.toString();
	}

	private static Written read(final String given, final Kind expected)
	{
		String text = normalised(given);
		if (text.isEmpty())
		{
			throw new IllegalArgumentException("an address was expected, and the text is empty");
		}
		String[] parts = text.split(SEPARATOR, -1);
		Matcher prefix = PREFIX.matcher(parts[0]);
		if (prefix.matches())
		{
			return readNewForm(text, parts, prefix, expected);
		}
		return readOldForm(parts, expected);
	}

	private static Written readNewForm(final String text, final String[] parts,
		final Matcher prefix, final Kind expected)
	{
		String version = prefix.group(2);
		if (!String.valueOf(VERSION).equals(version))
		{
			throw new IllegalArgumentException("version " + version + " of the address format is "
				+ "not known here; this build reads version " + VERSION);
		}
		requireKind(Kind.ofParts(parts.length), expected, parts.length);
		for (int part = 1; part < parts.length - 1; part++)
		{
			requireKey(parts, part);
		}
		String checksum = parts[parts.length - 1];
		if (!CHECKSUM.matcher(checksum).matches())
		{
			throw new IllegalArgumentException("the last part is the checksum, 8 hexadecimal "
				+ "characters, and this one is '" + checksum + "'");
		}
		if (!checksumOf(text.substring(0, text.lastIndexOf(SEPARATOR))).equals(checksum))
		{
			throw new IllegalArgumentException(
				"this address is mistyped or incomplete: its checksum does not match");
		}
		return new Written(prefix.group(1),
			Arrays.asList(parts).subList(1, parts.length - 1), 2);
	}

	private static Written readOldForm(final String[] parts, final Kind expected)
	{
		boolean allHex = Arrays.stream(parts).allMatch(part -> KEY.matcher(part).matches());
		Kind kind = parts.length == 2 ? Kind.PUBLISHED : parts.length == 1 ? Kind.ACCOUNT : null;
		if (!allHex || kind == null)
		{
			throw new IllegalArgumentException("an address starts with " + MAIN_NETWORK + VERSION
				+ SEPARATOR + " or " + TEST_NETWORK + VERSION + SEPARATOR
				+ ", and this one starts with '" + parts[0] + "'");
		}
		if (kind != expected)
		{
			throw new IllegalArgumentException(kind == Kind.ACCOUNT
				? "this is the key of an account, not a published address"
				: "this is a published address, not the key of an account");
		}
		return new Written(null, List.of(parts), 1);
	}

	private static void requireKind(final Kind kind, final Kind expected, final int parts)
	{
		if (kind == null)
		{
			throw new IllegalArgumentException("an address has " + ACCOUNT_PARTS
				+ " parts (an account) or " + PUBLISHED_PARTS + " (a published address), "
				+ "separated by '" + SEPARATOR + "', and this one has " + parts);
		}
		if (kind != expected)
		{
			throw new IllegalArgumentException(kind == Kind.ACCOUNT
				? "this is the address of an account (" + ACCOUNT_PARTS
					+ " parts), not a published address (" + PUBLISHED_PARTS + " parts)"
				: "this is a published address (" + PUBLISHED_PARTS
					+ " parts), not the address of an account (" + ACCOUNT_PARTS + " parts)");
		}
	}

	private static void requireKey(final String[] parts, final int part)
	{
		if (!KEY.matcher(parts[part]).matches())
		{
			throw new IllegalArgumentException("part " + (part + 1) + " is no key in "
				+ "hexadecimal: it needs an even number of the characters 0-9 and a-f");
		}
	}

	private static String withChecksum(final String body)
	{
		return body + SEPARATOR + checksumOf(body);
	}

	private static String checksumOf(final String body)
	{
		try
		{
			byte[] digest = HashExtensions.hash(body.getBytes(StandardCharsets.US_ASCII),
				HashAlgorithm.SHA_256);
			return Bytes.of(Arrays.copyOf(digest, CHECKSUM_BYTES)).toString();
		}
		catch (NoSuchAlgorithmException never)
		{
			// SHA-256 is required of every Java platform; the name comes from crypt-api
			throw new IllegalStateException(
				HashAlgorithm.SHA_256.getAlgorithm() + " is missing from this Java runtime", never);
		}
	}

	/**
	 * The first part of an address of a chain: its network and the version, {@code leth1} or
	 * {@code tleth1}
	 *
	 * @param chainIdentifier
	 *            the chain
	 * @return the prefix, without the separator
	 * @throws IllegalArgumentException
	 *             for a chain this build writes no addresses of
	 */
	public static String prefixFor(final String chainIdentifier)
	{
		return networkOf(chainIdentifier) + VERSION;
	}

	private static String networkOf(final String chainIdentifier)
	{
		if (Chain.IDENTIFIER.equals(chainIdentifier))
		{
			return MAIN_NETWORK;
		}
		if (Chain.TEST_IDENTIFIER.equals(chainIdentifier))
		{
			return TEST_NETWORK;
		}
		throw new IllegalArgumentException("no address form for the chain '" + chainIdentifier
			+ "': this build writes addresses of " + Chain.IDENTIFIER + " and "
			+ Chain.TEST_IDENTIFIER);
	}

	private static String chainName(final String network)
	{
		return (MAIN_NETWORK.equals(network) ? "main" : "test") + " chain (" + network + VERSION
			+ ")";
	}

	/** The two kinds of address, told apart by the number of their parts */
	private enum Kind
	{
		PUBLISHED, ACCOUNT;

		static Kind ofParts(final int parts)
		{
			return parts == PUBLISHED_PARTS ? PUBLISHED : parts == ACCOUNT_PARTS ? ACCOUNT : null;
		}
	}

	/**
	 * What steps 1 to 4 leave: the network, or none for an old form, and the keys as hex
	 *
	 * @param network
	 *            {@code leth} or {@code tleth}, null for an old form
	 * @param keys
	 *            the key parts in order
	 * @param firstKeyPart
	 *            the number of the first key's part, counted from 1, for the messages
	 */
	private record Written(String network, List<String> keys, int firstKeyPart)
	{

		void requireNetwork(final String chainIdentifier)
		{
			String expected = networkOf(chainIdentifier);
			if (network == null)
			{
				if (MAIN_NETWORK.equals(expected))
				{
					throw new IllegalArgumentException("an address without a checksum is not "
						+ "taken on the main chain: it needs its " + MAIN_NETWORK + VERSION
						+ SEPARATOR + " form");
				}
				return;
			}
			if (!network.equals(expected))
			{
				throw new IllegalArgumentException("this is an address of the "
					+ chainName(network) + ", and it is read for the " + chainName(expected));
			}
		}

		PublishedAddress publishedAddress()
		{
			Bytes view = Bytes.ofHex(keys.get(0));
			Bytes spend = Bytes.ofHex(keys.get(1));
			requireDecodes(view, KeyPairGeneratorAlgorithm.X25519.getAlgorithm(), firstKeyPart);
			requireDecodes(spend, KeyPairGeneratorAlgorithm.Ed25519.getAlgorithm(),
				firstKeyPart + 1);
			return new PublishedAddress(view, spend);
		}

		Bytes accountKey()
		{
			Bytes account = Bytes.ofHex(keys.get(0));
			for (SignatureSuite suite : SignatureSuite.values())
			{
				if (decodes(account, suite.algorithm()))
				{
					return account;
				}
			}
			throw new IllegalArgumentException("part " + firstKeyPart + " is no public key of a "
				+ "signature suite (" + String.join(", ",
					Arrays.stream(SignatureSuite.values()).map(SignatureSuite::identifier).toList())
				+ ")");
		}

		private static void requireDecodes(final Bytes key, final String algorithm,
			final int part)
		{
			if (!decodes(key, algorithm))
			{
				throw new IllegalArgumentException("part " + part + " is no " + algorithm
					+ " public key");
			}
		}

		private static boolean decodes(final Bytes key, final String algorithm)
		{
			try
			{
				OneTimeAddresses.publicKeyOf(key, algorithm);
				return true;
			}
			catch (IllegalArgumentException notThatKey)
			{
				return false;
			}
		}
	}
}
