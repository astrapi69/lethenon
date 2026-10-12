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

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.ChainRejected;
import io.github.astrapi69.lethenon.transport.Outbound;
import io.github.astrapi69.lethenon.transport.PeerAddress;
import io.github.astrapi69.lethenon.transport.Sync;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Brings a chain file up to one node's tip and stops (#107): {@link Sync} on the command line
 */
@Command(name = "sync", description = "Bring a chain file of the test network up to a node's tip, "
	+ "verifying every block, and stop. An empty file starts from the node's genesis block. "
	+ "Reads no wallet, asks for no balance.")
class SyncCommand implements Callable<Integer>
{

	@Option(names = "--chain", required = true,
		description = "the chain file; empty, it starts from the node's genesis block")
	Path chain;

	@Option(names = "--peer", required = true, description = "host:port of the node")
	String peer;

	@Option(names = "--proxy", description = "host:port of a SOCKS5 proxy, for Tor usually "
		+ "127.0.0.1:9050; needed for an onion address (ADR 0004)")
	String proxy;

	@Option(names = "--within", defaultValue = "300",
		description = "give up after this many seconds; default: ${DEFAULT-VALUE}")
	long seconds;

	/**
	 * Creates the command; picocli instantiates it reflectively
	 */
	SyncCommand()
	{
	}

	@Override
	public Integer call()
	{
		try
		{
			return run(System.out);
		}
		catch (ChainRejected | IllegalArgumentException | IllegalStateException
			| SecurityException | IOException refused)
		{
			System.err.println(refused.getMessage());
			return 1;
		}
	}

	private int run(final PrintStream out) throws IOException
	{
		PeerAddress address = PeerAddress.parse(peer);
		ChainCommand.refuseAnOnionPeerWithoutTor(List.of(address), proxy != null, "--proxy");
		Outbound outbound = proxy == null ? Outbound.DIRECT
			: Outbound.through(PeerAddress.parse(proxy));
		Sync.Synced synced = Sync.once(new ChainFile(chain), address, Duration.ofSeconds(seconds),
			outbound);
		out.println("chain " + synced.chainIdentifier() + " from the node at " + address
			+ ": took " + synced.taken() + " block(s), now at height "
			+ (synced.blocksAfter() - 1));
		return 0;
	}
}
