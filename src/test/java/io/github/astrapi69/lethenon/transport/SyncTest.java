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

import static io.github.astrapi69.lethenon.transport.Networks.extended;
import static io.github.astrapi69.lethenon.transport.Networks.genesis;
import static io.github.astrapi69.lethenon.transport.Networks.testGenesis;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.DifficultyRule;

/**
 * A one-shot sync brings a chain file up to a node's tip and stops (#107): every block verified by
 * the replay a node uses, the file written once and only when the chain grew, nothing written when
 * the sync fails
 */
class SyncTest
{

	private static final Bytes MINER = Bytes.of(new byte[] { 9 });

	private static final Duration WITHIN = Duration.ofSeconds(20);

	private final List<BlockBody> genesis = testGenesis(Bytes.of(new byte[] { 1 }));

	private final List<BlockBody> ahead = chainOf(BlockRequest.LIMIT + 7);

	@TempDir
	Path directory;

	private List<BlockBody> chainOf(final int blocks)
	{
		List<BlockBody> chain = genesis;
		while (chain.size() < blocks)
		{
			chain = extended(chain, MINER, List.of());
		}
		return chain;
	}

	@Test
	@DisplayName("an empty chain file takes the peer's whole chain, genesis block included")
	void anEmptyFile_takesThePeersWholeChain() throws IOException
	{
		ChainFile file = new ChainFile(directory.resolve("empty.lethenon"));
		try (Node peer = Node.on(ahead))
		{
			Sync.Synced synced = Sync.once(file, local(peer.listen(0)), WITHIN);

			assertEquals(ahead, file.require());
			assertEquals(0L, synced.blocksBefore());
			assertEquals(ahead.size(), synced.blocksAfter());
			assertEquals(Chain.TEST_IDENTIFIER, synced.chainIdentifier());
		}
	}

	@Test
	@DisplayName("a chain file behind the peer ends at the peer's tip")
	void aFileBehind_endsAtThePeersTip() throws IOException
	{
		Path path = fileWith(genesis);
		ChainFile file = new ChainFile(path);
		try (Node peer = Node.on(ahead))
		{
			Sync.Synced synced = Sync.once(file, local(peer.listen(0)), WITHIN);

			assertEquals(ahead, file.require());
			assertEquals(genesis.size(), synced.blocksBefore());
			assertEquals(ahead.size(), synced.blocksAfter());
		}
	}

	@Test
	@DisplayName("a chain file ahead of the peer is left as it was, and the sync says so")
	void aFileAhead_isLeftAsItWas() throws IOException
	{
		Path path = fileWith(ahead);
		ChainFile file = new ChainFile(path);
		byte[] before = Files.readAllBytes(path);
		try (Node peer = Node.on(genesis))
		{
			Sync.Synced synced = Sync.once(file, local(peer.listen(0)), WITHIN);

			assertArrayEquals(before, Files.readAllBytes(path));
			assertEquals(synced.blocksBefore(), synced.blocksAfter());
		}
	}

	@Test
	@DisplayName("a peer on another genesis block is refused, and the file is not written")
	void aPeerOnAnotherGenesis_isRefused() throws IOException
	{
		Path path = fileWith(genesis);
		ChainFile file = new ChainFile(path);
		byte[] before = Files.readAllBytes(path);
		List<BlockBody> other = testGenesis(Bytes.of(new byte[] { 2 }));
		try (Node peer = Node.on(extended(other, MINER, List.of())))
		{
			PeerAddress address = local(peer.listen(0));

			IOException refused = assertThrows(IOException.class,
				() -> Sync.once(file, address, WITHIN));

			assertTrue(refused.getMessage().contains(address.toString()), refused.getMessage());
			assertArrayEquals(before, Files.readAllBytes(path));
		}
	}

	@Test
	@DisplayName("a peer that cannot be reached is named, and an empty file stays empty")
	void anUnreachablePeer_isNamed() throws IOException
	{
		Path path = directory.resolve("empty.lethenon");
		ChainFile file = new ChainFile(path);
		PeerAddress nobody = local(aClosedPort());

		IOException refused = assertThrows(IOException.class,
			() -> Sync.once(file, nobody, WITHIN));

		assertTrue(refused.getMessage().contains(nobody.toString()), refused.getMessage());
		assertFalse(Files.exists(path));
	}

