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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.ChainFixtures;
import io.github.astrapi69.lethenon.Genesis;

/**
 * The test network's seed nodes in the code (#197): which addresses a node dials, and for which
 * chain. The genesis block is the test network's own, taken from seed node 1's chain
 * ({@code src/test/resources/chain/README.md}).
 */
class TestNetworkSeedsTest
{


	@Test
	@DisplayName("the genesis hash in the code is the hash of the test network's genesis block")
	void theGenesisHash_isTheTestNetworksGenesis()
	{
		assertEquals(TestNetworkSeeds.GENESIS_HASH, Blocks.hashOf(ChainFixtures.testNetworkGenesis().getFirst()));
	}

	@Test
	@DisplayName("the seeds serve an empty chain file and the test network's chain, not a chain of its own")
	void theSeeds_serveTheTestNetworkOnly()
	{
		assertTrue(TestNetworkSeeds.serve(List.of()));
		assertTrue(TestNetworkSeeds.serve(ChainFixtures.testNetworkGenesis()));
		assertFalse(TestNetworkSeeds.serve(
			List.of(Genesis.start(Chain.TEST_IDENTIFIER, "a chain of its own", 0L))));
	}

	@Test
	@DisplayName("without Tor the seeds' IP addresses, with Tor their onion addresses, never a local one")
	void theRoute_choosesTheAddresses()
	{
		assertEquals(TestNetworkSeeds.CLEARNET, TestNetworkSeeds.reachedThrough(false));
		assertEquals(TestNetworkSeeds.ONION, TestNetworkSeeds.reachedThrough(true));
		assertFalse(TestNetworkSeeds.CLEARNET.isEmpty());
		assertFalse(TestNetworkSeeds.ONION.isEmpty());
		assertTrue(TestNetworkSeeds.CLEARNET.stream()
			.noneMatch(address -> address.isOnion() || address.isLocal()));
		assertTrue(TestNetworkSeeds.ONION.stream().allMatch(PeerAddress::isOnion));
	}
}
