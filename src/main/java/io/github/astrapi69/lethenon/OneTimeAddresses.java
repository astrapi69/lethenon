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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.SecretKey;

import io.github.astrapi69.crypt.api.algorithm.HashAlgorithm;
import io.github.astrapi69.crypt.api.algorithm.key.KeyPairGeneratorAlgorithm;
import io.github.astrapi69.mystic.crypt.key.Ed25519ExpandedPrivateKey;
import io.github.astrapi69.mystic.crypt.key.Ed25519KeyBlinding;
import io.github.astrapi69.mystic.crypt.key.X25519KeyExchange;

/**
 * Payments that cannot be linked to the address they were sent to, and can still be spent.
 * <p>
 * The sender takes the recipient's published view key, makes a key pair for this payment alone,
 * derives a shared secret with X25519, and hashes it into a tweak. The destination is the
 * recipient's spend key blinded with that tweak - the one-time public key {@code P = S + H(s)*B},
 * a real Ed25519 public key. Two payments to the same published address therefore land on keys with
 * nothing visibly in common, the recipient recognises both with its view private key alone, and it
 * can MOVE them, because the blinded scalar {@code b + H(s) mod l} is a private key only the holder
 * of the spend key can compute (#21).
 * <p>
 * The view tag is one byte of the same secret, carried in the clear. It is a filter, not a secret:
 * a wallet can rule out 255 of every 256 transactions with one comparison instead of a full
 * derivation, which is what makes scanning a whole chain affordable on a laptop.
 * <p>
 * Nothing here is curve arithmetic. The X25519 agreement is mystic-crypt's
 * {@link X25519KeyExchange}, which runs the raw secret through HKDF; the blinding is its
 * {@link Ed25519KeyBlinding}, the deliberate exception decided in #21 and built there rather than
 * in this project. What this class defines is the rule: what is hashed, in which order, with which
 * domain tag.
 */
public final class OneTimeAddresses
{

	/** What the derivation is for, so the same secret cannot mean something else elsewhere */
	public static final String DOMAIN_TAG = "lethenon.stealth.v2";

	/** The view tag's own tag, so the filter byte is not a byte of the tweak */
	private static final String VIEW_TAG_DOMAIN = DOMAIN_TAG + ".tag";

	private OneTimeAddresses()
	{
	}

	/**
	 * A fresh X25519 pair for one payment, which the sender publishes the public half of inside the
	 * transaction and then forgets
	 *
	 * @return the pair
	 */
	public static KeyPair newEphemeralKeyPair()
	{
		try
		{
			return X25519KeyExchange.newKeyPair();
		}
		catch (GeneralSecurityException missing)
		{
			throw new IllegalStateException("this Java runtime has no X25519", missing);
		}
	}

	/**
	 * The destination a sender writes into a transfer
	 *
	 * @param address
	 *            the recipient's published address
	 * @param ephemeral
	 *            the pair made for this payment alone
	 * @return the destination: the scheme, the one-time public key, the ephemeral public half and
	 *         the view tag
	 */
	public static Destination destinationFor(final PublishedAddress address,
		final KeyPair ephemeral)
	{
		byte[] shared = sharedSecret(ephemeral.getPrivate(), publicKeyOf(address.viewKey(),
			KeyPairGeneratorAlgorithm.X25519.getAlgorithm()));
		return new Destination(AddressScheme.STEALTH_V2, oneTimePublicKey(address, shared),
			Bytes.of(ephemeral.getPublic().getEncoded()), viewTagOf(shared));
	}

	/**
	 * Whether a destination was meant for this recipient.
	 * <p>
	 * The view tag is compared first, which is the cheap half; only then is the one-time key
	 * derived and compared.
	 *
	 * @param destination
	 *            the destination a transfer carries
	 * @param address
	 *            the recipient's published address
	 * @param viewPrivateKey
	 *            the private half of the view key - the spending key is not needed to recognise a
	 *            payment, which is the point of having two
	 * @return true when this payment belongs to the recipient
	 */
	public static boolean belongsTo(final Destination destination, final PublishedAddress address,
		final PrivateKey viewPrivateKey)
	{
		if (!AddressScheme.STEALTH_V2.equals(destination.scheme()))
		{
			return false;
		}
		byte[] shared = sharedSecretOf(destination, viewPrivateKey);
		return destination.viewTag() == viewTagOf(shared)
			&& oneTimePublicKey(address, shared).equals(destination.key());
	}

	/**
	 * The private key of a one-time destination: the recipient's spend scalar plus the tweak of
	 * this payment. This is what makes a stealth payment spendable rather than only receivable.
	 *
	 * @param destination
	 *            the destination the transfer paid to
	 * @param address
	 *            the recipient's published address
	 * @param viewPrivateKey
	 *            the private half of the view key, which recognises the payment
	 * @param spendPrivateKey
	 *            the private half of the spend key, which is what signing needs - so this call
	 *            happens where the spending key lives, and scanning does not
	 * @return the blinded key, which signs with
	 *         {@link Ed25519ExpandedPrivateKey#sign(byte[])}
	 * @throws IllegalArgumentException
	 *             when the destination does not belong to this address, rather than returning a key
	 *             that signs for nothing
	 */
	public static Ed25519ExpandedPrivateKey oneTimeKey(final Destination destination,
		final PublishedAddress address, final PrivateKey viewPrivateKey,
		final PrivateKey spendPrivateKey)
	{
		if (!belongsTo(destination, address, viewPrivateKey))
		{
			throw new IllegalArgumentException(
				"this destination was not paid to " + address.spendKey() + ", so there is no "
					+ "one-time key for it here");
		}
		return Ed25519KeyBlinding.blind(edwardsPrivateKey(spendPrivateKey),
			tweakOf(sharedSecretOf(destination, viewPrivateKey), address.spendKey()));
	}

