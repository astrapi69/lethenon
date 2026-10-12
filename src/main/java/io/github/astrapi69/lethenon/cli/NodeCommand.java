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
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.ChainRejected;
import io.github.astrapi69.lethenon.ConsensusRules;
import io.github.astrapi69.lethenon.Genesis;
import io.github.astrapi69.lethenon.SignatureSuite;
import io.github.astrapi69.lethenon.transport.Bootstrap;
import io.github.astrapi69.lethenon.transport.Miner;
import io.github.astrapi69.lethenon.transport.Node;
import io.github.astrapi69.lethenon.transport.Outbound;
import io.github.astrapi69.lethenon.transport.PeerAddress;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Runs a node of the test network on a chain file (ADR 0003): it listens, connects to a fixed list
 * of peers, relays and synchronises, and with --mine mines on its pool
 */
@Command(name = "node", description = "Run a node of the test network, "
	+ Chain.TEST_IDENTIFIER + ", on a chain "
	+ "file it keeps while it runs. With --mine it mines on its pool and pays the wallet, whose "
	+ "password is the first line of standard input.")
class NodeCommand implements Callable<Integer>
{

	/** How many peers the anonymity zone takes unless --tx-proxy says otherwise: Monero's example */
	static final int DEFAULT_ANONYMITY_PEERS = 10;

	@Option(names = "--chain", required = true,
		description = "the chain file; empty, it is started with --mine or taken from a peer")
	Path chain;

	@Option(names = "--listen", required = true, description = "the TCP port, 0 for any free one")
	int listen;

	@Option(names = "--peer", description = "host:port of a peer; repeat for more, at most 12")
	List<String> peers = new ArrayList<>();

	@Option(names = "--proxy", description = "host:port of a SOCKS5 proxy, for Tor usually "
		+ "127.0.0.1:9050; every outgoing connection goes through it, and the node listens on "
		+ "127.0.0.1 unless --bind says otherwise (ADR 0004)")
	String proxy;

	@Option(names = "--tx-proxy", description = "tor,host:port[,max]: an anonymity zone through "
		+ "Tor's SOCKS proxy; onion peers given with --peer join it, at most max of them "
		+ "(default 10), and a transfer that originates on this node goes only there, or waits "
		+ "for one, never in the clear (ADR 0004)")
	String txProxy;

	@Option(names = "--anonymous-inbound", description = "<onion>:<port>,127.0.0.1:<port>[,max]: "
		+ "this node's onion service, whose HiddenServicePort forwards to the loopback port; its "
		+ "peers belong to the anonymity zone, at most max of them (default 16), and the onion "
		+ "address is announced inside the zone only; needs --tx-proxy (ADR 0004)")
	String anonymousInbound;

	@Option(names = "--bind", description = "the local address to listen on; default: every "
		+ "interface, or 127.0.0.1 with --proxy")
	String bind;

	@Option(names = "--no-discovery", description = "stay with the peers given by --peer and do "
		+ "not connect to addresses learnt from them; the node still passes on what it knows")
	boolean noDiscovery;

	@Option(names = "--mine", description = "mine on the node's pool and pay the wallet")
	boolean mine;

	@Option(names = "--wallet", description = "the wallet the mined blocks pay; needs --mine")
	Path wallet;

	@Option(names = "--pun", defaultValue = "watching is not protecting",
		description = "the words mining varies; default: ${DEFAULT-VALUE}")
	String pun;

	@Option(names = "--for", defaultValue = "0",
		description = "stop after this many seconds; default: run until interrupted")
	long seconds;

	@Option(names = "--status-every", defaultValue = "60",
		description = "print a status line every this many seconds, 0 for none; default: "
			+ "${DEFAULT-VALUE}")
	long statusSeconds;

