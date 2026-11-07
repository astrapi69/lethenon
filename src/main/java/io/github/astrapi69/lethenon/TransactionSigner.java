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

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

import io.github.astrapi69.mystic.crypt.key.Ed25519ExpandedPrivateKey;
import io.github.astrapi69.mystic.crypt.key.Signatures;

/**
 * Signs a transfer, and checks a signed one.
 * <p>
 * Everything cryptographic here comes from this family's own library - {@code Signatures} out of
 * mystic-crypt 13.1, which dispatches by the suite identifier - rather than from the platform
 * directly. The chain
 * is the library's acceptance test (issue #1): primitives that cannot carry a chain are not
 * primitives, and reaching past them into {@code java.security} would mean never finding that out.
 * The gap this project reported when it had to write that switch itself is closed: mystic-crypt#149
 * added {@code Signatures}, and this class now asks it rather than deciding per scheme. An unknown
 * identifier is refused there with an exception rather than answered with a false that would read
 * as an invalid signature.
 * <p>
 * This class writes no primitive either way, only the rules around one - what gets signed, which
 * suite is named in it, and whose key has to match.
 */
public final class TransactionSigner
{

	private TransactionSigner()
	{
	}

	/**
	 * A fresh key pair of the given suite
	 *
	 * @param suite
	 *            the signature scheme
	 * @return the key pair
	 */
	public static KeyPair newKeyPair(final SignatureSuite suite)
	{
		try
		{
			return KeyPairGenerator.getInstance(suite.algorithm()).generateKeyPair();
		}
		catch (GeneralSecurityException missing)
		{
			throw new IllegalStateException(
				"this Java runtime has no " + suite.algorithm() + " key pair generator", missing);
		}
	}

	/**
	 * The address form of a public key: the encoded key, which is what a transfer names as its
	 * sender
	 *
	 * @param publicKey
	 *            the key
	 * @return its bytes
	 */
	public static Bytes asBytes(final PublicKey publicKey)
	{
		return Bytes.of(publicKey.getEncoded());
	}

	/**
	 * Signs a transfer
	 *
	 * @param body
	 *            the transfer, whose sender field must be the public key belonging to this private
	 *            key
	 * @param suite
	 *            the signature scheme
	 * @param privateKey
	 *            the key to sign with
	 * @return the signed transaction
	 */
	public static SignedTransaction sign(final TransactionBody body, final SignatureSuite suite,
		final PrivateKey privateKey)
	{
		try
		{
			byte[] signature = Signatures.sign(suite.algorithm(), privateKey,
				SigningPayload.of(body, suite));
			return new SignedTransaction(body, suite, Bytes.of(signature));
		}
		catch (GeneralSecurityException refused)
		{
			throw new IllegalStateException("the transfer could not be signed", refused);
		}
	}

	/**
	 * Whether a signed transaction was signed by the key it names, over exactly the transfer it
	 * carries
	 *
	 * @param transaction
	 *            the signed transaction
	 * @return true when the signature matches, false for every way it does not
	 */
	/**
	 * Signs a transfer out of a one-time destination.
	 * <p>
	 * A one-time key is a SCALAR, not an RFC 8032 seed, so it is not a {@link PrivateKey} and the
	 * platform cannot sign with it; mystic-crypt's expanded key does, and the signature it produces
	 * is checked by {@link #verify} like any other - the chain has no second spending path (#21).
	 * The suite is always Ed25519: there is no stealth construction for the post-quantum one, and
	 * its destinations stay {@link AddressScheme#DIRECT}.
	 *
	 * @param body
	 *            the transfer, whose sender is the one-time destination's key
	 * @param oneTimeKey
	 *            the blinded key from
	 *            {@link OneTimeAddresses#oneTimeKey(Destination, PublishedAddress, PrivateKey, PrivateKey)}
	 * @return the signed transfer
	 */
	public static SignedTransaction sign(final TransactionBody body,
		final Ed25519ExpandedPrivateKey oneTimeKey)
	{
		byte[] signature = oneTimeKey
			.sign(SigningPayload.of(body, SignatureSuite.ED25519));
		return new SignedTransaction(body, SignatureSuite.ED25519, Bytes.of(signature));
	}

	/**
	 * Whether a transfer is signed by the key it names as its sender, under the suite it names
	 *
	 * @param transaction
	 *            the signed transfer
	 * @return true when the signature is valid; false for a wrong signature and for a key or a
	 *         signature that cannot be read
	 */
	public static boolean verify(final SignedTransaction transaction)
	{
		try
		{
			PublicKey publicKey = KeyFactory.getInstance(transaction.suite().algorithm())
				.generatePublic(new X509EncodedKeySpec(transaction.body().sender().toByteArray()));
			byte[] payload = SigningPayload.of(transaction.body(), transaction.suite());
			return Signatures.verify(transaction.suite().algorithm(), publicKey, payload,
				transaction.signature().toByteArray());
		}
		catch (GeneralSecurityException refused)
		{
			// a key that cannot be read and a signature that cannot be checked are the same answer
			// to the only question asked here: no, this is not authorised
			return false;
		}
	}
}
