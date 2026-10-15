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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Bytes;

/**
 * A node fills an outgoing slot again when a connection to a learnt address ends or never shakes
 * hands, without waiting for the next PEERS answer to arrive (#121)
 */
class OutgoingRefillTest
{

	private final List<BlockBody> genesis = testGenesis(Bytes.of(new byte[] { 1 }));

	@Test
	@DisplayName("an address that accepts and never says HELLO does not keep its slot once the handshake times out")
	void aSilentAddress_givesItsSlotBack() throws Exception
	{
		List<Node> others = new ArrayList<>();
		List<Socket> held = new CopyOnWriteArrayList<>();
		try (Node hub = Node.on(genesis).discoverPeers(false).keepingLocalAddresses();
			Node newcomer = Node.on(genesis).keepingLocalAddresses();
			ServerSocket silent = new ServerSocket(0))
		{
			Thread.ofVirtual().start(() -> holdEveryConnection(silent, held));
			int hubPort = hub.listen(0);
			announceToTheHub(hubPort, silent.getLocalPort());
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

			await("the silent address dialled", () -> !held.isEmpty());
			awaitLonger("the newcomer's outgoing connections full after the handshake timed out",
				() -> newcomer.peers().size() == Node.MAXIMUM_OUTGOING, newcomer);
			assertTrue(newcomer.refusals().stream()
				.anyMatch(reason -> reason.contains("ended the handshake")),
				newcomer.refusals().toString());
		}
		finally
		{
			others.forEach(Node::close);
			for (Socket socket : held)
			{
				socket.close();
			}
		}
	}

	/**
	 * Shakes hands with the hub as a node that listens on the silent port, so that the hub
	 * confirms and passes on that address, then leaves
	 */
	private void announceToTheHub(final int hubPort, final int silentPort) throws IOException
	{
		try (Socket socket = new Socket("127.0.0.1", hubPort))
		{
			socket.setSoTimeout(10_000);
			DataInputStream in = new DataInputStream(socket.getInputStream());
			Frames.write(new DataOutputStream(socket.getOutputStream()),
				new Frame(MessageType.HELLO, Hello.of(genesis, silentPort, 4242L).encode()));
			Frames.read(in, Frames.MAXIMUM_FRAME);
		}
	}

	private static void holdEveryConnection(final ServerSocket silent, final List<Socket> held)
	{
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
	}

	/**
	 * Waits up to twice the handshake timeout and a margin: the silent connection ends only when
	 * the handshake times out
	 */
	private static void awaitLonger(final String what, final java.util.function.BooleanSupplier holds,
		final Node node) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + 2L * Node.HANDSHAKE_MILLIS + 5_000L;
		while (System.currentTimeMillis() < deadline)
		{
			if (holds.getAsBoolean())
			{
				return;
			}
			Thread.sleep(20L);
		}
		throw new AssertionError("waited for " + what + "; peers=" + node.peers().size()
			+ " refusals=" + node.refusals());
	}
}
