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

import static io.github.astrapi69.lethenon.transport.Networks.testGenesis;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Bytes;

/**
 * What a node's ports do once {@link Node#close()} has returned (#325). A listener whose accept
 * runs on a virtual thread is not closed when ServerSocket.close() returns: the JDK finishes the
 * close on the accepting thread, and until that thread has run, the port completes handshakes
 * into its backlog and resets them afterwards. A dial right after close() then reads "Connection
 * reset" instead of being refused, which is how FailedDialTest failed under load: the ports of
 * the nodes it closed took the newcomer's dials
 */
class ClosedNodeTest
{

	private static final int DIALS = 40;

	private static final PeerAddress ONION = PeerAddress.parse(
		"cccccccccccccccccccccccccccccccccccccccccccccccccccccccc.onion:18484");

	private final List<BlockBody> genesis = testGenesis(Bytes.of(new byte[] { 1 }));

	/** A listener of a node: it starts listening, and says on which port */
	interface Listener
	{
		int listen(Node node) throws IOException;
	}

	/** The node's two listeners, by name */
	record Port(String name, Listener listener)
	{
		@Override
		public String toString()
		{
			return name;
		}
	}

	static Stream<Port> ports()
	{
		return Stream.of(new Port("the clearnet port", node -> node.listen(0)),
			new Port("the onion service's port", node -> node
				.anonymityZone(Outbound.through(new PeerAddress("127.0.0.1", 1)), 2)
				.listenAnonymously(ONION, 0, 16)));
	}

	/**
	 * Of {@value #DIALS} nodes closed one after the other, no dial right after close() reaches the
	 * port: close() waits until the accepting threads have ended. Before, at least one did; on
	 * develop ed2ac66, 139 of 200 without load and 64 of 200 with every core busy
	 */
	@ParameterizedTest(name = "{0} refuses a dial right after the node closed")
	@MethodSource("ports")
	void aPortRefusesADial_rightAfterTheNodeClosed(final Port port)
		throws IOException, InterruptedException
	{
		int taken = 0;
		for (int dial = 0; dial < DIALS; dial++)
		{
			Node node = Node.on(genesis);
			int listening = port.listener().listen(node);
			// the accepting thread reaches accept(), as a node's that has been running has
			Thread.sleep(5);
			node.close();
			if (taken(listening))
			{
				taken++;
			}
		}
		assertEquals(0, taken, "dials of " + DIALS + " taken right after close()");
	}

	/**
	 * Whether the loopback port took a dial's handshake. A port that no longer listens refuses it
	 * ("Connection refused"). One that took it and reset it before connect() returned fails the
	 * connect with "Connection reset by peer": a reset of an established connection, which only a
	 * taken handshake has
	 */
	private static boolean taken(final int port) throws IOException
	{
		try (Socket dial = new Socket())
		{
			dial.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000);
			return true;
		}
		catch (ConnectException refused)
		{
			return false;
		}
		catch (SocketException reset)
		{
			return true;
		}
	}
}
