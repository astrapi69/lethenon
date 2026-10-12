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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.ChainRejected;
import io.github.astrapi69.lethenon.Replay;
import io.github.astrapi69.lethenon.SignedTransaction;
import io.github.astrapi69.lethenon.TransactionPool.Admission;

/**
 * A node of the test network (ADR 0003): it listens, connects to a fixed list of peers, and shakes
 * hands with every connection before anything else is exchanged.
 * <p>
 * A node runs only on the test chain {@link Chain#TEST_IDENTIFIER}: it refuses a main chain at
 * start and a peer that names any other chain or another genesis block. Each connection runs on a
 * virtual thread of its own.
 * <p>
 * A node relays what it accepts: a block that extends its chain and verifies, and a transfer its
 * pool admits, to every peer except the one it came from. A block whose parent it does not know
 * makes it ask that peer for its chain and fetch the missing blocks. Every chain it adopts is
 * checked whole with {@link Replay#verify}. A peer that sends bytes that do not decode, a message
 * out of place, or a block that does not verify is disconnected.
 */
public final class Node implements AutoCloseable
{

	/** How long a peer has to say HELLO: Monero's handshake timeout of 5000 ms (ADR 0003) */
	public static final int HANDSHAKE_MILLIS = 5_000;

	/** How long a request may wait for its answer: Monero's two-minute invoke timeout (ADR 0003) */
	public static final int ANSWER_MILLIS = 120_000;

	/** The most connections a node opens: Monero's default of 12 (ADR 0003) */
	public static final int MAXIMUM_OUTGOING = 12;

	/** The most connections a node accepts (ADR 0003) */
	public static final int MAXIMUM_INCOMING = 16;

	private final AtomicInteger outgoing = new AtomicInteger();

	private final AtomicInteger incoming = new AtomicInteger();

	private volatile int answerMillis = ANSWER_MILLIS;

	private final List<Peer> peers = new CopyOnWriteArrayList<>();

	/** Dialled addresses whose handshake has not ended yet (#117) */
	private final Set<PeerAddress> handshaking = ConcurrentHashMap.newKeySet();

	/** Held while a peer is checked against the others and added (#117) */
	private final Object admitting = new Object();

	private final List<String> refusals = new CopyOnWriteArrayList<>();

	private final List<Socket> open = new CopyOnWriteArrayList<>();

	private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

	private final LocalChain local;

	private final Gossip gossip;

	private ServerSocket server;

	private final long nodeId = drawNodeId();

	private final Discovery discovery;

	private volatile int listenPort;

	private volatile Outbound outbound = Outbound.DIRECT;

	private volatile AnonymityZone zone;

	private volatile ServerSocket anonymousServer;

	private volatile PeerAddress announcedOnion;

	private final AtomicInteger anonymousIncoming = new AtomicInteger();

	private volatile boolean closed;

	/** The peers given with {@code --peer}, dialled again while they are not connected (#128) */
	private volatile Redials redials = Redials.standard();

	private final AtomicBoolean redialing = new AtomicBoolean();

	private Node(final List<BlockBody> chain, final Replay replay, final ChainFile file)
	{
		this.local = new LocalChain(chain, replay);
		this.discovery = new Discovery(peers, refusals, new Discovery.Dialer()
		{
			@Override
			public int outgoing()
			{
				return outgoing.get();
			}

			@Override
			public void dial(final PeerAddress address) throws IOException
			{
				connect(address.host(), address.port());
			}

			@Override
			public Set<PeerAddress> handshaking()
			{
				return Set.copyOf(handshaking);
			}

			@Override
			public int maximum()
			{
				return MAXIMUM_OUTGOING;
			}
		}, threads);
		this.gossip = new Gossip(local, file, peers, refusals, discovery);
	}

	/**
	 * A random, non-zero identity for this run of the node, so that a connection to itself is
	 * recognised (Monero's {@code peer_id})
	 */
	private static long drawNodeId()
	{
		long drawn = 0L;
		while (drawn == 0L)
		{
			drawn = new SecureRandom().nextLong();
		}
		return drawn;
	}