	@Test
	@DisplayName("a peer that announces a tip and never sends it fails the sync when the time is up")
	void aPeerThatNeverSendsItsTip_failsWhenTheTimeIsUp() throws Exception
	{
		Path path = fileWith(genesis);
		ChainFile file = new ChainFile(path);
		byte[] before = Files.readAllBytes(path);
		Hello ours = Hello.of(genesis);
		Hello boasting = new Hello(ours.protocolVersion(), ours.chainIdentifier(),
			ours.genesisHash(), 1_000L, Bytes.of(new byte[32]), BigInteger.TWO.pow(200));
		ExecutorService threads = Executors.newSingleThreadExecutor();
		try (ServerSocket server = new ServerSocket(0))
		{
			threads.submit(() -> silentPeer(server, boasting));

			IOException refused = assertThrows(IOException.class,
				() -> Sync.once(file, local(server.getLocalPort()), Duration.ofMillis(1_500)));

			String message = refused.getMessage();
			assertTrue(message.contains("height 1000"), message);
			assertTrue(message.contains("height " + (genesis.size() - 1)), message);
			assertArrayEquals(before, Files.readAllBytes(path));
		}
		finally
		{
			threads.shutdownNow();
			threads.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	@Test
	@DisplayName("a main chain file is refused before anything is sent: the network is the test network's")
	void aMainChainFile_isRefused() throws IOException
	{
		Path path = fileWith(genesis(Chain.IDENTIFIER, Bytes.of(new byte[] { 1 })));
		ChainFile file = new ChainFile(path);
		try (Node peer = Node.on(ahead))
		{
			PeerAddress address = local(peer.listen(0));

			IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> Sync.once(file, address, WITHIN));

			assertTrue(refused.getMessage().contains(Chain.IDENTIFIER), refused.getMessage());
		}
	}

	@Test
	@DisplayName("a test chain file from before the rules of 0.4.0 is refused before anything is sent, and the reason says so")
	void aTestChainFileUnderTheRetiredIdentifier_isRefused() throws IOException
	{
		BlockBody retired = Blocks.mine(new BlockBody("lethenon-test-1", 0L,
			Bytes.of(new byte[32]), Bytes.of(new byte[] { 1 }), List.of(), 1_759_000_000_000L,
			DifficultyRule.MINIMUM, "in the beginning"), 1_000_000L).orElseThrow();
		ChainFile file = new ChainFile(fileWith(List.of(retired)));
		try (Node peer = Node.on(ahead))
		{
			PeerAddress address = local(peer.listen(0));

			IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> Sync.once(file, address, WITHIN));

			assertTrue(refused.getMessage().contains("'lethenon-test-1'"), refused.getMessage());
			assertTrue(refused.getMessage().contains("started under the rules before lethenon 0.4.0"),
				refused.getMessage());
			assertTrue(refused.getMessage().contains("'" + Chain.TEST_IDENTIFIER + "'"),
				refused.getMessage());
		}
	}

	private Path fileWith(final List<BlockBody> chain) throws IOException
	{
		Path path = directory.resolve("chain-" + chain.size() + ".lethenon");
		new ChainFile(path).write(chain);
		return path;
	}

	private static PeerAddress local(final int port)
	{
		return new PeerAddress("127.0.0.1", port);
	}

	private static int aClosedPort() throws IOException
	{
		try (ServerSocket socket = new ServerSocket(0))
		{
			return socket.getLocalPort();
		}
	}

	/**
	 * Accepts one connection, shakes hands with the given HELLO and then answers nothing
	 */
	private static Void silentPeer(final ServerSocket server, final Hello hello) throws IOException
	{
		try (Socket socket = server.accept())
		{
			Frames.write(new DataOutputStream(socket.getOutputStream()),
				new Frame(MessageType.HELLO, hello.encode()));
			DataInputStream in = new DataInputStream(socket.getInputStream());
			while (true)
			{
				Frames.read(in, Frames.MAXIMUM_FRAME);
			}
		}
	}
}
