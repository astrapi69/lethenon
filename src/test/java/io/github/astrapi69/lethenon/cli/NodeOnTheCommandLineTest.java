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
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.SignedTransaction;
import io.github.astrapi69.lethenon.transport.Node;

/**
 * send and faucet hand a transfer to a running node with --node, instead of writing the pool file
 * the node keeps (ADR 0003, the pool)
 */
class NodeOnTheCommandLineTest extends AbstractCliTest
{

	@TempDir
	Path directory;

	private Path chain;

	private String password;

	private String wallet;

	private String account;

	@BeforeEach
	void aTestChain()
	{
		chain = directory.resolve("chain.lethenon");
		password = aPassword();
		wallet = directory.resolve("holder.wallet").toString();
		assertEquals(0, run(password, "wallet", "create", "--wallet", wallet), err);
		account = matchIn(ACCOUNT, out);
		assertEquals(0, run(password, "mine", "--testnet", "--chain", chain.toString(), "--wallet",
			wallet), err);
		// block 0 pays the burn account (#148), block 1 this wallet
		assertEquals(0, run(password, "mine", "--chain", chain.toString(), "--wallet", wallet),
			err);
	}

	@Test
	@DisplayName("send --node hands the transfer to the node, twice with consecutive nonces")
	void sendToANode_waitsInItsPool() throws IOException
	{
		ChainFile file = new ChainFile(chain);
		try (Node node = Node.serving(file))
		{
			String address = "127.0.0.1:" + node.listen(0);

			assertEquals(0, run(password, "send", "--chain", chain.toString(), "--wallet", wallet,
				"--to", account, "--amount", "1", "--node", address), err);
			assertTrue(out.contains("with nonce 0"), out);
			assertTrue(out.contains("node at " + address), out);
			assertTrue(out.contains("1 waiting"), out);
			assertEquals(0, run(password, "send", "--chain", chain.toString(), "--wallet", wallet,
				"--to", account, "--amount", "2", "--node", address), err);
			assertTrue(out.contains("with nonce 1"), out);

			List<SignedTransaction> pending = file.readPending();
			assertEquals(List.of(0L, 1L),
				pending.stream().map(transfer -> transfer.body().nonce()).toList());
			assertEquals(pending, node.pending());
		}
	}

	@Test
	@DisplayName("faucet --node hands the faucet transfer to the node")
	void faucetToANode_waitsInItsPool() throws IOException
	{
		try (Node node = Node.serving(new ChainFile(chain)))
		{
			String address = "127.0.0.1:" + node.listen(0);

			assertEquals(0, run(password, "faucet", "--chain", chain.toString(), "--wallet", wallet,
				"--to", account, "--node", address), err);

			assertEquals(1, node.pending().size());
		}
	}

	@Test
	@DisplayName("a node that cannot be reached fails the command, and nothing is written")
	void anUnreachableNode_failsTheCommand() throws IOException
	{
		int closedPort;
		try (ServerSocket probe = new ServerSocket(0))
		{
			closedPort = probe.getLocalPort();
		}

		int exit = run(password, "send", "--chain", chain.toString(), "--wallet", wallet, "--to",
			account, "--amount", "1", "--node", "127.0.0.1:" + closedPort);

		assertEquals(1, exit, out);
		assertTrue(err.contains("127.0.0.1:" + closedPort), err);
		assertEquals(List.of(), new ChainFile(chain).readPending());
	}

	@Test
	@DisplayName("a transfer the node does not admit fails the command with where to look")
	void aTransferTheNodeDoesNotAdmit_failsTheCommand() throws IOException
	{
		Path elsewhere = directory.resolve("elsewhere.lethenon");
		ChainFile copy = new ChainFile(elsewhere);
		copy.write(new ChainFile(chain).require());
		try (Node node = Node.serving(copy))
		{
			String address = "127.0.0.1:" + node.listen(0);
			assertEquals(0, run(password, "send", "--chain", elsewhere.toString(), "--wallet",
				wallet, "--to", account, "--amount", "1", "--node", address), err);

			int exit = run(password, "send", "--chain", chain.toString(), "--wallet", wallet,
				"--to", account, "--amount", "2", "--node", address);

			assertEquals(1, exit, out);
			assertTrue(err.contains("not waiting in " + chain), err);
			assertEquals(1, node.pending().size(), "the second one reused nonce 0");
		}
	}
}
