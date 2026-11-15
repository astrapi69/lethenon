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

import static io.github.astrapi69.lethenon.transport.Networks.addressBookOf;
import static io.github.astrapi69.lethenon.transport.Networks.announcedAtTheHub;
import static io.github.astrapi69.lethenon.transport.Networks.await;
import static io.github.astrapi69.lethenon.transport.Networks.connectedInTurn;
import static io.github.astrapi69.lethenon.transport.Networks.leftItsHandshakeWhileTheBookWasHeld;
import static io.github.astrapi69.lethenon.transport.Networks.testGenesis;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.SignatureSuite;
import io.github.astrapi69.lethenon.Wallet;

/**
 * What a node does with a learnt address whose dial fails (ADR 0003, #278, #296): the address
 * stays in the book and waits, and the node fills its slots from the others. The failures are
 * made, not hoped for: nodes the hub has already been connected to are closed before the newcomer
 * dials them, so their ports refuse the connection. The others connect to the hub one after the
 * other, which fixes the order the hub passes their addresses on in, and with it the order the
 * newcomer dials them in.
 */
class FailedDialTest
{

	private static final String NOT_CONNECTED = "learnt from a peer, was not connected";

	private final Wallet holder = Wallet.create();

	private final List<BlockBody> genesis = testGenesis(holder.spendKey(SignatureSuite.ED25519));

	private static long notConnected(final Node node)
	{
		return node.refusals().stream().filter(refusal -> refusal.contains(NOT_CONNECTED)).count();
	}

	@Test
	@DisplayName("a learnt address whose dial is refused stays heard of and waits, and the refusal says so (ADR 0003, #296)")
	void aLearntAddressWhoseDialIsRefused_staysHeardOfAndWaits() throws IOException
	{
		List<Node> others = new ArrayList<>();
		try (Node hub = Node.on(genesis).discoverPeers(false).keepingLocalAddresses();
			Node newcomer = Node.on(genesis).keepingLocalAddresses())
		{
			int hubPort = hub.listen(0);
			others.addAll(connectedInTurn(genesis, hub, hubPort, 2));
			others.get(0).close();
			await("the closed node gone from the hub", () -> hub.peers().size() == 1);

			newcomer.connect("127.0.0.1", hubPort);

			await(() -> "the refused dial recorded; refusals " + newcomer.refusals(),
				() -> notConnected(newcomer) == 1);
			await(() -> "the open node connected; peers " + newcomer.peers().size(),
				() -> newcomer.peers().size() == 2);
			assertEquals(newcomer.waitingAddresses(), newcomer.heardOf(),
				() -> "the refused address is not heard of, or does not wait; refusals "
					+ newcomer.refusals());
			assertEquals(1, newcomer.heardOf().size(), () -> "heard of " + newcomer.heardOf());
			assertTrue(newcomer.refusals().stream().anyMatch(refusal -> refusal.contains(NOT_CONNECTED)
				&& refusal.contains("it waits 60 s before the next attempt (1 counted failure)")),
				() -> "no refusal names the wait: " + newcomer.refusals());
		}
		finally
		{
			others.forEach(Node::close);
		}
	}

	@Test
	@DisplayName("with three of fourteen learnt addresses refused, the newcomer holds twelve and the three wait (#278, #296)")
	void threeRefusedOfFourteen_leaveTwelveAndTheThreeWaiting() throws IOException
	{
		List<Node> others = new ArrayList<>();
		try (Node hub = Node.on(genesis).discoverPeers(false).keepingLocalAddresses();
			Node newcomer = Node.on(genesis).keepingLocalAddresses())
		{
			int hubPort = hub.listen(0);
			others.addAll(connectedInTurn(genesis, hub, hubPort, Node.MAXIMUM_OUTGOING + 2));
			for (int index = 0; index < 3; index++)
			{
				others.get(index).close();
			}
			await("the three closed nodes gone from the hub",
				() -> hub.peers().size() == Node.MAXIMUM_OUTGOING - 1);

			newcomer.connect("127.0.0.1", hubPort);

			await(() -> "three refused dials recorded; refusals " + newcomer.refusals(),
				() -> notConnected(newcomer) == 3);
			await(() -> "the newcomer full; peers " + newcomer.peers().size() + ", heard of "
				+ newcomer.heardOf(), () -> newcomer.peers().size() == Node.MAXIMUM_OUTGOING);
			assertEquals(3, newcomer.heardOf().size(),
				() -> "heard of " + newcomer.heardOf() + ", refusals " + newcomer.refusals());
			assertTrue(newcomer.waitingAddresses().containsAll(newcomer.heardOf()),
				() -> "heard of " + newcomer.heardOf() + ", waiting " + newcomer.waitingAddresses());
		}
		finally
		{
			others.forEach(Node::close);
		}
	}

	/**
	 * A learnt address whose connection opens and then closes before HELLO: Node.serve records the
	 * failure while the address still counts as in its handshake, so no fill takes it in between
	 * (#296). The test holds the newcomer's address book while it closes the far end, which stops
	 * the connection's thread where it records the failure: the address stays in its handshake
	 * meanwhile, and waits once the book is let go. Before, the outer finally recorded the failure
	 * after the address had left its handshake, and it was neither busy nor waiting there
	 */
	@Test
	@DisplayName("an address whose handshake ends without HELLO stays in its handshake until its failure is recorded")
	void anAddressWhoseHandshakeEndsWithoutHello_staysInItsHandshake_untilItsFailureIsRecorded()
		throws Exception
	{
		List<Socket> held = new CopyOnWriteArrayList<>();
		try (Node hub = Node.on(genesis).discoverPeers(false).keepingLocalAddresses();
			Node newcomer = Node.on(genesis).keepingLocalAddresses();
			ServerSocket silent = silent(held))
		{
			int hubPort = hub.listen(0);
			PeerAddress address = new PeerAddress("127.0.0.1", silent.getLocalPort());
			try (Socket announcing = announcedAtTheHub(genesis, hubPort, address.port()))
			{
				await("the address confirmed at the hub", () -> hub.peers().size() == 1);
				newcomer.connect("127.0.0.1", hubPort);
				await(() -> "the newcomer in its handshake with " + address + "; refusals "
					+ newcomer.refusals(),
					() -> !held.isEmpty() && newcomer.handshakingAddresses().contains(address));

				Optional<List<PeerAddress>> waitingWhenItLeft = leftItsHandshakeWhileTheBookWasHeld(
					addressBookOf(newcomer, false), held.get(0), address,
					newcomer::handshakingAddresses, newcomer::waitingAddresses);

				await(() -> "the failure recorded; waiting " + newcomer.waitingAddresses()
					+ ", refusals " + newcomer.refusals(),
					() -> !newcomer.handshakingAddresses().contains(address)
						&& newcomer.waitingAddresses().contains(address));
				assertEquals(Optional.empty(), waitingWhenItLeft,
					"what was waiting when " + address + " left its handshake");
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
	 * Listens on a loopback port and accepts, but never says HELLO; what it accepted is held for
	 * the test to close
	 */
	private static ServerSocket silent(final List<Socket> held) throws IOException
	{
		ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
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
		return silent;
	}
}
