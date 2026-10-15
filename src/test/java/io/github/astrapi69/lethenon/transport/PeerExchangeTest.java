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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.astrapi69.lethenon.Amount;
import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.Destination;
import io.github.astrapi69.lethenon.SignatureSuite;
import io.github.astrapi69.lethenon.TransactionBody;
import io.github.astrapi69.lethenon.Wallet;

/**
 * Peer exchange after Monero's handshake (ADR 0003): a node passes on the addresses of nodes it
 * has itself been connected to, learns addresses from its peers, and connects to them while it
 * has room
 */
class PeerExchangeTest
{

	private final Wallet holder = Wallet.create();

	private final List<BlockBody> genesis = testGenesis(holder.spendKey(SignatureSuite.ED25519));

	@Test
	@DisplayName("a HELLO carries the listening port and the node identity")
	void aHello_carriesThePortAndTheIdentity() throws ProtocolViolation
	{
		Hello hello = Hello.of(genesis, 18431, 42L);

		assertEquals(hello, Hello.decode(hello.encode()));
		assertEquals(3, Hello.PROTOCOL_VERSION);
	}

	@Test
	@DisplayName("two nodes that know only a third find each other through it")
	void twoNodesThatKnowOnlyAThird_findEachOther() throws IOException
	{
		try (Node hub = Node.on(genesis).keepingLocalAddresses();
			Node first = Node.on(genesis).keepingLocalAddresses();
			Node second = Node.on(genesis).keepingLocalAddresses())
		{
			int hubPort = hub.listen(0);
			first.listen(0);
			second.listen(0);
			first.connect("127.0.0.1", hubPort);
			await("first at the hub", () -> hub.peers().size() == 1);

			second.connect("127.0.0.1", hubPort);

			await("the second connected to the first as well",
				() -> first.peers().size() == 2 && second.peers().size() == 2);
		}
	}

	@Test
	@DisplayName("a node that is told its own address connects to it once, and never again")
	void aNodeToldItsOwnAddress_doesNotConnectToItself() throws IOException
	{
		try (Node node = Node.on(genesis))
		{
			int port = node.listen(0);

			node.connect("127.0.0.1", port);

			await("the refusal", () -> node.refusals().stream()
				.anyMatch(reason -> reason.contains("this node itself")));
			assertEquals(List.of(), node.peers());
		}
	}

	@Test
	@DisplayName("a command that handed over a transfer is never passed on as a peer")
	void aCommandThatHandedOverATransfer_isNeverPassedOn() throws IOException
	{
		try (Node node = Node.on(genesis).keepingLocalAddresses(); Node listening = Node.on(genesis))
		{
			int port = node.listen(0);
			int listeningPort = listening.listen(0);
			listening.connect("127.0.0.1", port);
			Handover.send(new PeerAddress("127.0.0.1", port), genesis, holder.sign(
				new TransactionBody(Chain.TEST_IDENTIFIER, 0L, holder.spendKey(
					SignatureSuite.ED25519), Destination.direct(Bytes.of(new byte[] { 7 })),
					Amount.ofLeth(1L), Amount.ZERO, ""), SignatureSuite.ED25519));
			await("the transfer", () -> node.pending().size() == 1);

			List<PeerAddress> passedOn = askForPeers(port);

			assertEquals(List.of(new PeerAddress("127.0.0.1", listeningPort)), passedOn);
		}
	}

	@Test
	@DisplayName("a node keeps at most twelve outgoing connections however many it learns of")
	void aNodeKeepsAtMostTwelveOutgoing_howeverManyItLearnsOf() throws IOException
	{
		List<Node> others = new ArrayList<>();
		try (Node hub = Node.on(genesis).discoverPeers(false).keepingLocalAddresses();
			Node newcomer = Node.on(genesis).keepingLocalAddresses())
		{
			int hubPort = hub.listen(0);
			for (int index = 0; index < Node.MAXIMUM_OUTGOING + 2; index++)
			{
				Node other = Node.on(genesis).discoverPeers(false);
				others.add(other);
				other.listen(0);
				other.connect("127.0.0.1", hubPort);
			}
			await("every other node at the hub",
				() -> hub.peers().size() == Node.MAXIMUM_OUTGOING + 2);

			newcomer.connect("127.0.0.1", hubPort);

			await("the newcomer's outgoing connections full",
				() -> newcomer.peers().size() == Node.MAXIMUM_OUTGOING);
			assertFalse(newcomer.heardOf().isEmpty(), "the rest stays heard of");
		}
		finally
		{
			others.forEach(Node::close);
		}
	}

	@Test
	@DisplayName("PEERS that nobody asked for disconnects")
	void peersThatNobodyAskedFor_disconnect() throws IOException
	{
		try (Node node = Node.on(genesis); Socket socket = handshake(node.listen(0)))
		{
			DataInputStream in = new DataInputStream(socket.getInputStream());
			Frames.write(new DataOutputStream(socket.getOutputStream()), new Frame(
				MessageType.PEERS, new PeerList(List.of(new PeerAddress("127.0.0.1", 1))).encode()));

			assertThrows(EOFException.class, () -> Frames.read(in, Frames.MAXIMUM_FRAME));
			await("the reason", () -> node.refusals().stream()
				.anyMatch(reason -> reason.contains("PEERS nobody asked for")));
		}
	}

