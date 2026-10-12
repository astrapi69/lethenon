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

import static io.github.astrapi69.lethenon.transport.Networks.await;
import static io.github.astrapi69.lethenon.transport.Networks.extended;
import static io.github.astrapi69.lethenon.transport.Networks.testGenesis;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.astrapi69.lethenon.Amount;
import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.Destination;
import io.github.astrapi69.lethenon.SignatureSuite;
import io.github.astrapi69.lethenon.SignedTransaction;
import io.github.astrapi69.lethenon.TransactionBody;
import io.github.astrapi69.lethenon.Wallet;

/**
 * Every outgoing connection goes through a SOCKS5 proxy when one is given, with the host left for
 * the proxy to resolve (ADR 0004, step 1, #114). The proxy here is {@link Socks5Server}, which
 * stands in for a Tor daemon and records what reached it.
 */
class ProxyTest
{

	/** A v3 onion address: 56 base32 characters */
	static final String ONION = "lethenonlethenonlethenonlethenonlethenonlethenonlethenon.onion";

	/** A name that resolves nowhere (RFC 2606): only a proxy that is told the route reaches it */
	private static final String NOWHERE = "peer.invalid";

	private static final Bytes MINER = Bytes.of(new byte[] { 9 });

	private final Wallet holder = Wallet.create();

	private final Bytes account = holder.spendKey(SignatureSuite.ED25519);

	private final List<BlockBody> genesis = testGenesis(account);

	private final List<BlockBody> ahead = extended(extended(genesis, MINER, List.of()), MINER,
		List.of());

	@TempDir
	Path directory;

	@Test
	@DisplayName("a node reaches a peer through the proxy, and the host arrives there unresolved")
	void aNode_reachesAPeerThroughTheProxy_withTheHostUnresolved() throws IOException
	{
		try (Socks5Server socks = Socks5Server.start(); Node peer = Node.on(ahead);
			Node node = Node.on(genesis).dialingThrough(Outbound.through(socks.address())))
		{
			socks.route(NOWHERE, 18480, peer.listen(0));

			node.connect(NOWHERE, 18480);

			await("the chain through the proxy", () -> node.chain().equals(ahead));
			assertEquals(List.of(new Socks5Server.Request(Socks5Server.DOMAIN_NAME, NOWHERE, 18480)),
				socks.requests());
		}
	}

	@Test
	@DisplayName("a name the local resolver knows is still handed to the proxy unresolved: no DNS leak")
	void aResolvableName_reachesTheProxyUnresolved() throws IOException
	{
		try (Socks5Server socks = Socks5Server.start(); Node peer = Node.on(ahead);
			Node node = Node.on(genesis).dialingThrough(Outbound.through(socks.address())))
		{
			int port = peer.listen(0);
			socks.route("localhost", port, port);

			node.connect("localhost", port);

			await("the chain through the proxy", () -> node.chain().equals(ahead));
			assertEquals(List.of(new Socks5Server.Request(Socks5Server.DOMAIN_NAME, "localhost",
				port)), socks.requests(), "resolved locally, it would arrive as an IP address");
		}
	}

	@Test
	@DisplayName("an onion peer is reached through the proxy")
	void anOnionPeer_isReachedThroughTheProxy() throws IOException
	{
		try (Socks5Server socks = Socks5Server.start(); Node peer = Node.on(ahead);
			Node node = Node.on(genesis).dialingThrough(Outbound.through(socks.address())))
		{
			socks.route(ONION, 18480, peer.listen(0));

			node.connectAll(List.of(PeerAddress.parse(ONION + ":18480")));

			await("the chain from the onion peer", () -> node.chain().equals(ahead));
			assertEquals(Socks5Server.DOMAIN_NAME, socks.requests().getFirst().addressType());
		}
	}

	@Test
	@DisplayName("with a proxy, a node does not reach a peer the proxy cannot reach, even a local one")
	void withAProxy_nothingIsDialledAroundIt() throws IOException
	{
		try (Socks5Server socks = Socks5Server.start(); Node peer = Node.on(ahead);
			Node node = Node.on(genesis).dialingThrough(Outbound.through(socks.address())))
		{
			int port = peer.listen(0);

			assertThrows(IOException.class, () -> node.connect("127.0.0.1", port));

			List<Socks5Server.Request> requests = socks.requests();
			assertEquals(1, requests.size(), "the attempt went to the proxy, which had no route");
			assertEquals("127.0.0.1", requests.getFirst().host());
			assertEquals(port, requests.getFirst().port());
			assertEquals(genesis, node.chain());
		}
	}

