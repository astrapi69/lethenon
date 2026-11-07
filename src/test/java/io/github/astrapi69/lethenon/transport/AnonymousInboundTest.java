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
import static io.github.astrapi69.lethenon.transport.Networks.testGenesis;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Bytes;

/**
 * A node reached through its onion service (ADR 0004, step 3, #120): its peers there belong to the
 * anonymity zone, its onion address travels only inside the zone, and zone peers pass onion
 * addresses on among themselves
 */
class AnonymousInboundTest
{

	private static final PeerAddress ONION_A = PeerAddress.parse(
		"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion:18484");

	private static final PeerAddress ONION_B = PeerAddress.parse(
		"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.onion:18484");

	private final List<BlockBody> genesis = testGenesis(Bytes.of(new byte[] { 1 }));

	/**
	 * A node with an onion service, reached through the given SOCKS server and through every other
	 * one given
	 */
	private Node onionNode(final Socks5Server socks, final PeerAddress onion,
		final Socks5Server... alsoRoutedBy) throws IOException
	{
		Node node = Node.on(genesis).anonymityZone(Outbound.through(socks.address()), 10);
		int port = node.listenAnonymously(onion, 0, 16);
		socks.route(onion.host(), onion.port(), port);
		for (Socks5Server other : alsoRoutedBy)
		{
			other.route(onion.host(), onion.port(), port);
		}
		return node;
	}

	@Test
	@DisplayName("a connection through the onion service is a zone peer on both ends")
	void aConnectionThroughTheOnionService_isAZonePeerOnBothEnds() throws IOException
	{
		try (Socks5Server socks = Socks5Server.start(); Node b = onionNode(socks, ONION_B);
			Node a = Node.on(genesis).anonymityZone(Outbound.through(socks.address()), 10))
		{
			a.connectAll(List.of(ONION_B));

			await("zone peers on both ends",
				() -> a.anonymousPeers().size() == 1 && b.anonymousPeers().size() == 1);
			assertEquals(List.of(), a.peers());
			assertEquals(List.of(), b.peers(), "an onion connection is not a clearnet peer");
		}
	}

	@Test
	@DisplayName("the zone HELLO names the onion address, the clearnet HELLO never does")
	void theOnionAddress_travelsOnlyInTheZone() throws IOException
	{
		try (Socks5Server socks = Socks5Server.start(); Node b = onionNode(socks, ONION_B);
			Node a = Node.on(genesis).anonymityZone(Outbound.through(socks.address()), 10))
		{
			a.connectAll(List.of(ONION_B));
			await("the zone peer", () -> a.anonymousPeers().size() == 1);

			Hello zone = a.anonymousPeers().getFirst();
			assertEquals(ONION_B.host(), zone.onionHost());
			assertEquals(ONION_B.port(), zone.listenPort());
			Hello clearnet = clearnetHelloOf(b);
			assertEquals("", clearnet.onionHost());
		}
	}

	@Test
	@DisplayName("a third node finds a second onion node through the first, inside the zone")
	void onionAddresses_arePassedOnInsideTheZone() throws IOException
	{
		try (Socks5Server socks = Socks5Server.start(); Node a = onionNode(socks, ONION_A);
			Node b = onionNode(socks, ONION_B);
			Node c = Node.on(genesis).anonymityZone(Outbound.through(socks.address()), 10))
		{
			a.connectAll(List.of(ONION_B));
			await("a and b in the zone", () -> b.anonymousPeers().size() == 1);

			c.connectAll(List.of(ONION_B));

			await("c connected to a as well", () -> c.anonymousPeers().size() == 2);
			assertTrue(c.anonymousPeers().stream()
				.anyMatch(hello -> ONION_A.host().equals(hello.onionHost())));
		}
	}