	/**
	 * A node on the given chain
	 *
	 * @param chain
	 *            the chain, genesis first, on {@link Chain#TEST_IDENTIFIER}
	 * @return the node, not yet listening or connected
	 * @throws IllegalArgumentException
	 *             for an empty chain or one that is not the test chain
	 * @throws ChainRejected
	 *             when the chain does not verify
	 */
	public static Node on(final List<BlockBody> chain)
	{
		requireTestChain(chain);
		return new Node(chain, Replay.verify(chain), null);
	}

	/**
	 * A node that serves a chain file: it starts from the chain and the pool in it, and writes
	 * every chain it adopts and every change of its pool back, in the formats every command reads.
	 * Each waiting transfer is offered to the pool again at start, so a double spend or a transfer
	 * that no longer fits is dropped, with the reason in {@link #refusals()}, and the file
	 * rewritten.
	 *
	 * @param file
	 *            the chain file, on {@link Chain#TEST_IDENTIFIER}; it belongs to the node while the
	 *            node runs
	 * @return the node, not yet listening or connected
	 * @throws IOException
	 *             when the file cannot be read or written
	 * @throws IllegalArgumentException
	 *             for an empty chain or one that is not the test chain
	 * @throws ChainRejected
	 *             when the chain does not verify
	 */
	public static Node serving(final ChainFile file) throws IOException
	{
		List<BlockBody> chain = file.require();
		requireTestChain(chain);
		Node node = new Node(chain, Replay.verify(chain), file);
		for (SignedTransaction waiting : file.readPending())
		{
			node.gossip.admit(waiting, null);
		}
		node.gossip.writePool();
		return node;
	}

	private static void requireTestChain(final List<BlockBody> chain)
	{
		if (chain.isEmpty())
		{
			throw new IllegalArgumentException("a node needs a chain with a genesis block");
		}
		String identifier = chain.getFirst().chainIdentifier();
		Optional<String> retired = Chain.retiredBecause(identifier);
		if (retired.isPresent())
		{
			throw new IllegalArgumentException(retired.get());
		}
		if (!Chain.TEST_IDENTIFIER.equals(identifier))
		{
			throw new IllegalArgumentException("a node runs only on '" + Chain.TEST_IDENTIFIER
				+ "', and this chain is '" + identifier + "' (ADR 0003)");
		}
	}

	/**
	 * Starts accepting connections: on every interface, or on loopback only when this node dials
	 * through a proxy ({@link #dialingThrough(Outbound)}), so that a node meant to be reached over
	 * Tor is not reachable around it
	 *
	 * @param port
	 *            the TCP port, 0 for any free one
	 * @return the port it listens on
	 * @throws IOException
	 *             when the port cannot be bound
	 */
	public int listen(final int port) throws IOException
	{
		return listen(outbound.proxied() ? InetAddress.getLoopbackAddress()
			: new InetSocketAddress(0).getAddress(), port);
	}

	/**
	 * Connects to one peer and shakes hands with it on a thread of its own
	 *
	 * @param host
	 *            the peer's host
	 * @param port
	 *            the peer's port
	 * @throws IOException
	 *             when the peer cannot be reached within the route's connect time
	 *             ({@link Outbound#connectMillis()}); a {@link NoRoom} when this node has
	 *             {@link #MAXIMUM_OUTGOING} outgoing connections already, or the anonymity zone its
	 *             maximum, which says nothing about the address (#126)
	 */
	public void connect(final String host, final int port) throws IOException
	{
		PeerAddress dialed = new PeerAddress(host, port);
		AnonymityZone anonymous = zone;
		if (anonymous != null && dialed.isOnion())
		{
			connectAnonymously(anonymous, dialed);
			return;
		}
		if (outgoing.incrementAndGet() > MAXIMUM_OUTGOING)
		{
			outgoing.decrementAndGet();
			throw new NoRoom("this node has " + MAXIMUM_OUTGOING
				+ " outgoing connections, its limit (ADR 0003)");
		}
		Socket socket;
		try
		{
			socket = outbound.open(dialed);
		}
		catch (IOException unreachable)
		{
			outgoing.decrementAndGet();
			throw unreachable;
		}
		handshaking.add(dialed);
		threads.submit(() -> serve(socket, outgoing, Optional.of(dialed)));
	}

