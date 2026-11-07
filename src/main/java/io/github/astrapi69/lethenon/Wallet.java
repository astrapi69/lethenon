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

import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.List;

import io.github.astrapi69.crypt.api.algorithm.key.KeyPairGeneratorAlgorithm;
import io.github.astrapi69.mystic.crypt.key.Ed25519ExpandedPrivateKey;
import io.github.astrapi69.mystic.crypt.key.SeedDerivation;
import io.github.astrapi69.mystic.crypt.key.SeedDerivation.Curve;
import io.github.astrapi69.mystic.crypt.secret.SecretShare;
import io.github.astrapi69.mystic.crypt.secret.SecretSharing;

/**
 * A wallet that can be recovered: 32 bytes of entropy, every key derived from them, and two ways
 * back - the 24 words of its {@link SeedPhrase BIP-39 phrase}, or enough Shamir shares of the
 * entropy (lethenon#2, milestone 4). Both reach the same wallet: the shares split the entropy, and
 * the entropy is what the words encode.
 * <p>
 * The keys are never stored on their own. The phrase is stretched into the 64 byte BIP-39 seed,
 * with an empty passphrase, and each key is a path below that seed, after SLIP-0010:
 *
 * <pre>
 * ed25519 tree     m/1984'/0'/0'   the Ed25519 spend key
 * curve25519 tree  m/1984'/0'/1'   the X25519 view key of the published address
 * ed25519 tree     m/1984'/0'/2'   the seed xi of the ML-DSA-65 spend key
 * </pre>
 *
 * 1984 is this chain's purpose number, the 0 after it the account. SLIP-0010 knows nothing of
 * ML-DSA; its 32 byte seed is taken from a branch of the ed25519 tree, which gives 32 bytes that
 * nothing else uses, and FIPS 204 makes the key pair from them. These paths are a recovery format:
 * once a wallet exists, changing one makes its keys unreachable from its own backup.
 * <p>
 * The backup is {@link SecretSharing} from mystic-crypt - Shamir over the entropy, with every share
 * knowing its split and its threshold, so shares of two different wallets or too few of them are
 * refused instead of combining into wrong entropy and a wallet that is silently somebody else's.
 */
public final class Wallet
{

	/** How many bytes of entropy a wallet is: 24 words */
	public static final int ENTROPY_LENGTH = 32;

	/** This chain's purpose number, the first step of every path */
	static final int PURPOSE = 1984;

	/** The only account so far */
	static final int ACCOUNT = 0;

	private final byte[] entropy;

	private final byte[] seed;

	/**
	 * The wallet of some entropy, for the wallet file
	 *
	 * @param entropy
	 *            32 bytes; copied
	 * @return the wallet
	 */
	static Wallet ofEntropy(final byte[] entropy)
	{
		return new Wallet(entropy);
	}

	/**
	 * The entropy, for the wallet file
	 *
	 * @return a copy the caller is expected to overwrite
	 */
	byte[] entropy()
	{
		return entropy.clone();
	}

	private Wallet(final byte[] entropy)
	{
		if (entropy.length != ENTROPY_LENGTH)
		{
			throw new IllegalArgumentException("a wallet is " + ENTROPY_LENGTH
				+ " bytes of entropy, 24 words, and this is " + entropy.length + " bytes");
		}
		this.entropy = entropy.clone();
		this.seed = SeedPhrase.seed(SeedPhrase.fromEntropy(entropy), "");
	}

	/**
	 * A new wallet from fresh randomness
	 *
	 * @return the wallet
	 */
	public static Wallet create()
	{
		byte[] entropy = new byte[ENTROPY_LENGTH];
		new SecureRandom().nextBytes(entropy);
		return new Wallet(entropy);
	}

	/**
	 * The wallet that the given shares are the backup of
	 *
	 * @param shares
	 *            at least as many shares of one split as that split needs
	 * @return the wallet, with every key it had
	 * @throws IllegalArgumentException
	 *             for too few shares, shares of different splits, a share given twice, or a secret
	 *             that is not a wallet's entropy
	 */
	public static Wallet restore(final List<SecretShare> shares)
	{
		return new Wallet(SecretSharing.combine(shares));
	}

