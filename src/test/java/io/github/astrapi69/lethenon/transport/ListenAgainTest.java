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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Bytes;

/**
 * A node binds its port again while the connections of its last run on that port wait in TIME_WAIT:
 * a seed node on a fixed port restarted, and every test that lets a node go and binds its port
 * again (#247). The side that closes a TCP connection first keeps it in TIME_WAIT for a minute, and
 * only SO_REUSEADDR lets a new listener bind the port meanwhile.
 */
class ListenAgainTest
{

	private static final List<Path> CONNECTION_TABLES = List.of(Path.of("/proc/net/tcp"),
		Path.of("/proc/net/tcp6"));

	/** TIME_WAIT in the state column of /proc/net/tcp */
	private static final String TIME_WAIT = "06";

	private final List<BlockBody> genesis = testGenesis(Bytes.of(new byte[] { 1 }));

	@Test
	@DisplayName("a node listens again on a port whose last connection is in TIME_WAIT")
	void aNodeListensAgainOnItsPort_whileItsLastConnectionIsInTimeWait() throws IOException
	{
		assumeTrue(Files.isReadable(CONNECTION_TABLES.get(1)),
			"reads the connection states from /proc/net, which Linux has");
		int port;
		try (ServerSocket first = new ServerSocket())
		{
			first.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			port = first.getLocalPort();
			try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port);
				Socket accepted = first.accept())
			{
				// the listener's side closes first and so keeps the connection in TIME_WAIT; no byte
				// is sent either way, so neither side has a reason to answer with a reset instead
				accepted.close();
				assertEquals(-1, client.getInputStream().read());
			}
		}
		await("the listener's side of the connection, on its port, in TIME_WAIT",
			() -> inTimeWait(port));

		try (Node again = Node.on(genesis))
		{
			assertEquals(port, again.listen(port));
		}
	}

	private static boolean inTimeWait(final int port)
	{
		String local = ":" + String.format(Locale.ROOT, "%04X", port);
		for (Path table : CONNECTION_TABLES)
		{
			try
			{
				for (String line : Files.readAllLines(table))
				{
					String[] columns = line.trim().split("\\s+");
					if (columns.length > 3 && columns[1].endsWith(local) && TIME_WAIT.equals(columns[3]))
					{
						return true;
					}
				}
			}
			catch (IOException unreadable)
			{
				throw new UncheckedIOException(unreadable);
			}
		}
		return false;
	}
}
