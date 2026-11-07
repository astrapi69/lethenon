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
package io.github.astrapi69.lethenon.transport;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.ChainWork;

/**
 * The handshake message: who a node is and where its chain stands (ADR 0003). Monero's handshake
 * carries a network id and the peer's height, cumulative difficulty and top block id; this one
 * carries the chain identifier and the genesis hash in place of the network id.
 *
 * @param protocolVersion
 *            the version of this protocol the node speaks
 * @param chainIdentifier
 *            the chain the node runs, which has to be the test chain
 * @param genesisHash
 *            the hash of its genesis block
 * @param bestHeight
 *            the height of its tip
 * @param bestHash
 *            the hash of its tip
 * @param work
 *            the cumulative work of its chain ({@link ChainWork})
 * @param listenPort
 *            the port the node listens on, 0 for none; in the anonymity zone, the port of its
 *            onion service
 * @param nodeId
 *            the node's random identity for this run, 0 for a command that is not a node
 * @param onionHost
 *            the node's onion address, announced only inside the anonymity zone (ADR 0004,
 *            step 3); empty everywhere else
 */
public record Hello(int protocolVersion, String chainIdentifier, Bytes genesisHash,
	long bestHeight, Bytes bestHash, BigInteger work, int listenPort, long nodeId,
	String onionHost)
{

	/** The bytes every HELLO starts with */
	public static final String MAGIC = "LETHENON";

	/** The protocol this build speaks */
	public static final int PROTOCOL_VERSION = 3;

	/** A v3 onion host: 56 characters and ".onion" */
	private static final int ONION_HOST_LIMIT = 62;

	private static final byte[] MAGIC_BYTES = MAGIC.getBytes(StandardCharsets.US_ASCII);

	/**
	 * A HELLO's fields, checked before it is sent or read further
	 *
	 * @throws IllegalArgumentException
	 *             for a listening port outside 0 to 65535, or an announced onion host that does not
	 *             end in .onion
	 */
	public Hello
	{
		if (listenPort < 0 || listenPort > 65_535)
		{
			throw new IllegalArgumentException(
				"a listening port is 0 (none) to 65535, not " + listenPort);
		}
		if (!onionHost.isEmpty())
		{
			PeerAddress announced = new PeerAddress(onionHost, Math.max(listenPort, 1));
			if (!announced.isOnion())
			{
				throw new IllegalArgumentException(
					"an announced onion address ends in .onion, and '" + onionHost + "' does not");
			}
		}
	}

	/**
	 * The HELLO of a node that announces no onion address: every clearnet HELLO
	 *
	 * @param protocolVersion
	 *            the protocol the sender speaks
	 * @param chainIdentifier
	 *            the chain it is on
	 * @param genesisHash
	 *            the hash of that chain's genesis block
	 * @param bestHeight
	 *            the height of its best block
	 * @param bestHash
	 *            the hash of its best block
	 * @param work
	 *            the work of its chain up to that block
	 * @param listenPort
	 *            the port it listens on, 0 for none
	 * @param nodeId
	 *            its node identity, 0 for none
	 */
	public Hello(final int protocolVersion, final String chainIdentifier, final Bytes genesisHash,
		final long bestHeight, final Bytes bestHash, final BigInteger work, final int listenPort,
		final long nodeId)
	{
		this(protocolVersion, chainIdentifier, genesisHash, bestHeight, bestHash, work, listenPort,
			nodeId, "");
	}

	/**
	 * The HELLO of a caller that does not listen and has no node identity: a command handing over
	 * a transfer, a node taking its genesis block, a test
	 *
	 * @param protocolVersion
	 *            the protocol the sender speaks
	 * @param chainIdentifier
	 *            the chain it is on
	 * @param genesisHash
	 *            the hash of that chain's genesis block
	 * @param bestHeight
	 *            the height of its best block
	 * @param bestHash
	 *            the hash of its best block
	 * @param work
	 *            the work of its chain up to that block
	 */
	public Hello(final int protocolVersion, final String chainIdentifier, final Bytes genesisHash,
		final long bestHeight, final Bytes bestHash, final BigInteger work)
	{
		this(protocolVersion, chainIdentifier, genesisHash, bestHeight, bestHash, work, 0, 0L);
	}

	/**
	 * The HELLO a node on the given chain sends
	 *
	 * @param chain
	 *            the chain, genesis first, not empty
	 * @return the message
	 */
	public static Hello of(final List<BlockBody> chain)
	{
		return of(chain, 0, 0L);
	}

	/**
	 * The HELLO a node sends: its chain, the port it listens on and its identity, so that a peer
	 * can pass its address on and a node can tell a connection to itself (Monero's
	 * {@code my_port} and {@code peer_id}, {@code src/p2p/p2p_protocol_defs.h:152-155})
	 *
	 * @param chain
	 *            the chain, genesis first, not empty
	 * @param listenPort
	 *            the port the node listens on, 0 for none
	 * @param nodeId
	 *            a random number the node drew at start, 0 for none
	 * @return the message
	 */
	public static Hello of(final List<BlockBody> chain, final int listenPort, final long nodeId)
	{
		BlockBody tip = chain.getLast();
		return new Hello(PROTOCOL_VERSION, chain.getFirst().chainIdentifier(),
			Blocks.hashOf(chain.getFirst()), tip.height(), Blocks.hashOf(tip), ChainWork.of(chain),
			listenPort, nodeId);
	}

	/**
	 * The payload of a HELLO frame
	 *
	 * @return the bytes
	 */
	public byte[] encode()
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(MAGIC_BYTES);
		Wire.writeInt(out, protocolVersion);
		Wire.writeText(out, chainIdentifier);
		Wire.writeBytes(out, genesisHash.toByteArray());
		Wire.writeLong(out, bestHeight);
		Wire.writeBytes(out, bestHash.toByteArray());
		Wire.writeBytes(out, work.toByteArray());
		Wire.writeInt(out, listenPort);
		Wire.writeLong(out, nodeId);
		Wire.writeText(out, onionHost);
		return out.toByteArray();
	}

	/**
	 * Reads the payload of a HELLO frame
	 *
	 * @param payload
	 *            the bytes
	 * @return the message
	 * @throws ProtocolViolation
	 *             when the magic is missing or the bytes do not decode exactly
	 */
	public static Hello decode(final byte[] payload) throws ProtocolViolation
	{
		ByteBuffer in = ByteBuffer.wrap(payload);
		if (payload.length < MAGIC_BYTES.length
			|| !Arrays.equals(Arrays.copyOf(payload, MAGIC_BYTES.length), MAGIC_BYTES))
		{
			throw new ProtocolViolation("a HELLO has to start with " + MAGIC);
		}
		in.position(MAGIC_BYTES.length);
		int version = Wire.readInt(in);
		String chain = Wire.readText(in, 64, "the chain identifier");
		Bytes genesis = Wire.readHash(in, "the genesis hash");
		long height = Wire.readLong(in);
		Bytes best = Wire.readHash(in, "the best hash");
		BigInteger work = new BigInteger(1, Wire.readBytes(in, 64, "the work"));
		int listenPort = Wire.readInt(in);
		long nodeId = Wire.readLong(in);
		// a version-2 HELLO ends here; it is decoded so that the handshake can refuse it by its
		// version rather than as bytes that do not decode
		String onion = in.hasRemaining() ? Wire.readText(in, ONION_HOST_LIMIT, "the onion address")
			: "";
		Wire.requireEnd(in, "a HELLO");
		try
		{
			return new Hello(version, chain, genesis, height, best, work, listenPort, nodeId,
				onion);
		}
		catch (IllegalArgumentException outOfRange)
		{
			throw new ProtocolViolation("a HELLO: " + outOfRange.getMessage());
		}
	}
}