	/**
	 * Connects to every peer on a fixed list, in its order; a peer that cannot be reached is
	 * recorded as a refusal and does not stop the others. Each of them is dialled again while it is
	 * not connected, after a pause that grows with every failed attempt, and a failure is recorded
	 * once rather than on every attempt (#128)
	 *
	 * @param addresses
	 *            the peers
	 */
	public void connectAll(final List<PeerAddress> addresses)
	{
		for (PeerAddress address : addresses)
		{
			dialConfigured(address);
		}
		if (!addresses.isEmpty() && redialing.compareAndSet(false, true))
		{
			threads.submit(this::redial);
		}
	}

	private void dialConfigured(final PeerAddress address)
	{
		redials.dialling(address);
		try
		{
			connect(address.host(), address.port());
		}
		catch (NoRoom full)
		{
			if (redials.full(address, nowMillis()))
			{
				refusals.add(address + " was not connected: " + full.getMessage()
					+ "; it is dialled again on the next round, its pause unchanged");
			}
		}
		catch (IOException notConnected)
		{
			if (redials.failed(address, nowMillis()))
			{
				refusals.add(address + " was not connected: " + notConnected.getMessage()
					+ "; it is dialled again, after a longer pause each time it fails");
			}
		}
	}

	/**
	 * Dials the configured peers whose pause is over, until the node closes
	 */
	private void redial()
	{
		while (!closed)
		{
			try
			{
				Thread.sleep(redials.tick());
				for (PeerAddress due : redials.due(nowMillis()))
				{
					dialConfigured(due);
				}
			}
			catch (InterruptedException | RejectedExecutionException closing)
			{
				return;
			}
		}
	}

	/**
	 * Sets the pauses before a configured peer is dialled again, for peers given after this call;
	 * tests use it so as not to wait seconds
	 */
	Node redialingAfter(final Duration first, final Duration longest)
	{
		redials = new Redials(first, longest);
		return this;
	}

	private static long nowMillis()
	{
		return System.nanoTime() / 1_000_000L;
	}

	/**
	 * Tells the configured peers how a connection to one of them ended
	 *
	 * @param admitted
	 *            whether the connection got through its handshake
	 */
	private void endedDial(final Optional<PeerAddress> dialed, final boolean admitted)
	{
		if (dialed.isEmpty() || !redials.isConfigured(dialed.get()))
		{
			return;
		}
		if (admitted)
		{
			redials.disconnected(dialed.get(), nowMillis());
		}
		else
		{
			redials.failed(dialed.get(), nowMillis());
		}
	}

	/**
	 * The HELLOs of the peers this node has shaken hands with and is still connected to
	 *
	 * @return a snapshot
	 */
	public List<Hello> peers()
	{
		List<Hello> hellos = new ArrayList<>();
		for (Peer peer : peers)
		{
			hellos.add(peer.hello());
		}
		return List.copyOf(hellos);
	}

	/**
	 * How many outgoing connections this node holds against its limit of
	 * {@link #MAXIMUM_OUTGOING}, those still in their handshake included
	 *
	 * @return the count at this moment
	 */
	public int outgoingConnections()
	{
		return outgoing.get();
	}

	/**
	 * How many incoming connections this node holds against its limit of
	 * {@link #MAXIMUM_INCOMING}, those still in their handshake included
	 *
	 * @return the count at this moment
	 */
	public int incomingConnections()
	{
		return incoming.get();
	}

	/**
	 * Why connections were refused, oldest first
	 *
	 * @return a snapshot
	 */
	public List<String> refusals()
	{
		return List.copyOf(refusals);
	}

	/**
	 * The node's chain
	 *
	 * @return a snapshot, genesis first
	 */
	public List<BlockBody> chain()
	{
		return local.blocks();
	}

	private void accept()
	{
		ServerSocket socket = server;
		while (!socket.isClosed())
		{
			try
			{
				Socket accepted = socket.accept();
				if (incoming.incrementAndGet() > MAXIMUM_INCOMING)
				{
					incoming.decrementAndGet();
					refusals.add(accepted.getRemoteSocketAddress() + " was refused: this node has "
						+ MAXIMUM_INCOMING + " incoming connections, its limit (ADR 0003)");
					closeQuietly(accepted);
					continue;
				}
				threads.submit(() -> serve(accepted, incoming, Optional.empty()));
			}
			catch (IOException closed)
			{
				return;
			}
		}
	}