	/**
	 * The silent address is a real onion node for b and a, and a mute listener for c alone: b
	 * keeps it confirmed and passes it on for as long as the test runs. Up to 2026-11 b learnt it
	 * from a handshake that left at once, dialled it itself, and forgot it when its own handshake
	 * timed out: a c that asked b more than {@link Node#HANDSHAKE_MILLIS} later never heard of it,
	 * filled its slot with a, and the test failed with no refusal (run 41783361755)
	 */
	@Test
	@DisplayName("a learnt onion address that never says HELLO gives its zone slot back")
	void aSilentOnionAddress_givesItsZoneSlotBack() throws Exception
	{
		PeerAddress silentOnion = PeerAddress.parse(
			"ssssssssssssssssssssssssssssssssssssssssssssssssssssssss.onion:18484");
		List<Socket> held = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (Socks5Server socks = Socks5Server.start(); Socks5Server routeOfC = Socks5Server.start();
			Node b = onionNode(socks, ONION_B, routeOfC);
			Node speaking = onionNode(socks, silentOnion);
			java.net.ServerSocket silent = new java.net.ServerSocket(0))
		{
			Thread.ofVirtual().start(() -> {
				while (!silent.isClosed())
				{
					try
					{
						held.add(silent.accept());
					}
					catch (IOException closed)
					{
						return;
					}
				}
			});
			routeOfC.route(silentOnion.host(), silentOnion.port(), silent.getLocalPort());
			speaking.connectAll(List.of(ONION_B));
			await("the silent address confirmed at b", () -> b.anonymousPeers().size() == 1);
			try (Node a = onionNode(socks, ONION_A, routeOfC);
				Node c = Node.on(genesis).anonymityZone(Outbound.through(routeOfC.address()), 2))
			{
				a.connectAll(List.of(ONION_B));
				await("a at b",
					() -> b.anonymousPeers().size() >= 2 && a.anonymousPeers().size() >= 1);

				c.connectAll(List.of(ONION_B));

				await("c dialled the silent onion address", () -> !held.isEmpty());
				long deadline = System.currentTimeMillis() + 2L * Node.HANDSHAKE_MILLIS + 5_000L;
				while (c.anonymousPeers().size() < 2 && System.currentTimeMillis() < deadline)
				{
					Thread.sleep(20L);
				}
				assertEquals(2, c.anonymousPeers().size(), "c: " + c.refusals());
				assertEquals(1, c.refusals().stream()
					.filter(reason -> reason.contains("ended the handshake")).count(),
					"the silent address is forgotten after its handshake timed out, not dialled "
						+ "again: " + c.refusals());
				assertTrue(c.anonymousPeers().stream()
					.anyMatch(hello -> ONION_A.host().equals(hello.onionHost())));
			}
		}
		finally
		{
			for (Socket socket : held)
			{
				socket.close();
			}
		}
	}

	/**
	 * A node that does not discover peers stays with its zone peers as well: it hears of the onion
	 * addresses they pass on and dials none of them, whichever of the two it is told first. Up to
	 * 2026-11 the flag reached the clearnet only
	 */
	@ParameterizedTest(name = "discovery switched off {argumentSetName}")
	@MethodSource("bothOrders")
	void aNodeThatDoesNotDiscover_dialsNoLearntOnionAddress(final boolean beforeTheZone)
		throws Exception
	{
		try (Socks5Server socks = Socks5Server.start(); Socks5Server routeOfC = Socks5Server.start();
			Node a = onionNode(socks, ONION_A, routeOfC);
			Node b = onionNode(socks, ONION_B, routeOfC);
			Node c = notDiscovering(routeOfC, beforeTheZone))
		{
			a.connectAll(List.of(ONION_B));
			await("a at b", () -> b.anonymousPeers().size() == 1);

			c.connectAll(List.of(ONION_B));

			await("c heard of a from b, or dialled it",
				() -> c.anonymousHeardOf().contains(ONION_A) || c.anonymousPeers().size() > 1);
			// a dial would start on a thread of its own after the address is heard of; through the
			// local SOCKS stub it opens within milliseconds, so a second is room enough to see one
			Thread.sleep(1_000L);
			assertEquals(1, c.anonymousPeers().size(), "c: " + c.refusals());
			assertEquals(List.of(ONION_B.host()),
				routeOfC.requests().stream().map(Socks5Server.Request::host).toList(),
				"c opened a connection to b alone");
		}
	}

	static Stream<Arguments> bothOrders()
	{
		return Stream.of(Arguments.argumentSet("before the zone, as the command line does", true),
			Arguments.argumentSet("after the zone", false));
	}

	private Node notDiscovering(final Socks5Server route, final boolean beforeTheZone)
	{
		Node node = Node.on(genesis);
		if (beforeTheZone)
		{
			return node.discoverPeers(false).anonymityZone(Outbound.through(route.address()), 10);
		}
		return node.anonymityZone(Outbound.through(route.address()), 10).discoverPeers(false);
	}

