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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.CanonicalEncoding;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.Genesis;
import io.github.astrapi69.lethenon.Mining;
import io.github.astrapi69.lethenon.Replay;
import io.github.astrapi69.lethenon.transport.Node;

/**
 * lethenon node: a node of the test network as a command of its own (ADR 0003)
 */
class NodeCommandTest extends AbstractCliTest
{

	@TempDir
	Path directory;

	private Path chain;

	private String password;

	private String wallet;

	@BeforeEach
	void aWallet()
	{
		chain = directory.resolve("chain.lethenon");
		password = aPassword();
		wallet = directory.resolve("miner.wallet").toString();
		assertEquals(0, run(password, "wallet", "create", "--wallet", wallet), err);
	}

	@Test
	@DisplayName("node --mine --no-default-peers on an empty file starts a test chain and mines on it")
	void mineOnAnEmptyFile_startsATestChainAndMines() throws IOException
	{
		assertEquals(0, run(password, "node", "--chain", chain.toString(), "--listen", "0",
			"--no-default-peers", "--mine", "--wallet", wallet, "--for", "2"), err);

		List<BlockBody> blocks = new ChainFile(chain).require();
		assertEquals(Chain.TEST_IDENTIFIER, blocks.getFirst().chainIdentifier());
		assertTrue(blocks.size() >= 2, blocks.size() + " blocks");
		Replay.verify(blocks);
		assertTrue(out.contains("listening on 0.0.0.0 port"), out);
		assertTrue(out.contains("height " + (blocks.size() - 1)), out);
	}

	@Test
	@DisplayName("node --peer on an empty file takes the peer's chain")
	void aPeerOnAnEmptyFile_takesThePeersChain() throws IOException
	{
		assertEquals(0, run(password, "mine", "--testnet", "--chain", chain.toString(),
			"--wallet", wallet), err);
		assertEquals(0, run(password, "mine", "--chain", chain.toString(), "--wallet", wallet),
			err);
		List<BlockBody> theirs = new ChainFile(chain).require();
		Path empty = directory.resolve("empty.lethenon");
		try (Node peer = Node.serving(new ChainFile(chain)))
		{
			int port = peer.listen(0);

			assertEquals(0, run("", "node", "--chain", empty.toString(), "--listen", "0",
				"--peer", "127.0.0.1:" + port, "--for", "2"), err);
		}

		assertEquals(theirs, new ChainFile(empty).require());
	}

	@Test
	@DisplayName("a main chain file is refused")
	void aMainChain_isRefused() throws Exception
	{
		// a main chain file as the start day writes it; this build does not start one itself
		Files.write(chain, CanonicalEncoding.encodeChain(List.of(Blocks.mine(Mining.nextBlock(
			Chain.IDENTIFIER, List.of(), Genesis.NOBODY, List.of(), "main", System.currentTimeMillis()),
			1_000_000L).orElseThrow())));

		assertEquals(1, run("", "node", "--chain", chain.toString(), "--listen", "0", "--for", "1"),
			out);
		assertTrue(err.contains("'" + Chain.TEST_IDENTIFIER + "'"), err);
	}

	@Test
	@DisplayName("--mine without --wallet fails with a message")
	void mineWithoutAWallet_fails()
	{
		assertEquals(1, run("", "node", "--chain", chain.toString(), "--listen", "0", "--mine",
			"--for", "1"), out);
		assertTrue(err.contains("--wallet"), err);
	}

	@Test
	@DisplayName("an empty file without --mine, --peer or default peers fails with a message")
	void anEmptyFileWithNothingToStartFrom_fails()
	{
		assertEquals(1, run("", "node", "--chain", chain.toString(), "--listen", "0",
			"--no-default-peers", "--for", "1"), out);
		assertTrue(err.contains("--peer"), err);
		assertTrue(err.contains("--mine"), err);
	}
}
