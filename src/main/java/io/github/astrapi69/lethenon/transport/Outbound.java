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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.Objects;

/**
 * How a connection to a peer leaves this machine: directly, or through a SOCKS5 proxy such as a
 * Tor daemon (ADR 0004, step 1).
 * <p>
 * Through a proxy, the peer's host is handed over unresolved, so the proxy resolves it and nothing
 * about the peer reaches the local DNS; the JDK's SOCKS client then sends it as a domain name. A
 * connection is never made around a proxy that is given. An onion address can only be reached
 * through one, and {@link #DIRECT} refuses it before anything touches the network.
 */
public final class Outbound
{

	/** How long a direct connection may take to open: Monero's 5000 ms */
	public static final int DIRECT_CONNECT_MILLIS = 5_000;

	/** How long a connection through a SOCKS proxy may take to open: Monero's 45 s (#127) */
	public static final int PROXIED_CONNECT_MILLIS = 45_000;

	/** Connections go out directly, as they did before Tor */
	public static final Outbound DIRECT = new Outbound(null);

	private final PeerAddress proxy;

	private Outbound(final PeerAddress proxy)
	{
		this.proxy = proxy;
	}

	/**
	 * Connections go out through the SOCKS5 proxy at the given address
	 *
	 * @param proxy
	 *            the proxy, for Tor usually 127.0.0.1:9050
	 * @return the route
	 */
	public static Outbound through(final PeerAddress proxy)
	{
		return new Outbound(Objects.requireNonNull(proxy, "a proxy"));
	}

	/**
	 * Whether connections go through a proxy
	 *
	 * @return true for {@link #through(PeerAddress)}
	 */
	public boolean proxied()
	{
		return proxy != null;
	}

	/**
	 * Opens a connection to a peer
	 *
	 * @param target
	 *            the peer
	 * @param timeoutMillis
	 *            how long the connection may take to open
	 * @return the connected socket
	 * @throws IOException
	 *             when the peer, or the proxy, cannot be reached, or an onion address is to be
	 *             reached without a proxy
	 */
	public Socket open(final PeerAddress target, final int timeoutMillis) throws IOException
	{
		if (proxy == null && target.isOnion())
		{
			throw new IOException("the onion address " + target + " is reached only through Tor, "
				+ "so only through Tor's SOCKS proxy, and this connection has none");
		}
		Socket socket = proxy == null ? new Socket()
			: new Socket(new Proxy(Proxy.Type.SOCKS,
				new InetSocketAddress(proxy.host(), proxy.port())));
		try
		{
			socket.connect(proxy == null ? new InetSocketAddress(target.host(), target.port())
				: InetSocketAddress.createUnresolved(target.host(), target.port()), timeoutMillis);
			return socket;
		}
		catch (IOException unreachable)
		{
			socket.close();
			throw unreachable;
		}
	}

	/**
	 * How long this route may take to open a connection.
	 * <p>
	 * Directly, five seconds, Monero's {@code P2P_DEFAULT_CONNECTION_TIMEOUT}. Through a SOCKS
	 * proxy, 45 seconds, Monero's {@code P2P_DEFAULT_SOCKS_CONNECT_TIMEOUT}: for an onion service
	 * Tor answers the CONNECT only after it has fetched the service's descriptor and built a
	 * rendezvous circuit, and on a first connection that took longer than five seconds in the first
	 * run against a real Tor (#127). The HELLO that follows keeps its {@link Node#HANDSHAKE_MILLIS}
	 *
	 * @return the connect time in milliseconds
	 */
	public int connectMillis()
	{
		return proxy == null ? DIRECT_CONNECT_MILLIS : PROXIED_CONNECT_MILLIS;
	}

	/**
	 * Opens a connection to a peer within this route's {@link #connectMillis()}
	 *
	 * @param target
	 *            the peer
	 * @return the connected socket
	 * @throws IOException
	 *             when the peer, or the proxy, cannot be reached in time, or an onion address is to
	 *             be reached without a proxy
	 */
	public Socket open(final PeerAddress target) throws IOException
	{
		return open(target, connectMillis());
	}

	@Override
	public String toString()
	{
		return proxy == null ? "direct" : "through the SOCKS5 proxy at " + proxy;
	}
}
