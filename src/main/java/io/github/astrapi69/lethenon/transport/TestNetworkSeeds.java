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

import java.util.List;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.Bytes;

/**
 * The seed nodes of the test network {@code lethenon-test-2} (#197): the addresses a node dials
 * when it is given no peer, so that joining the test network needs no address. Seed node 1 runs
 * since 2026-10-15 (docs/launch/infrastructure.md). The list belongs to one chain, named by its
 * genesis hash; when the test network is started anew, the hash and the list change together.
 */
public final class TestNetworkSeeds
{

	/** The genesis hash of the chain the seeds serve, as seed node 1 announces it in its HELLO */
	public static final Bytes GENESIS_HASH = Bytes
		.ofHex("0093b8db1a0cf9be2d5203659d80b42f105d2e322a1850f13da26579df411d6e");

	/** The seeds' IP addresses */
	public static final List<PeerAddress> CLEARNET = List
		.of(new PeerAddress("185.112.144.29", 18480));

	/** The seeds' onion addresses */
	public static final List<PeerAddress> ONION = List.of(
		new PeerAddress("qbegibjyc5r4o2rhzsqekgv52f7wft43wvgpyyhexn2g5seaapysyxid.onion", 18480));

	private TestNetworkSeeds()
	{
	}

	/**
	 * The addresses a node dials: with every connection through Tor, the onion addresses, so that
	 * the node stays inside Tor and needs no exit relay; otherwise the IP addresses, because an
	 * onion address is reached only through a proxy (ADR 0004)
	 *
	 * @param tor
	 *            whether every outgoing connection goes through Tor's SOCKS proxy
	 * @return the addresses
	 */
	public static List<PeerAddress> reachedThrough(final boolean tor)
	{
		return tor ? ONION : CLEARNET;
	}

	/**
	 * Whether the seeds serve this chain: an empty one, which takes its genesis from a seed, or the
	 * test network's. A chain of its own would be refused by them in the handshake, and dialling
	 * them would only tell them that it exists.
	 *
	 * @param chain
	 *            the chain a node starts with, empty for an empty chain file
	 * @return true when the node may dial the seeds
	 */
	public static boolean serve(final List<BlockBody> chain)
	{
		return chain.isEmpty() || GENESIS_HASH.equals(Blocks.hashOf(chain.getFirst()));
	}
}