	/**
	 * Runs one connection to its end; the counter it was admitted under is released then
	 *
	 * @param dialed
	 *            the address this node dialled, empty for a connection it accepted
	 */
	private void serve(final Socket socket, final AtomicInteger counted,
		final Optional<PeerAddress> dialed)
	{
		open.add(socket);
		Peer peer = null;
		boolean admitted = false;
		try
		{
			try
			{
				peer = shakeHands(socket, dialed);
				if (peer == null || !admit(peer))
				{
					return;
				}
				admitted = true;
				dialed.ifPresent(redials::connected);
			}
			finally
			{
				dialed.ifPresent(handshaking::remove);
			}
			peer.startWriting(threads);
			discovery.connected(peer);
			gossip.askIfAhead(peer);
			while (!socket.isClosed())
			{
				gossip.handle(peer, Frames.read(peer.in(), Frames.MAXIMUM_FRAME));
			}
		}
		catch (IOException ended)
		{
			recordTheEnd(socket, peer, ended);
		}
		finally
		{
			if (peer != null)
			{
				peers.remove(peer);
				closeQuietly(peer);
			}
			open.remove(socket);
			closeQuietly(socket);
			counted.decrementAndGet();
			endedDial(dialed, admitted);
			if (peer == null)
			{
				// a dialled address that did not complete a handshake is not dialled again
				dialed.ifPresent(discovery::unreachable);
			}
			if (counted == outgoing)
			{
				refill();
			}
		}
	}

	/**
	 * An outgoing slot is free again: discovery fills it from the addresses it knows, instead of
	 * waiting for a PEERS answer that may not come (#121)
	 */
	private void refill()
	{
		if (closed)
		{
			return;
		}
		try
		{
			discovery.fill();
		}
		catch (RejectedExecutionException closing)
		{
			// the node closed between the check and the dial; there is nothing left to fill
		}
	}

	/**
	 * Records why a connection ended, when it ended for a reason: a handshake that failed, a
	 * protocol violation, a peer that did not answer
	 */
	private void recordTheEnd(final Socket socket, final Peer peer, final IOException ended)
	{
		if (peer == null)
		{
			refusals.add(socket.getRemoteSocketAddress() + " ended the handshake: "
				+ ended.getClass().getSimpleName() + " " + ended.getMessage());
		}
		else if (ended instanceof ProtocolViolation violation)
		{
			refusals.add(peer.address() + " was disconnected: " + violation.getMessage());
		}
		else if (ended instanceof SocketTimeoutException)
		{
			refusals.add(peer.address() + " did not answer within " + answerMillis
				+ " ms and was disconnected");
		}
	}

	/**
	 * Adds a peer unless this node already has a connection to the same node identity (#117).
	 * Of two connections between the same two nodes, both ends keep the one dialled by the node
	 * with the smaller identity, and of two that are equally preferred, the older one. Whether a
	 * connection is preferred follows from the two identities and its own direction alone, so
	 * both ends decide alike in whichever order their handshakes end, also while the other
	 * connection is already being closed. Callers without an identity are commands, not nodes,
	 * and are never duplicates.
	 *
	 * @return whether the peer was added; when not, the caller closes its connection
	 */
	private boolean admit(final Peer peer)
	{
		long theirs = peer.hello().nodeId();
		synchronized (admitting)
		{
			Optional<Peer> existing = theirs == 0L ? Optional.empty()
				: peers.stream().filter(other -> other.hello().nodeId() == theirs).findFirst();
			if (existing.isEmpty())
			{
				peers.add(peer);
				return true;
			}
			if (!preferred(peer) || preferred(existing.get()))
			{
				refusals.add(peer.address() + " is already a peer over another connection; this "
					+ "one is closed (#117)");
				return false;
			}
			peers.remove(existing.get());
			closeQuietly(existing.get());
			peers.add(peer);
			refusals.add(existing.get().address() + " is a peer over another connection, which "
				+ "both ends keep; this one is closed (#117)");
			return true;
		}
	}

	/**
	 * Whether a connection is the one both ends keep: the one dialled by the node with the
	 * smaller identity
	 */
	private boolean preferred(final Peer peer)
	{
		return peer.dialledHere() == nodeId < peer.hello().nodeId();
	}

	private Peer shakeHands(final Socket socket, final Optional<PeerAddress> dialed)
		throws IOException
	{
		return shakeHands(socket, Hello.of(chain(), listenPort, nodeId), dialed, false);
	}

