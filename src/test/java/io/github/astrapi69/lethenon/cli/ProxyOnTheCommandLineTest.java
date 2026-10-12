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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.transport.Node;
import io.github.astrapi69.lethenon.transport.Socks5Server;

/**
 * --proxy and --bind on the command line: node and sync reach their peers through a SOCKS5 proxy,
 * and a node with a proxy listens on loopback (ADR 0004, step 1, #114)
 */
class ProxyOnTheCommandLineTest extends AbstractCliTest
{

	private static final String ONION =
		"lethenonlethenonlethenonlethenonlethenonlethenonlethenon.onion";

	@TempDir
	Path directory;

	private List<BlockBody> served;

	private Path servedFile;

	@BeforeEach
	void aTestChainOfTwoBlocks() throws Exception
	{
		String password = aPassword();
		String wallet = directory.resolve("miner.wallet").toString();
		servedFile = directory.resolve("served.lethenon");
		assertEquals(0, run(password, "wallet", "create", "--wallet", wallet), err);
		assertEquals(0, run(password, "mine", "--testnet", "--chain", servedFile.toString(),
			"--wallet", wallet), err);
		assertEquals(0, run(password, "mine", "--chain", servedFile.toString(), "--wallet",
			wallet), err);
		served = new ChainFile(servedFile).require();
	}

	@Test
	@DisplayName("sync --proxy takes an onion node's chain through the proxy")
	void syncWithAProxy_reachesAnOnionNode() throws Exception
	{
		Path target = directory.resolve("synced.lethenon");
		try (Socks5Server socks = Socks5Server.start(); Node node = Node.on(served))
		{
			socks.route(ONION, 18480, node.listen(0));

			assertEquals(0, run("", "sync", "--chain", target.toString(), "--peer",
				ONION + ":18480", "--proxy", socks.address().toString()), err);

			assertFalse(socks.requests().isEmpty());
		}
		assertEquals(served, new ChainFile(target).require());
	}

	@Test
	@DisplayName("node --proxy takes an onion peer's chain and listens on loopback")
	void nodeWithAProxy_reachesAnOnionPeerAndListensOnLoopback() throws Exception
	{
		Path empty = directory.resolve("empty.lethenon");
		try (Socks5Server socks = Socks5Server.start(); Node peer = Node.on(served))
		{
			socks.route(ONION, 18480, peer.listen(0));

			assertEquals(0, run("", "node", "--chain", empty.toString(), "--listen", "0",
				"--peer", ONION + ":18480", "--proxy", socks.address().toString(), "--for", "2"),
				err);
		}

		assertEquals(served, new ChainFile(empty).require());
		assertTrue(out.contains("listening on 127.0.0.1 port"), out);
		assertTrue(out.contains("through the SOCKS5 proxy at"), out);
	}

	@Test
	@DisplayName("node --bind listens on the given address")
	void nodeWithBind_listensThere()
	{
		assertEquals(0, run("", "node", "--chain", servedFile.toString(), "--listen", "0",
			"--bind", "127.0.0.1", "--for", "1"), err);

		assertTrue(out.contains("listening on 127.0.0.1 port"), out);
	}

	@Test
	@DisplayName("node refuses an onion peer it has no proxy for, and names the options")
	void aNodeWithAnOnionPeerAndNoProxy_isRefused()
	{
		assertEquals(1, run("", "node", "--chain", servedFile.toString(), "--listen", "0",
			"--peer", ONION + ":18480", "--for", "1"), out);

		assertTrue(err.contains("--proxy") && err.contains("--tx-proxy"), err);
	}

	@Test
	@DisplayName("an onion peer without --proxy is refused with the reason, and nothing is written")
	void anOnionPeerWithoutAProxy_isRefused() throws Exception
	{
		Path empty = directory.resolve("never.lethenon");

		assertEquals(1, run("", "sync", "--chain", empty.toString(), "--peer", ONION + ":18480"));

		assertTrue(err.contains("--proxy"), err);
		assertFalse(Files.exists(empty));
	}
}