	@Test
	@DisplayName("no onion address reaches a clearnet PEERS answer")
	void noOnionAddress_reachesTheClearnet() throws IOException
	{
		try (Socks5Server socks = Socks5Server.start(); Node b = onionNode(socks, ONION_B);
			Node a = onionNode(socks, ONION_A))
		{
			a.connectAll(List.of(ONION_B));
			await("a and b in the zone", () -> b.anonymousPeers().size() == 1);
			int clearnetPort = b.listen(0);

			try (Socket socket = new Socket("127.0.0.1", clearnetPort))
			{
				socket.setSoTimeout(10_000);
				DataInputStream in = new DataInputStream(socket.getInputStream());
				DataOutputStream out = new DataOutputStream(socket.getOutputStream());
				Frames.read(in, Frames.MAXIMUM_FRAME);
				Frames.write(out, new Frame(MessageType.HELLO,
					Hello.of(genesis, 18999, 77L).encode()));
				Frames.write(out, new Frame(MessageType.GET_PEERS, new byte[0]));
				Frame answer = Frames.read(in, Frames.MAXIMUM_FRAME);
				while (answer.type() != MessageType.PEERS)
				{
					answer = Frames.read(in, Frames.MAXIMUM_FRAME);
				}
				List<PeerAddress> passedOn = PeerList.decode(answer.payload()).addresses();
				assertFalse(passedOn.stream().anyMatch(PeerAddress::isOnion), passedOn.toString());
			}
		}
	}

	@Test
	@DisplayName("a clearnet HELLO that carries an onion address is refused")
	void aClearnetHelloWithAnOnionAddress_isRefused() throws IOException
	{
		try (Node node = Node.on(genesis);
			Socket socket = new Socket("127.0.0.1", node.listen(0)))
		{
			socket.setSoTimeout(10_000);
			DataInputStream in = new DataInputStream(socket.getInputStream());
			Frames.read(in, Frames.MAXIMUM_FRAME);
			Hello plain = Hello.of(genesis, 18999, 77L);
			Frames.write(new DataOutputStream(socket.getOutputStream()),
				new Frame(MessageType.HELLO, new Hello(plain.protocolVersion(),
					plain.chainIdentifier(), plain.genesisHash(), plain.bestHeight(),
					plain.bestHash(), plain.work(), plain.listenPort(), plain.nodeId(),
					ONION_A.host()).encode()));

			assertThrows(EOFException.class, () -> Frames.read(in, Frames.MAXIMUM_FRAME));
			await("the reason", () -> node.refusals().stream()
				.anyMatch(reason -> reason.contains("onion address")));
		}
	}

	@Test
	@DisplayName("a HELLO of protocol version 2 is refused with its version")
	void aVersionTwoHello_isRefused() throws IOException
	{
		try (Node node = Node.on(genesis);
			Socket socket = new Socket("127.0.0.1", node.listen(0)))
		{
			socket.setSoTimeout(10_000);
			DataInputStream in = new DataInputStream(socket.getInputStream());
			Frames.read(in, Frames.MAXIMUM_FRAME);
			Hello plain = Hello.of(genesis, 18999, 77L);
			byte[] withAnEmptyOnionField = new Hello(2, plain.chainIdentifier(),
				plain.genesisHash(), plain.bestHeight(), plain.bestHash(), plain.work(),
				plain.listenPort(), plain.nodeId()).encode();
			// version 2 ends after the node identity: drop the empty onion field's length prefix
			byte[] versionTwo = Arrays.copyOf(withAnEmptyOnionField,
				withAnEmptyOnionField.length - Integer.BYTES);
			Frames.write(new DataOutputStream(socket.getOutputStream()),
				new Frame(MessageType.HELLO, versionTwo));

			assertThrows(EOFException.class, () -> Frames.read(in, Frames.MAXIMUM_FRAME));
			await("the reason", () -> node.refusals().stream()
				.anyMatch(reason -> reason.contains("version 2")));
		}
	}

	@Test
	@DisplayName("the onion service's listener binds loopback only")
	void theOnionListener_bindsLoopback() throws IOException
	{
		try (Socks5Server socks = Socks5Server.start(); Node b = onionNode(socks, ONION_B))
		{
			assertTrue(b.anonymouslyListeningOn().isLoopbackAddress());
		}
	}

	@Test
	@DisplayName("an onion service without an anonymity zone is refused")
	void anOnionServiceWithoutAZone_isRefused()
	{
		try (Node node = Node.on(genesis))
		{
			IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> node.listenAnonymously(ONION_B, 0, 16));

			assertTrue(refused.getMessage().contains("--tx-proxy"), refused.getMessage());
		}
	}

	private Hello clearnetHelloOf(final Node node) throws IOException
	{
		try (Socket socket = new Socket("127.0.0.1", node.listen(0)))
		{
			socket.setSoTimeout(10_000);
			return Hello.decode(
				Frames.read(new DataInputStream(socket.getInputStream()), Frames.MAXIMUM_FRAME)
					.payload());
		}
	}
}
