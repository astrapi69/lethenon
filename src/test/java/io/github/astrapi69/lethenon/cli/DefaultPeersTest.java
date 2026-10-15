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
package io.github.astrapi69.lethenon.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.ChainFixtures;
import io.github.astrapi69.lethenon.Genesis;
import io.github.astrapi69.lethenon.transport.PeerAddress;
import io.github.astrapi69.lethenon.transport.TestNetworkSeeds;

/**
 * Which peers {@code node} dials (#197): the ones given with --peer, otherwise the test network's
 * seed nodes - by IP address without a proxy, by onion address with one - unless
 * --no-default-peers says no, or the chain file holds a chain of its own
 */
class DefaultPeersTest
{

	private static final PeerAddress GIVEN = new PeerAddress("198.51.100.7", 18480);

	/** The chain file a node starts with */
	enum Start
	{
		EMPTY, TEST_NETWORK, OWN_CHAIN
	}

	/** A start of the node, and the peers it dials */
	record Case(String name, List<PeerAddress> given, boolean noDefaultPeers, boolean throughTor,
		Start start, List<PeerAddress> expected)
	{
		@Override
		public String toString()
		{
			return name;
		}
	}

	static Stream<Case> cases()
	{
		List<PeerAddress> none = List.of();
		return Stream.of(
			new Case("no --peer, an empty file, no proxy: the seeds' IP addresses", none, false,
				false, Start.EMPTY, TestNetworkSeeds.CLEARNET),
			new Case("no --peer, an empty file, --proxy: the seeds' onion addresses", none, false,
				true, Start.EMPTY, TestNetworkSeeds.ONION),
			new Case("no --peer, the test network's chain: the seeds", none, false, false,
				Start.TEST_NETWORK, TestNetworkSeeds.CLEARNET),
			new Case("--no-default-peers: none", none, true, false, Start.EMPTY, none),
			new Case("--no-default-peers with --proxy: none", none, true, true, Start.EMPTY, none),
			new Case("--peer: that peer and no seed", List.of(GIVEN), false, false, Start.EMPTY,
				List.of(GIVEN)),
			new Case("--peer with --proxy: that peer and no seed", List.of(GIVEN), false, true,
				Start.EMPTY, List.of(GIVEN)),
			new Case("no --peer, a chain of its own: no seed", none, false, false, Start.OWN_CHAIN,
				none),
			new Case("no --peer, a chain of its own, --proxy: no seed", none, false, true,
				Start.OWN_CHAIN, none));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("cases")
	void aNode_dialsThePeersItShould(final Case start)
	{
		assertEquals(start.expected(), NodeCommand.peersToDial(start.given(),
			start.noDefaultPeers(), start.throughTor(), chainOf(start.start())));
	}

	private static List<BlockBody> chainOf(final Start start)
	{
		return switch (start)
		{
			case EMPTY -> List.of();
			case OWN_CHAIN -> List.of(Genesis.start(Chain.TEST_IDENTIFIER, "a chain of its own", 0L));
			case TEST_NETWORK -> ChainFixtures.testNetworkGenesis();
		};
	}

}