	@Test
	@DisplayName("without a proxy, an onion address is refused before anything touches the network")
	void withoutAProxy_anOnionAddressIsRefused()
	{
		IOException refused = assertThrows(IOException.class,
			() -> Outbound.DIRECT.open(PeerAddress.parse(ONION + ":18480"), 1_000));

		assertTrue(refused.getMessage().contains("Tor") && refused.getMessage().contains("SOCKS proxy"),
			refused.getMessage());
		assertFalse(refused.getMessage().contains("--"),
			"a library caller has no command line option to give (#135): " + refused.getMessage());
	}

	@Test
	@DisplayName("a sync goes through the proxy, the genesis block of an empty file included")
	void aSync_goesThroughTheProxy() throws IOException
	{
		ChainFile file = new ChainFile(directory.resolve("synced.lethenon"));
		try (Socks5Server socks = Socks5Server.start(); Node peer = Node.on(ahead))
		{
			socks.route(ONION, 18480, peer.listen(0));

			Sync.Synced synced = Sync.once(file, PeerAddress.parse(ONION + ":18480"),
				Duration.ofSeconds(20), Outbound.through(socks.address()));

			assertEquals(ahead, file.require());
			assertEquals(ahead.size(), synced.blocksAfter());
			assertEquals(2, socks.requests().size(), "one for the genesis block, one for the rest");
		}
	}

	@Test
	@DisplayName("a transfer is handed to a node through the proxy")
	void aTransfer_isHandedOverThroughTheProxy() throws IOException
	{
		SignedTransaction transfer = holder.sign(new TransactionBody(Chain.TEST_IDENTIFIER, 0L,
			account, Destination.direct(Bytes.of(new byte[] { 7 })), Amount.ofLeth(3L), Amount.ZERO,
			"through Tor"), SignatureSuite.ED25519);
		try (Socks5Server socks = Socks5Server.start(); Node node = Node.on(genesis))
		{
			socks.route(ONION, 18480, node.listen(0));

			Handover.send(PeerAddress.parse(ONION + ":18480"), genesis, transfer,
				Outbound.through(socks.address()));

			await("the transfer in the pool", () -> node.pending().equals(List.of(transfer)));
		}
	}

	@Test
	@DisplayName("a node that dials through a proxy listens on loopback only")
	void aNodeWithAProxy_listensOnLoopback() throws IOException
	{
		try (Node node = Node.on(genesis)
			.dialingThrough(Outbound.through(new PeerAddress("127.0.0.1", 9050))))
		{
			int port = node.listen(0);

			assertTrue(node.listeningOn().isLoopbackAddress(), node.listeningOn().toString());
			try (Socket local = new Socket(InetAddress.getLoopbackAddress(), port))
			{
				assertTrue(local.isConnected());
			}
		}
	}

	@Test
	@DisplayName("a node without a proxy listens on every interface, as before")
	void aNodeWithoutAProxy_listensOnEveryInterface() throws IOException
	{
		try (Node node = Node.on(genesis))
		{
			node.listen(0);

			assertTrue(node.listeningOn().isAnyLocalAddress(), node.listeningOn().toString());
		}
	}

	@Test
	@DisplayName("an address to bind to overrides the default")
	void anAddressToBindTo_overridesTheDefault() throws IOException
	{
		try (Node node = Node.on(genesis))
		{
			node.listen(InetAddress.getLoopbackAddress(), 0);

			assertTrue(node.listeningOn().isLoopbackAddress());
			assertFalse(node.listeningOn().isAnyLocalAddress());
		}
	}

	@Test
	@DisplayName("the proxy names itself in the description of the outbound route")
	void theOutboundRoute_namesTheProxy()
	{
		assertEquals("direct", Outbound.DIRECT.toString());
		assertEquals("through the SOCKS5 proxy at 127.0.0.1:9050",
			Outbound.through(new PeerAddress("127.0.0.1", 9050)).toString());
	}
}