	/**
	 * Sends this node's HELLO, reads the peer's and checks it
	 *
	 * @param dialed
	 *            the address this node dialled, empty for a connection it accepted
	 * @param inTheZone
	 *            whether the connection belongs to the anonymity zone, where an onion address
	 *            is expected, rather than to the clearnet, where it is refused
	 * @return the peer, or null when the handshake was refused, with the reason recorded
	 */
	private Peer shakeHands(final Socket socket, final Hello ours,
		final Optional<PeerAddress> dialed, final boolean inTheZone) throws IOException
	{
		socket.setSoTimeout(HANDSHAKE_MILLIS);
		DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
		DataOutputStream out = new DataOutputStream(
			new BufferedOutputStream(socket.getOutputStream()));
		Frames.write(out, new Frame(MessageType.HELLO, ours.encode()));
		Frame first = Frames.read(in, Frames.MAXIMUM_FRAME);
		if (first.type() != MessageType.HELLO)
		{
			refusals.add(socket.getRemoteSocketAddress() + " sent " + first.type()
				+ " before HELLO");
			return null;
		}
		Hello theirs = Hello.decode(first.payload());
		AnonymityZone anonymous = zone;
		if (theirs.nodeId() == nodeId
			|| anonymous != null && theirs.nodeId() == anonymous.nodeId())
		{
			refusals.add(socket.getRemoteSocketAddress() + " is this node itself");
			dialed.ifPresent(inTheZone ? zone.discovery()::isItself : discovery::isItself);
			return null;
		}
		if (!inTheZone && !theirs.onionHost().isEmpty())
		{
			refusals.add(socket.getRemoteSocketAddress() + " announced an onion address on a "
				+ "clearnet connection, which would link its IP address to it (ADR 0004)");
			return null;
		}
		Optional<String> refusal = refusalOf(theirs);
		if (refusal.isPresent())
		{
			refusals.add(socket.getRemoteSocketAddress() + ": " + refusal.get());
			return null;
		}
		socket.setSoTimeout(0);
		return new Peer(socket, in, out, theirs, answerMillis, refusals::add,
			inTheZone ? announcedOnionOf(theirs, dialed) : listeningAddress(socket, theirs, dialed),
			dialed.isPresent());
	}

	/**
	 * Where a peer can be reached: the address this node dialled, or for a connection it
	 * accepted, the peer's host with the port the peer announced; empty for a peer that does
	 * not listen, which is then never passed on
	 */
	/**
	 * Where a zone peer can be reached: the onion address this node dialled, or the one the peer
	 * announced; never the loopback address its connection arrives from
	 */
	private static Optional<PeerAddress> announcedOnionOf(final Hello theirs,
		final Optional<PeerAddress> dialed)
	{
		if (dialed.isPresent())
		{
			return dialed;
		}
		if (theirs.onionHost().isEmpty() || theirs.listenPort() == 0)
		{
			return Optional.empty();
		}
		return Optional.of(new PeerAddress(theirs.onionHost(), theirs.listenPort()));
	}

	private static Optional<PeerAddress> listeningAddress(final Socket socket, final Hello theirs,
		final Optional<PeerAddress> dialed)
	{
		if (dialed.isPresent())
		{
			return dialed;
		}
		if (theirs.listenPort() == 0)
		{
			return Optional.empty();
		}
		return Optional.of(
			new PeerAddress(socket.getInetAddress().getHostAddress(), theirs.listenPort()));
	}

	/**
	 * How this node's outgoing connections leave the machine, given peers and learnt ones alike;
	 * {@link Outbound#DIRECT} by default. Through a proxy, {@link #listen(int)} binds loopback only.
	 *
	 * @param route
	 *            the route
	 * @return this node
	 */
	public Node dialingThrough(final Outbound route)
	{
		this.outbound = Objects.requireNonNull(route, "an outbound route");
		return this;
	}

	/**
	 * Starts accepting connections on the given address only
	 *
	 * @param bind
	 *            the local address to listen on, for example the loopback address an onion service
	 *            forwards to
	 * @param port
	 *            the TCP port, 0 for any free one
	 * @return the port it listens on
	 * @throws IOException
	 *             when the port cannot be bound
	 */
	public int listen(final InetAddress bind, final int port) throws IOException
	{
		ServerSocket socket = new ServerSocket();
		socket.bind(new InetSocketAddress(bind, port));
		server = socket;
		listenPort = socket.getLocalPort();
		threads.submit(this::accept);
		return listenPort;
	}

