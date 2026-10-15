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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A peer as the command line names it
 *
 * @param host
 *            the host name or address, or a v3 onion address, which only a proxy reaches
 * @param port
 *            the TCP port
 */
public record PeerAddress(String host, int port)
{

	private static final String ONION_SUFFIX = ".onion";

	/** The name that always means this machine, as do its subdomains (RFC 6761, section 6.3) */
	private static final String LOCALHOST = "localhost";

	/** A v3 onion address: 56 characters of base32 (RFC 4648, a-z and 2-7) and the suffix */
	private static final Pattern V3_ONION = Pattern.compile("(?i)[a-z2-7]{56}\\.onion");

	/**
	 * Checks the address
	 *
	 * @throws IllegalArgumentException
	 *             for an empty host or a port outside 1 to 65535
	 */
	public PeerAddress
	{
		if (host == null || host.isBlank())
		{
			throw new IllegalArgumentException("a peer needs a host");
		}
		if (port < 1 || port > 65_535)
		{
			throw new IllegalArgumentException("a port is 1 to 65535, not " + port);
		}
		if (endsInOnion(host) && !V3_ONION.matcher(host).matches())
		{
			throw new IllegalArgumentException("an onion address has 56 base32 characters before "
				+ "'.onion' (Tor's version 3), and '" + host + "' has "
				+ (host.length() - ONION_SUFFIX.length()));
		}
	}

	/**
	 * Reads host:port
	 *
	 * @param text
	 *            the address, for example localhost:18480
	 * @return the address
	 * @throws IllegalArgumentException
	 *             when the text is not host:port
	 */
	public static PeerAddress parse(final String text)
	{
		int colon = text.lastIndexOf(':');
		if (colon <= 0 || colon == text.length() - 1)
		{
			throw new IllegalArgumentException("a peer is host:port, not '" + text + "'");
		}
		try
		{
			return new PeerAddress(text.substring(0, colon),
				Integer.parseInt(text.substring(colon + 1)));
		}
		catch (NumberFormatException notANumber)
		{
			throw new IllegalArgumentException("a peer is host:port, not '" + text + "'");
		}
	}

	/**
	 * Whether this is a Tor onion address, which only a proxy can reach
	 *
	 * @return true for a v3 onion address
	 */
	public boolean isOnion()
	{
		return endsInOnion(host);
	}

	/**
	 * Whether this address points into a machine or its local network, so that no other node
	 * reaches the one it names by it: a loopback, unspecified, private or link-local IP address, or
	 * the name localhost. A node neither keeps nor passes on such an address (#194), after Monero's
	 * {@code sanitize_peerlist} and {@code is_host_allowed}. Only an IP literal is read; a host
	 * name is never looked up, because the lookup would leave the node outside Tor.
	 *
	 * @return true for a local address
	 */
	public boolean isLocal()
	{
		String name = host.toLowerCase(Locale.ROOT);
		if (LOCALHOST.equals(name) || name.endsWith("." + LOCALHOST))
		{
			return true;
		}
		return literal().map(PeerAddress::isLocal).orElse(false);
	}

	/**
	 * The host as an IP address, if it is written as one: without the brackets of an IPv6 literal
	 * and without its scope
	 */
	private Optional<InetAddress> literal()
	{
		String text = host.startsWith("[") && host.endsWith("]")
			? host.substring(1, host.length() - 1)
			: host;
		int scope = text.indexOf('%');
		try
		{
			return Optional.of(InetAddress.ofLiteral(scope < 0 ? text : text.substring(0, scope)));
		}
		catch (IllegalArgumentException aName)
		{
			// a host name: it stays a name, and nothing is looked up
			return Optional.empty();
		}
	}

	private static boolean isLocal(final InetAddress address)
	{
		return address.isLoopbackAddress() || address.isAnyLocalAddress()
			|| address.isSiteLocalAddress() || address.isLinkLocalAddress()
			|| isUniqueLocal(address);
	}

	/** fc00::/7, the private range of IPv6 (RFC 4193), which the JDK has no test for */
	private static boolean isUniqueLocal(final InetAddress address)
	{
		return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
	}

	private static boolean endsInOnion(final String host)
	{
		return host.toLowerCase(Locale.ROOT).endsWith(ONION_SUFFIX);
	}

	@Override
	public String toString()
	{
		return host + ":" + port;
	}
}
