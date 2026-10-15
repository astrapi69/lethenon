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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A node neither learns nor passes on an address that points into a machine or its local
 * network, whatever its configuration (#194), after Monero's {@code sanitize_peerlist}
 * ({@code src/p2p/net_node.inl}) and {@code peerlist_manager::is_host_allowed}
 * ({@code src/p2p/net_peerlist.h}), master {@code fe786010b76b}
 */
class LocalAddressesTest
{

	/** A host, and why it is in the list */
	record Host(String name, String host)
	{
		@Override
		public String toString()
		{
			return name;
		}
	}

	static Stream<Host> localHosts()
	{
		return Stream.of(new Host("127.0.0.1, loopback", "127.0.0.1"),
			new Host("10.0.0.1, private 10.0.0.0/8", "10.0.0.1"),
			new Host("10.0.0.0, first of 10.0.0.0/8", "10.0.0.0"),
			new Host("10.255.255.255, last of 10.0.0.0/8", "10.255.255.255"),
			new Host("127.255.255.254, end of loopback 127.0.0.0/8", "127.255.255.254"),
			new Host("172.16.0.0, first of 172.16.0.0/12", "172.16.0.0"),
			new Host("172.31.255.255, last of 172.16.0.0/12", "172.31.255.255"),
			new Host("192.168.0.1, private 192.168.0.0/16", "192.168.0.1"),
			new Host("169.254.1.1, link-local", "169.254.1.1"),
			new Host("0.0.0.0, unspecified", "0.0.0.0"), new Host("::1, IPv6 loopback", "::1"),
			new Host("[::1], IPv6 loopback in brackets", "[::1]"),
			new Host("::, IPv6 unspecified", "::"), new Host("fe80::1, IPv6 link-local", "fe80::1"),
			new Host("fe80::1%1, IPv6 link-local with a scope", "fe80::1%1"),
			new Host("fd12:3456::1, IPv6 unique local fc00::/7", "fd12:3456::1"),
			new Host("fc00::1, first of fc00::/7", "fc00::1"),
			new Host("::ffff:10.0.0.1, a private IPv4 address mapped into IPv6", "::ffff:10.0.0.1"),
			new Host("localhost", "localhost"), new Host("LocalHost, in any case", "LocalHost"),
			new Host("node.localhost, a subdomain of localhost (RFC 6761)", "node.localhost"));
	}

	static Stream<Host> reachableHosts()
	{
		return Stream.of(new Host("198.51.100.7, a public address", "198.51.100.7"),
			new Host("9.255.255.255, just below 10.0.0.0/8", "9.255.255.255"),
			new Host("11.0.0.0, just above 10.0.0.0/8", "11.0.0.0"),
			new Host("126.255.255.255, just below loopback", "126.255.255.255"),
			new Host("128.0.0.0, just above loopback", "128.0.0.0"),
			new Host("172.15.255.255, just below 172.16.0.0/12", "172.15.255.255"),
			new Host("172.32.0.0, just above 172.16.0.0/12", "172.32.0.0"),
			new Host("192.167.255.255, just below 192.168.0.0/16", "192.167.255.255"),
			new Host("192.169.0.0, just above 192.168.0.0/16", "192.169.0.0"),
			new Host("169.253.255.255, just below link-local", "169.253.255.255"),
			new Host("169.255.0.0, just above link-local", "169.255.0.0"),
			new Host("2001:db8::1, a public IPv6 address", "2001:db8::1"),
			new Host("fbff::1, just below fc00::/7", "fbff::1"),
			new Host("an onion address", "a".repeat(56) + ".onion"),
			new Host("seed.example.org, a DNS name, not looked up", "seed.example.org"),
			new Host("notlocalhost, a name that only ends in localhost", "notlocalhost"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("localHosts")
	void aLocalAddress_isNeitherKeptNorPassedOn(final Host local)
	{
		PeerBook book = new PeerBook();

		book.heard(List.of(new PeerAddress(local.host(), 18480)));
		book.confirmed(new PeerAddress(local.host(), 18481));

		assertEquals(List.of(), book.heardOf());
		assertEquals(List.of(), book.shareable());
		assertEquals(Optional.empty(), book.candidate(Set.of()));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("reachableHosts")
	void aReachableAddress_isKeptAndPassedOn(final Host reachable)
	{
		PeerBook book = new PeerBook();

		book.heard(List.of(new PeerAddress(reachable.host(), 18480)));
		book.confirmed(new PeerAddress(reachable.host(), 18481));

		assertEquals(List.of(new PeerAddress(reachable.host(), 18480)), book.heardOf());
		assertEquals(List.of(new PeerAddress(reachable.host(), 18481)), book.shareable());
	}
}