	@Test
	@DisplayName("PEERS with one address more than 250 does not decode")
	void peersAboveTheLimit_doNotDecode()
	{
		List<PeerAddress> tooMany = IntStream.rangeClosed(1, PeerList.LIMIT + 1)
			.mapToObj(port -> new PeerAddress("127.0.0.1", port)).toList();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Wire.writeInt(out, tooMany.size());
		tooMany.forEach(address -> {
			Wire.writeText(out, address.host());
			Wire.writeInt(out, address.port());
		});

		assertThrows(ProtocolViolation.class, () -> PeerList.decode(out.toByteArray()));
		assertEquals(PeerList.LIMIT, 250);
	}

	@Test
	@DisplayName("the address book passes on only confirmed addresses, the newest 250")
	void theBook_passesOnOnlyConfirmedAddresses()
	{
		PeerBook book = new PeerBook();
		book.heard(List.of(new PeerAddress("198.51.100.1", 1)));
		for (int port = 1; port <= PeerList.LIMIT + 10; port++)
		{
			book.confirmed(new PeerAddress("198.51.100.2", port));
		}

		List<PeerAddress> shared = book.shareable();

		assertEquals(PeerList.LIMIT, shared.size());
		assertEquals(new PeerAddress("198.51.100.2", PeerList.LIMIT + 10), shared.getLast());
		assertTrue(shared.stream().noneMatch(address -> address.host().equals("198.51.100.1")));
	}

	@Test
	@DisplayName("a node accepted from 127.0.0.1, as one through an onion service is, is not passed on (#194)")
	void aNodeAcceptedFromLoopback_isNotPassedOn() throws IOException
	{
		try (Node seed = Node.on(genesis); Node behindTor = Node.on(genesis))
		{
			int port = seed.listen(0);
			behindTor.listen(0);
			behindTor.connect("127.0.0.1", port);
			await("the connection", () -> seed.peers().size() == 1);

			assertEquals(List.of(), askForPeers(port));
		}
	}

	@Test
	@DisplayName("of the addresses a peer passes on, a node keeps neither 127.0.0.1 nor one from 10.0.0.0/8 (#194)")
	void learntAddresses_keepNeitherLoopbackNorPrivate() throws IOException
	{
		PeerAddress reachable = new PeerAddress("198.51.100.7", 18480);
		try (Node node = Node.on(genesis).discoverPeers(false);
			ServerSocket server = new ServerSocket(0))
		{
			node.connect("127.0.0.1", server.getLocalPort());
			try (Socket peer = server.accept())
			{
				peer.setSoTimeout(10_000);
				DataInputStream in = new DataInputStream(peer.getInputStream());
				DataOutputStream out = new DataOutputStream(peer.getOutputStream());
				Frames.write(out,
					new Frame(MessageType.HELLO, Hello.of(genesis, 18480, 42L).encode()));
				readUntil(in, MessageType.GET_PEERS);

				Frames.write(out, new Frame(MessageType.PEERS,
					new PeerList(List.of(new PeerAddress("127.0.0.1", 18481),
						new PeerAddress("10.0.0.1", 18482), reachable)).encode()));

				await("the reachable address", () -> node.heardOf().contains(reachable));
				assertEquals(List.of(reachable), node.heardOf());
			}
		}
	}

	private static void readUntil(final DataInputStream in, final MessageType type)
		throws IOException
	{
		Frame frame = Frames.read(in, Frames.MAXIMUM_FRAME);
		while (frame.type() != type)
		{
			frame = Frames.read(in, Frames.MAXIMUM_FRAME);
		}
	}

	private List<PeerAddress> askForPeers(final int port) throws IOException
	{
		try (Socket socket = handshake(port))
		{
			DataInputStream in = new DataInputStream(socket.getInputStream());
			Frames.write(new DataOutputStream(socket.getOutputStream()),
				new Frame(MessageType.GET_PEERS, new byte[0]));
			Frame answer = Frames.read(in, Frames.MAXIMUM_FRAME);
			assertEquals(MessageType.PEERS, answer.type());
			return PeerList.decode(answer.payload()).addresses();
		}
	}

	private Socket handshake(final int port) throws IOException
	{
		Socket socket = new Socket("127.0.0.1", port);
		socket.setSoTimeout(10_000);
		DataInputStream in = new DataInputStream(socket.getInputStream());
		Frames.write(new DataOutputStream(socket.getOutputStream()),
			new Frame(MessageType.HELLO, Hello.of(genesis).encode()));
		Frames.read(in, Frames.MAXIMUM_FRAME);
		return socket;
	}
}