	/**
	 * Splits the entropy into shares, any {@code threshold} of which bring the wallet back
	 *
	 * @param threshold
	 *            how many shares a restore needs, at least 2
	 * @param shares
	 *            how many shares to make
	 * @return the shares, each one encodable as a line of text with {@link SecretShare#encode()}
	 */
	/**
	 * The wallet that a phrase was written down for
	 *
	 * @param phrase
	 *            the 24 words, separated by any whitespace, in any case
	 * @return the wallet, with every key it had
	 * @throws IllegalArgumentException
	 *             for a word that is not in the list, a checksum that does not match, or a valid
	 *             phrase of fewer words, which is not a wallet of this chain
	 */
	public static Wallet fromPhrase(final String phrase)
	{
		return new Wallet(SeedPhrase.toEntropy(SeedPhrase.parse(phrase)));
	}

	/**
	 * The 24 words to write down
	 *
	 * @return the phrase, words separated by single spaces
	 */
	public String phrase()
	{
		return String.join(" ", SeedPhrase.fromEntropy(entropy));
	}

	/**
	 * Splits the wallet's entropy into shares, of which any {@code threshold} give the wallet back
	 * (Shamir's secret sharing, from mystic-crypt)
	 *
	 * @param threshold
	 *            how many shares restore the wallet
	 * @param shares
	 *            how many shares to make
	 * @return the shares
	 */
	public List<SecretShare> split(final int threshold, final int shares)
	{
		return SecretSharing.split(entropy, threshold, shares);
	}

	/**
	 * The account key of a suite, the bytes a transfer names as its sender
	 *
	 * @param suite
	 *            the signature scheme
	 * @return the encoded public key
	 */
	public Bytes spendKey(final SignatureSuite suite)
	{
		return TransactionSigner.asBytes(spendKeyPair(suite).getPublic());
	}

	/**
	 * What this wallet publishes to be paid at one-time destinations: the X25519 view key and the
	 * Ed25519 spend key
	 *
	 * @return the published address
	 */
	public PublishedAddress address()
	{
		return PublishedAddress.of(viewKeyPair(), spendKeyPair(SignatureSuite.ED25519));
	}

	/**
	 * The view key pair, whose private half recognises payments to {@link #address()} and moves
	 * nothing
	 *
	 * @return the X25519 key pair
	 */
	public KeyPair viewKeyPair()
	{
		return DeterministicKeys.from(KeyPairGeneratorAlgorithm.X25519.getAlgorithm(),
			Bytes.of(SeedDerivation.derive(Curve.CURVE25519, seed, PURPOSE, ACCOUNT, 1)
				.privateKey()));
	}

	/**
	 * Signs a transfer from this wallet's account of the given suite
	 *
	 * @param body
	 *            the transfer; its sender must be {@link #spendKey(SignatureSuite)} of the suite
	 * @param suite
	 *            the signature scheme
	 * @return the signed transaction
	 * @throws IllegalArgumentException
	 *             when the transfer names another sender, which no signature of this wallet could
	 *             make valid
	 */
	public SignedTransaction sign(final TransactionBody body, final SignatureSuite suite)
	{
		KeyPair keyPair = spendKeyPair(suite);
		Bytes own = TransactionSigner.asBytes(keyPair.getPublic());
		if (!own.equals(body.sender()))
		{
			throw new IllegalArgumentException("this wallet's " + suite.identifier()
				+ " account is " + own + ", and the transfer is from " + body.sender());
		}
		return TransactionSigner.sign(body, suite, keyPair.getPrivate());
	}

	/**
	 * The key that spends a payment made to one of this wallet's one-time destinations.
	 * <p>
	 * Everything it needs is here: the view key recognises the payment, the Ed25519 spend key is
	 * what the one-time key is blinded from. A caller holding only the chain and this wallet can
	 * therefore move what was paid to it without ever learning which published address a
	 * destination belongs to (#21).
	 *
	 * @param destination
	 *            the destination a transfer in the chain paid to
	 * @return the blinded key, which signs through
	 *         {@link TransactionSigner#sign(TransactionBody, Ed25519ExpandedPrivateKey)}
	 * @throws IllegalArgumentException
	 *             when the payment was not made to this wallet
	 */
	public Ed25519ExpandedPrivateKey oneTimeKey(final Destination destination)
	{
		return OneTimeAddresses.oneTimeKey(destination, address(), viewKeyPair().getPrivate(),
			spendKeyPair(SignatureSuite.ED25519).getPrivate());
	}

	private KeyPair spendKeyPair(final SignatureSuite suite)
	{
		int role = switch (suite)
		{
			case ED25519 -> 0;
			case ML_DSA_65 -> 2;
		};
		Bytes keySeed = Bytes.of(
			SeedDerivation.derive(Curve.ED25519, seed, PURPOSE, ACCOUNT, role).privateKey());
		return DeterministicKeys.from(suite.algorithm(), keySeed);
	}
}
