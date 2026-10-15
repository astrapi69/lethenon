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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.transport.Node;
import io.github.astrapi69.lethenon.transport.PeerAddress;
import io.github.astrapi69.lethenon.transport.Socks5Server;
import io.github.astrapi69.lethenon.transport.TestNetworkSeeds;

/**
 * The test network's seed nodes on the command line (#197). Every test here reaches a seed only
 * through the test SOCKS5 server, which answers for the seed's onion address with a local node;
 * nothing dials the real seed.
 */
class TestNetworkSeedsOnTheCommandLineTest extends AbstractCliTest
{

	@TempDir
	Path directory;

	private String password;

	private String wallet;

	private List<BlockBody> served;

	private Path servedFile;

	@BeforeEach
	void aTestChainOfTwoBlocks() throws Exception
	{
		password = aPassword();
		wallet = directory.resolve("miner.wallet").toString();
		servedFile = directory.resolve("served.lethenon");
		assertEquals(0, run(password, "wallet", "create", "--wallet", wallet), err);
		assertEquals(0, run(password, "mine", "--testnet", "--chain", servedFile.toString(),
			"--wallet", wallet), err);
		assertEquals(0, run(password, "mine", "--chain", servedFile.toString(), "--wallet",
			wallet), err);
		served = new ChainFile(servedFile).require();
	}

	@Test
	@DisplayName("without --peer, a node with --proxy dials the seed's onion address and takes its chain")
	void withoutAPeer_aNodeDialsTheSeedThroughTheProxy() throws Exception
	{
		Path empty = directory.resolve("empty.lethenon");
		PeerAddress seed = TestNetworkSeeds.ONION.getFirst();
		try (Socks5Server socks = Socks5Server.start(); Node stand = Node.on(served))
		{
			socks.route(seed.host(), seed.port(), stand.listen(0));

			assertEquals(0, run("", "node", "--chain", empty.toString(), "--listen", "0",
				"--proxy", socks.address().toString(), "--for", "2"), err);

			assertTrue(socks.requests().stream().anyMatch(
				request -> request.host().equals(seed.host()) && request.port() == seed.port()),
				socks.requests().toString());
		}
		assertEquals(served, new ChainFile(empty).require());
		assertTrue(out.contains("default seed node"), out);
	}

	@Test
	@DisplayName("a node on a chain of its own dials no seed")
	void aChainOfItsOwn_dialsNoSeed() throws Exception
	{
		try (Socks5Server socks = Socks5Server.start())
		{
			assertEquals(0, run("", "node", "--chain", servedFile.toString(), "--listen", "0",
				"--proxy", socks.address().toString(), "--for", "2"), err);

			assertEquals(List.of(), socks.requests());
		}
	}

	@Test
	@DisplayName("--no-default-peers: an empty file without --peer or --mine is refused as before")
	void noDefaultPeers_leavesAnEmptyFileWithNothingToStartFrom()
	{
		Path empty = directory.resolve("empty.lethenon");

		assertEquals(1, run("", "node", "--chain", empty.toString(), "--listen", "0",
			"--no-default-peers", "--for", "1"), out);

		assertTrue(err.contains("--peer") && err.contains("--mine"), err);
	}

	@Test
	@DisplayName("--mine on an empty file with a peer takes the peer's genesis instead of mining a new one")
	void mineOnAnEmptyFileWithAPeer_takesThePeersGenesis() throws Exception
	{
		Path joining = directory.resolve("joining.lethenon");
		try (Node peer = Node.on(served))
		{
			int port = peer.listen(0);

			assertEquals(0, run(password, "node", "--chain", joining.toString(), "--listen", "0",
				"--peer", "127.0.0.1:" + port, "--mine", "--wallet", wallet, "--for", "2"), err);
		}

		assertEquals(served.getFirst(), new ChainFile(joining).require().getFirst());
	}
}