	/**
	 * Creates the command; picocli instantiates it reflectively
	 */
	NodeCommand()
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
		if (mine && wallet == null)
		{
			throw new IllegalArgumentException("--mine pays a wallet: name it with --wallet");
		}
		List<PeerAddress> addresses = peers.stream().map(PeerAddress::parse).toList();
		ChainCommand.refuseAnOnionPeerWithoutTor(addresses, proxy != null || txProxy != null,
			"--proxy, or with --tx-proxy for the anonymity zone");
		Outbound outbound = proxy == null ? Outbound.DIRECT
			: Outbound.through(PeerAddress.parse(proxy));
		Bytes beneficiary = mine ? ChainCommand
			.openWallet(wallet, ChainCommand.firstLineOfStandardInput())
			.spendKey(SignatureSuite.ED25519) : null;
		ChainFile file = new ChainFile(chain);
		if (file.read().isEmpty())
		{
			file.write(start(addresses, beneficiary, outbound, out));
		}
		try (Node node = Node.serving(file).discoverPeers(!noDiscovery).dialingThrough(outbound))
		{
			String zone = txProxy == null ? "" : startTheZone(node, txProxy);
			zone += anonymousInbound == null ? "" : startTheOnionService(node, anonymousInbound);
			int port = bind == null ? node.listen(listen)
				: node.listen(InetAddress.getByName(bind), listen);
			node.connectAll(addresses);
			out.println("node on " + Chain.TEST_IDENTIFIER + ", listening on "
				+ node.listeningOn().getHostAddress() + " port " + port + ", "
				+ addresses.size() + " peer(s) configured, connecting " + outbound + zone
				+ (mine ? ", mining for " + ChainCommand.hex(beneficiary) : ""));
			Stop stop = Stop.onShutdown();
			try
			{
				long minedBlocks = runUntilStopped(node, beneficiary, stop, out);
				out.println("stopped at height " + (node.chain().size() - 1) + ", mined "
					+ minedBlocks + " block(s), " + node.pending().size()
					+ " transfer(s) waiting, " + node.peers().size() + " peer(s) connected"
					+ (txProxy == null ? "" : ", " + node.anonymousPeers().size()
						+ " anonymity peer(s)"));
				node.refusals().forEach(System.err::println);
			}
			finally
			{
				stop.reported();
			}
		}
		return 0;
	}

	/**
	 * How long to wait for a stop before the next status line or the end of --for, whichever comes
	 * first, rounded up so that the wait ends at or after it
	 *
	 * @param now
	 *            System.nanoTime
	 * @param deadline
	 *            the end of --for in System.nanoTime, 0 for none
	 * @param nextLine
	 *            when the next status line is due in System.nanoTime, 0 for none
	 * @return the milliseconds, 0 for no limit, -1 when --for is over
	 */
	static long millisUntil(final long now, final long deadline, final long nextLine)
	{
		if (deadline != 0L && deadline - now <= 0)
		{
			return -1L;
		}
		long untilTheEnd = deadline == 0L ? 0L : millisRoundedUp(deadline - now);
		long untilTheLine = nextLine == 0L ? 0L : millisRoundedUp(nextLine - now);
		if (untilTheEnd == 0L || untilTheLine == 0L)
		{
			return Math.max(untilTheEnd, untilTheLine);
		}
		return Math.min(untilTheEnd, untilTheLine);
	}

	private static long millisRoundedUp(final long nanos)
	{
		return Math.max(1L, (nanos + 999_999L) / 1_000_000L);
	}

	/**
	 * How the node stands: height, tip, peers, pool and the refusals since the last line, counted.
	 * Their addresses are left out on purpose: a remote address is personal data, and a line
	 * repeated every minute would leave the journal's retention to decide how long it is kept
	 * (#149); the stop report names them once
	 */
	private String statusLine(final Node node, final int refusalsReported, final Miner miner)
	{
		List<BlockBody> chain = node.chain();
		return "status: height " + (chain.size() - 1) + ", tip "
			+ Blocks.hashOf(chain.get(chain.size() - 1)) + ", " + node.peers().size()
			+ " peer(s) connected (" + node.outgoingConnections() + " outgoing, "
			+ node.incomingConnections() + " incoming connection(s))"
			+ (txProxy == null ? "" : ", " + node.anonymousPeers().size() + " anonymity peer(s)")
			+ ", " + node.pending().size() + " transfer(s) waiting"
			+ (miner == null ? "" : ", mined " + miner.minedBlocks() + " block(s)") + ", "
			+ (node.refusals().size() - refusalsReported) + " refusal(s) since the last line";
	}

	/**
	 * How a running node learns that it is to stop, besides --for: a shutdown hook, which SIGTERM
	 * and Ctrl-C both run (#129). The hook ends the wait and then waits, at most
	 * {@link #REPORT_MILLIS}, until the stop line and the refusals are printed, so that a node
	 * stopped by hand gives the same account of its run as one stopped by --for, and gives it once.
	 */
	static final class Stop
	{

		/** How long the JVM's shutdown waits for the stop line */
		static final long REPORT_MILLIS = 5_000L;

		private final CountDownLatch requested = new CountDownLatch(1);

		private final CountDownLatch done = new CountDownLatch(1);

		private final Thread hook = new Thread(this::stopAndWaitForTheReport, "lethenon-node-stop");

		private Stop()
		{
		}

		static Stop onShutdown()
		{
			Stop stop = new Stop();
			Runtime.getRuntime().addShutdownHook(stop.hook);
			return stop;
		}

		private void stopAndWaitForTheReport()
		{
			requested.countDown();
			try
			{
				done.await(REPORT_MILLIS, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException interrupted)
			{
				Thread.currentThread().interrupt();
			}
		}

		/**
		 * Waits for a stop, at most the given time
		 *
		 * @param millis
		 *            the longest wait, 0 for no limit
		 * @return whether a stop was asked for
		 */
		boolean await(final long millis) throws InterruptedException
		{
			if (millis > 0)
			{
				return requested.await(millis, TimeUnit.MILLISECONDS);
			}
			requested.await();
			return true;
		}

		/** The stop line is printed: the hook has nothing left to wait for and is withdrawn */
		void reported()
		{
			done.countDown();
			try
			{
				Runtime.getRuntime().removeShutdownHook(hook);
			}
			catch (IllegalStateException shuttingDown)
			{
				// the JVM is already running its hooks, this one among them; it returns at once
			}
		}
	}

	/**
	 * Reads &lt;onion&gt;:&lt;port&gt;,127.0.0.1:&lt;port&gt;[,max] and starts listening for the onion
	 * service
	 *
	 * @return what the start line says about it
	 */
	private static String startTheOnionService(final Node node, final String value)
		throws IOException
	{
		String form = "--anonymous-inbound is <onion>:<port>,127.0.0.1:<port>[,max], not '"
			+ value + "'";
		String[] parts = value.split(",", -1);
		if (parts.length < 2 || parts.length > 3)
		{
			throw new IllegalArgumentException(form);
		}
		PeerAddress onion = PeerAddress.parse(parts[0]);
		if (!onion.isOnion())
		{
			throw new IllegalArgumentException("--anonymous-inbound starts with this node's onion "
				+ "address, and '" + parts[0] + "' is not one");
		}
		int colon = parts[1].lastIndexOf(':');
		if (colon <= 0)
		{
			throw new IllegalArgumentException(form);
		}
		String localHost = parts[1].substring(0, colon);
		if (!"127.0.0.1".equals(localHost))
		{
			throw new IllegalArgumentException("--anonymous-inbound listens on 127.0.0.1 only, "
				+ "where Tor forwards, not on " + localHost);
		}
		int localPort;
		int maximum;
		try
		{
			// 0 is a port here: any free one, which a test or a second node on one machine uses
			localPort = Integer.parseInt(parts[1].substring(colon + 1));
			maximum = parts.length == 3 ? Integer.parseInt(parts[2]) : Node.MAXIMUM_INCOMING;
		}
		catch (NumberFormatException notANumber)
		{
			throw new IllegalArgumentException(form);
		}
		int port = node.listenAnonymously(onion, localPort, maximum);
		return ", onion service " + onion + " on 127.0.0.1 port " + port + ", at most " + maximum
			+ " peer(s)";
	}

	/**
	 * Reads tor,host:port[,max] and turns the anonymity zone on
	 *
	 * @return what the start line says about it
	 */
	private static String startTheZone(final Node node, final String value)
	{
		String[] parts = value.split(",", -1);
		if (parts.length < 2 || parts.length > 3)
		{
			throw new IllegalArgumentException(
				"--tx-proxy is tor,host:port[,max], not '" + value + "'");
		}
		if (!"tor".equals(parts[0]))
		{
			throw new IllegalArgumentException("--tx-proxy knows one network, tor, not '"
				+ parts[0] + "' (ADR 0004)");
		}
		int maximum;
		try
		{
			maximum = parts.length == 3 ? Integer.parseInt(parts[2]) : DEFAULT_ANONYMITY_PEERS;
		}
		catch (NumberFormatException notANumber)
		{
			throw new IllegalArgumentException(
				"--tx-proxy is tor,host:port[,max], not '" + value + "'");
		}
		Outbound route = Outbound.through(PeerAddress.parse(parts[1]));
		node.anonymityZone(route, maximum);
		return ", anonymity zone " + route + ", at most " + maximum + " peer(s)";
	}

	/**
	 * The genesis block of an empty chain file: mined with --mine, otherwise taken from the first
	 * peer that answers
	 */
	private List<BlockBody> start(final List<PeerAddress> addresses, final Bytes beneficiary,
		final Outbound outbound, final PrintStream out) throws IOException
	{
		Optional<BlockBody> anchored = ConsensusRules.LETHENON.anchorFor(Chain.TEST_IDENTIFIER);
		if (anchored.isPresent())
		{
			out.println("started from the genesis block fixed in the code for "
				+ Chain.TEST_IDENTIFIER);
			return List.of(anchored.get());
		}
		if (beneficiary != null)
		{
			BlockBody genesis = Genesis.start(Chain.TEST_IDENTIFIER, pun, System.currentTimeMillis());
			out.println("mined the genesis block of " + Chain.TEST_IDENTIFIER);
			return List.of(genesis);
		}
		if (addresses.isEmpty())
		{
			throw new IllegalArgumentException(chain + " is empty: start a test chain with --mine, "
				+ "or take one from a node with --peer");
		}
		List<String> reasons = new ArrayList<>();
		for (PeerAddress address : addresses)
		{
			try
			{
				List<BlockBody> genesis = Bootstrap.genesisFrom(address, outbound);
				out.println("took the genesis block from the peer at " + address);
				return genesis;
			}
			catch (IOException unanswered)
			{
				reasons.add(unanswered.getMessage());
			}
		}
		throw new IOException("no peer gave a genesis block: " + String.join("; ", reasons));
	}

	private long runUntilStopped(final Node node, final Bytes beneficiary, final Stop stop,
		final PrintStream out)
	{
		Miner miner = beneficiary == null ? null : Miner.start(node, beneficiary, pun);
		try
		{
			long interval = statusSeconds * 1_000_000_000L;
			long deadline = seconds > 0 ? System.nanoTime() + seconds * 1_000_000_000L : 0L;
			long nextLine = statusSeconds > 0 ? System.nanoTime() + interval : 0L;
			int refusalsReported = 0;
			long wait;
			while ((wait = millisUntil(System.nanoTime(), deadline, nextLine)) >= 0
				&& !stop.await(wait))
			{
				if (nextLine != 0L && System.nanoTime() - nextLine >= 0)
				{
					out.println(statusLine(node, refusalsReported, miner));
					refusalsReported = node.refusals().size();
					nextLine += interval;
				}
			}
		}
		catch (InterruptedException interrupted)
		{
			Thread.currentThread().interrupt();
		}
		finally
		{
			if (miner != null)
			{
				miner.close();
			}
		}
		return miner == null ? 0L : miner.minedBlocks();
	}
}