	/**
	 * The local address this node listens on
	 *
	 * @return the address, the wildcard address for every interface
	 * @throws IllegalStateException
	 *             when the node does not listen
	 */
	public InetAddress listeningOn()
	{
		if (server == null)
		{
			throw new IllegalStateException("this node does not listen");
		}
		return server.getInetAddress();
	}

	/**
	 * Turns on an anonymity zone, after Monero's {@code --tx-proxy} (ADR 0004, step 2): onion
	 * peers are reached through the given route and carry transfers only, and a transfer that
	 * originates on this node goes to them alone, or waits for one, never in the clear
	 *
	 * @param route
	 *            how zone connections leave the machine, a Tor SOCKS proxy
	 * @param maximum
	 *            the most peers the zone takes
	 * @return this node
	 * @throws IllegalArgumentException
	 *             for a direct route, which cannot reach an onion address, or a maximum below 1
	 */
	public Node anonymityZone(final Outbound route, final int maximum)
	{
		if (!route.proxied())
		{
			throw new IllegalArgumentException("an anonymity zone needs a proxy to reach onion "
				+ "addresses, and the route is " + route);
		}
		if (maximum < 1)
		{
			throw new IllegalArgumentException(
				"an anonymity zone takes at least one peer, not " + maximum);
		}
		long anonymousId = drawNodeId();
		while (anonymousId == nodeId)
		{
			anonymousId = drawNodeId();
		}
		AnonymityZone anonymous = new AnonymityZone(route, maximum, anonymousId);
		anonymous.discovery(new Discovery(anonymous.peers(), refusals, zoneDialer(anonymous),
			threads));
		zone = anonymous;
		gossip.anonymityZone(anonymous);
		return this;
	}

	/**
	 * How peer exchange in the zone opens connections: to onion addresses only, through the zone
	 */
	private Discovery.Dialer zoneDialer(final AnonymityZone anonymous)
	{
		return new Discovery.Dialer()
		{
			@Override
			public int outgoing()
			{
				return anonymous.outgoing();
			}

			@Override
			public int maximum()
			{
				return anonymous.maximum();
			}

			@Override
			public void dial(final PeerAddress address) throws IOException
			{
				if (!address.isOnion())
				{
					throw new IOException("the anonymity zone dials onion addresses only");
				}
				connectAnonymously(anonymous, address);
			}

			@Override
			public Set<PeerAddress> handshaking()
			{
				return Set.copyOf(anonymous.handshaking());
			}
		};
	}