	/**
	 * The tweak of a payment, from the sender's side: the scalar that moves the recipient's spend
	 * key to this payment's destination. Package-visible because only a test needs it on its own -
	 * a sender gets it inside {@link #destinationFor}, a recipient inside {@link #oneTimeKey}.
	 *
	 * @param address
	 *            the recipient's published address
	 * @param ephemeralPrivateKey
	 *            the private half of the pair made for this payment
	 * @return the tweak
	 */
	static byte[] tweakFor(final PublishedAddress address, final PrivateKey ephemeralPrivateKey)
	{
		return tweakOf(sharedSecret(ephemeralPrivateKey, publicKeyOf(address.viewKey(),
			KeyPairGeneratorAlgorithm.X25519.getAlgorithm())), address.spendKey());
	}

	private static Bytes oneTimePublicKey(final PublishedAddress address, final byte[] shared)
	{
		EdECPublicKey spendKey = (EdECPublicKey)publicKeyOf(address.spendKey(),
			KeyPairGeneratorAlgorithm.Ed25519.getAlgorithm());
		return Bytes.of(Ed25519KeyBlinding.blind(spendKey, tweakOf(shared, address.spendKey()))
			.getEncoded());
	}

	/**
	 * SHA-512 rather than the chain's SHA-256: the tweak is read as a scalar and reduced mod l, and
	 * a whole 64-byte hash is what makes that reduction uniform rather than biased
	 */
	private static byte[] tweakOf(final byte[] shared, final Bytes spendKey)
	{
		ByteArrayOutputStream material = new ByteArrayOutputStream();
		material.writeBytes(DOMAIN_TAG.getBytes(StandardCharsets.UTF_8));
		material.writeBytes(shared);
		material.writeBytes(spendKey.toByteArray());
		return digest(HashAlgorithm.SHA_512.getAlgorithm(), material.toByteArray());
	}

	private static byte[] sharedSecretOf(final Destination destination,
		final PrivateKey viewPrivateKey)
	{
		return sharedSecret(viewPrivateKey, publicKeyOf(destination.ephemeralKey(),
			KeyPairGeneratorAlgorithm.X25519.getAlgorithm()));
	}

	private static byte[] sharedSecret(final PrivateKey ours, final PublicKey theirs)
	{
		try
		{
			SecretKey secret = X25519KeyExchange.deriveSharedSecret(ours, theirs, 32);
			return secret.getEncoded();
		}
		catch (GeneralSecurityException refused)
		{
			throw new IllegalArgumentException("the two keys do not agree on a secret", refused);
		}
	}

	private static int viewTagOf(final byte[] shared)
	{
		ByteArrayOutputStream material = new ByteArrayOutputStream();
		material.writeBytes(VIEW_TAG_DOMAIN.getBytes(StandardCharsets.UTF_8));
		material.writeBytes(shared);
		return SigningPayload.digestOf(material.toByteArray())[0] & 0xFF;
	}

	private static EdECPrivateKey edwardsPrivateKey(final PrivateKey spendPrivateKey)
	{
		if (spendPrivateKey instanceof EdECPrivateKey edwardsKey)
		{
			return edwardsKey;
		}
		throw new IllegalArgumentException("a one-time key is blinded from an Ed25519 spend key, "
			+ "and this one is a " + spendPrivateKey.getAlgorithm()
			+ " key; there is no stealth construction for the post-quantum suite (#21)");
	}

	/**
	 * Decodes a public key from its X.509 encoding
	 *
	 * @param encoded
	 *            the encoding, as an address or a destination carries it
	 * @param algorithm
	 *            the platform's name of the key type, from crypt-api
	 * @return the key
	 * @throws IllegalArgumentException
	 *             when the bytes are no public key of that type
	 */
	static PublicKey publicKeyOf(final Bytes encoded, final String algorithm)
	{
		try
		{
			return KeyFactory.getInstance(algorithm)
				.generatePublic(new X509EncodedKeySpec(encoded.toByteArray()));
		}
		catch (GeneralSecurityException refused)
		{
			throw new IllegalArgumentException("not an " + algorithm + " public key: " + encoded,
				refused);
		}
	}

	private static byte[] digest(final String algorithm, final byte[] content)
	{
		try
		{
			return MessageDigest.getInstance(algorithm).digest(content);
		}
		catch (NoSuchAlgorithmException never)
		{
			throw new IllegalStateException(algorithm + " is missing from this Java runtime",
				never);
		}
	}
}