	/**
	 * Accepts connections from this node's onion service, after Monero's
	 * {@code --anonymous-inbound} (ADR 0004, step 3): on loopback only, where Tor's
	 * {@code HiddenServicePort} forwards, and every peer accepted there belongs to the anonymity
	 * zone. The onion address is announced in the zone's HELLO and nowhere else.
	 *
	 * @param onion
	 *            the onion service's address, as Tor's {@code hostname} file names it, and its port
	 * @param localPort
	 *            the loopback port Tor forwards to, 0 for any free one
	 * @param maximum
	 *            the most peers it accepts at once
	 * @return the loopback port it listens on
	 * @throws IOException
	 *             when the port cannot be bound
	 * @throws IllegalStateException
	 *             without an anonymity zone, which the onion service's peers belong to
	 * @throws IllegalArgumentException
	 *             for an address that is not an onion address, or a maximum below 1
	 */
	public int listenAnonymously(final PeerAddress onion, final int localPort, final int maximum)
		throws IOException
	{
		AnonymityZone anonymous = zone;
		if (anonymous == null)
		{
			throw new IllegalStateException("an onion service's peers belong to the anonymity "
				+ "zone, so it needs one first: --tx-proxy (ADR 0004)");
		}
		if (!onion.isOnion())
		{
			throw new IllegalArgumentException(
				"an onion service has an onion address, not " + onion);
		}
		if (maximum < 1)
		{
			throw new IllegalArgumentException(
				"an onion service takes at least one peer, not " + maximum);
		}
		ServerSocket socket = new ServerSocket();
		socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort));
		anonymousServer = socket;
		announcedOnion = onion;
		threads.submit(() -> acceptAnonymously(anonymous, socket, maximum));
		return socket.getLocalPort();
	}

	/**
	 * The local address the onion service's listener is bound to
	 *
	 * @return the loopback address
	 * @throws IllegalStateException
	 *             when the node has no onion service
	 */
	public InetAddress anonymouslyListeningOn()
	{
		if (anonymousServer == null)
		{
			throw new IllegalStateException("this node has no onion service");
		}
		return anonymousServer.getInetAddress();
	}

	private void acceptAnonymously(final AnonymityZone anonymous, final ServerSocket socket,
		final int maximum)
	{
		while (!socket.isClosed())
		{
			try
			{
				Socket accepted = socket.accept();
				if (anonymousIncoming.incrementAndGet() > maximum)
				{
					anonymousIncoming.decrementAndGet();
					refusals.add("a connection through the onion service was refused: it has "
						+ maximum + " connections, its limit (ADR 0004)");
					closeQuietly(accepted);
					continue;
				}
				threads.submit(() -> serveAnonymously(anonymous, accepted, Optional.empty(),
					anonymousIncoming::decrementAndGet));
			}
			catch (IOException closed)
			{
				return;
			}
		}
	}

	/**
	 * The HELLOs of the peers of the anonymity zone this node is connected to
	 *
	 * @return a snapshot; empty without a zone
	 */
	public List<Hello> anonymousPeers()
	{
		AnonymityZone anonymous = zone;
		if (anonymous == null)
		{
			return List.of();
		}
		return anonymous.peers().stream().map(Peer::hello).toList();
	}

	private void connectAnonymously(final AnonymityZone anonymous, final PeerAddress dialed)
		throws IOException
	{
		if (!anonymous.reserve())
		{
			throw new NoRoom("the anonymity zone has " + anonymous.maximum()
				+ " anonymity connection(s), its limit (ADR 0004)");
		}
		Socket socket;
		try
		{
			socket = anonymous.route().open(dialed);
		}
		catch (IOException unreachable)
		{
			anonymous.release();
			throw unreachable;
		}
		anonymous.handshaking().add(dialed);
		threads.submit(() -> serveAnonymously(anonymous, socket, Optional.of(dialed),
			anonymous::release));
	}

	/**
	 * Runs one connection of the anonymity zone to its end: a HELLO that names only the genesis
	 * block, under the zone's own identity, with the onion service's address and port where the
	 * node has one, then transfers and onion addresses only
	 *
	 * @param dialed
	 *            the onion address this node dialled, empty for a connection through its own
	 *            onion service
	 * @param release
	 *            frees the slot the connection was admitted under
	 */
	private void serveAnonymously(final AnonymityZone anonymous, final Socket socket,
		final Optional<PeerAddress> dialed, final Runnable release)
	{
		open.add(socket);
		Peer peer = null;
		try
		{
			try
			{
				peer = shakeHands(socket, zoneHello(anonymous), dialed, true);
			}
			finally
			{
				dialed.ifPresent(anonymous.handshaking()::remove);
			}
			if (peer == null)
			{
				return;
			}
			dialed.ifPresent(redials::connected);
			peer.startWriting(threads);
			anonymous.joined(peer);
			anonymous.discovery().connected(peer);
			while (!socket.isClosed())
			{
				gossip.handleAnonymous(peer, Frames.read(peer.in(), Frames.MAXIMUM_FRAME));
			}
		}
		catch (IOException ended)
		{
			recordTheEnd(socket, peer, ended);
		}
		finally
		{
			if (peer != null)
			{
				anonymous.left(peer);
				closeQuietly(peer);
			}
			open.remove(socket);
			closeQuietly(socket);
			release.run();
			endedDial(dialed, peer != null);
			if (dialed.isPresent())
			{
				refillTheZone(anonymous, dialed.get(), peer == null);
			}
		}
	}

	/**
	 * An outgoing slot of the zone is free again, as for clearnet peers (#121): an onion address
	 * that never completed a handshake is forgotten, and peer exchange inside the zone fills the
	 * slot from the onion addresses it knows
	 */
	private void refillTheZone(final AnonymityZone anonymous, final PeerAddress dialed,
		final boolean withoutAHandshake)
	{
		if (withoutAHandshake)
		{
			anonymous.discovery().unreachable(dialed);
		}
		if (closed)
		{
			return;
		}
		try
		{
			anonymous.discovery().fill();
		}
		catch (RejectedExecutionException closing)
		{
			// the node closed between the check and the dial; there is nothing left to fill
		}
	}

	/**
	 * The HELLO of the zone: the genesis block as the tip, the zone's identity, and the onion
	 * service's address and port where the node has one, port 0 and no address otherwise
	 */
	private Hello zoneHello(final AnonymityZone anonymous)
	{
		Hello genesisOnly = Hello.of(List.of(chain().getFirst()), 0, anonymous.nodeId());
		PeerAddress onion = announcedOnion;
		return onion == null ? genesisOnly
			: new Hello(genesisOnly.protocolVersion(), genesisOnly.chainIdentifier(),
				genesisOnly.genesisHash(), genesisOnly.bestHeight(), genesisOnly.bestHash(),
				genesisOnly.work(), onion.port(), anonymous.nodeId(), onion.host());
	}

	/**
	 * Whether this node connects to addresses it learns from its peers; on by default. Off, it
	 * stays with the peers it was given and still passes on the addresses it knows.
	 *
	 * @param enabled
	 *            whether to dial learnt addresses
	 * @return this node
	 */
	public Node discoverPeers(final boolean enabled)
	{
		discovery.dialling(enabled);
		return this;
	}

	/**
	 * Addresses this node has heard of from its peers and not yet been connected to
	 *
	 * @return a snapshot
	 */
	public List<PeerAddress> heardOf()
	{
		return discovery.heardOf();
	}

	private Optional<String> refusalOf(final Hello theirs)
	{
		Hello ours = Hello.of(chain());
		if (theirs.protocolVersion() != Hello.PROTOCOL_VERSION)
		{
			return Optional.of("the peer speaks protocol version " + theirs.protocolVersion()
				+ " and this node version " + Hello.PROTOCOL_VERSION);
		}
		if (!Chain.TEST_IDENTIFIER.equals(theirs.chainIdentifier()))
		{
			return Optional.of("the peer is on chain '" + theirs.chainIdentifier()
				+ "', and this node runs only on '" + Chain.TEST_IDENTIFIER + "'");
		}
		if (!ours.genesisHash().equals(theirs.genesisHash()))
		{
			return Optional.of("the peer's genesis block is " + theirs.genesisHash()
				+ " and this node's is " + ours.genesisHash());
		}
		return Optional.empty();
	}

	/**
	 * Offers a block this node mined or was handed: it is adopted if it extends the chain and the
	 * chain still verifies, and then relayed to every peer
	 *
	 * @param block
	 *            the block
	 * @return whether the chain now ends with it; the reason for a refusal is in
	 *         {@link #refusals()}
	 */
	public boolean submitBlock(final BlockBody block)
	{
		return gossip.submitBlock(block);
	}

	/**
	 * Offers a transfer to the node's pool and relays it to every peer when it is admitted
	 *
	 * @param transfer
	 *            the signed transfer
	 * @return the pool's answer
	 */
	public Admission submitTransfer(final SignedTransaction transfer)
	{
		return gossip.admit(transfer, null);
	}

	/**
	 * The transfers waiting in the node's pool, in the order a block can carry them
	 *
	 * @return a snapshot
	 */
	public List<SignedTransaction> pending()
	{
		return local.pool().waiting();
	}

	/**
	 * Sets how long a request may wait for its answer, for connections made after this call;
	 * tests use it so as not to wait two minutes
	 */
	void answerWithin(final int millis)
	{
		answerMillis = millis;
	}

	/**
	 * Stops listening, closes every connection and its thread
	 */
	@Override
	public void close()
	{
		closed = true;
		if (server != null)
		{
			closeQuietly(server);
		}
		if (anonymousServer != null)
		{
			closeQuietly(anonymousServer);
		}
		for (Socket socket : open)
		{
			closeQuietly(socket);
		}
		threads.shutdownNow();
	}

	private static void closeQuietly(final java.io.Closeable closeable)
	{
		try
		{
			closeable.close();
		}
		catch (IOException alreadyGone)
		{
			// closing a socket that is already closed or broken has nothing left to do
		}
	}
}
