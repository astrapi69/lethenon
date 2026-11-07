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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.SignatureSuite;
import io.github.astrapi69.lethenon.Wallet;

/**
 * What a node does with a learnt address whose dial fails (ADR 0003, #278). The failures are
 * made, not hoped for: nodes the hub has already been connected to are closed before the newcomer
 * dials them, so their ports refuse the connection. The others connect to the hub one after the
 * other, which fixes the order the hub passes their addresses on in, and with it the order the
 * newcomer dials them in.
 */
class FailedDialTest
{

	private static final String NOT_CONNECTED = "learnt from a peer, was not connected";

	private final Wallet holder = Wallet.create();

	private final List<BlockBody> genesis = testGenesis(holder.spendKey(SignatureSuite.ED25519));

	/**
	 * Starts listening nodes and connects them to the hub one after the other, each once the hub
	 * holds the one before it
	 */
	private List<Node> connectedInTurn(final Node hub, final int hubPort, final int count)
		throws java.io.IOException
	{
		List<Node> others = new ArrayList<>();
		for (int index = 0; index < count; index++)
		{
			Node other = Node.on(genesis).discoverPeers(false);
			others.add(other);
			int listening = other.listen(0);
			other.connect("127.0.0.1", hubPort);
			int expected = index + 1;
			await(() -> "node " + expected + " at the hub, listening on " + listening,
				() -> hub.peers().size() == expected);
		}
		return others;
	}

	private static long notConnected(final Node node)
	{
		return node.refusals().stream().filter(refusal -> refusal.contains(NOT_CONNECTED)).count();
	}

	@Test
	@DisplayName("a learnt address whose dial is refused is forgotten, and the refusal says so (ADR 0003)")
	void aLearntAddressWhoseDialIsRefused_isForgotten() throws java.io.IOException
	{
		List<Node> others = new ArrayList<>();
		try (Node hub = Node.on(genesis).discoverPeers(false).keepingLocalAddresses();
			Node newcomer = Node.on(genesis).keepingLocalAddresses())
		{
			int hubPort = hub.listen(0);
			others.addAll(connectedInTurn(hub, hubPort, 2));
			others.get(0).close();
			await("the closed node gone from the hub", () -> hub.peers().size() == 1);

			newcomer.connect("127.0.0.1", hubPort);

			await(() -> "the refused dial recorded; refusals " + newcomer.refusals(),
				() -> notConnected(newcomer) == 1);
			await(() -> "the open node connected; peers " + newcomer.peers().size(),
				() -> newcomer.peers().size() == 2);
			assertEquals(List.of(), newcomer.heardOf(),
				() -> "the refused address is still heard of; refusals " + newcomer.refusals());
		}
		finally
		{
			others.forEach(Node::close);
		}
	}

	@Test
	@DisplayName("with three of fourteen learnt addresses refused, the newcomer holds twelve and has heard of none (#278)")
	void threeRefusedOfFourteen_leaveTwelveAndNothingHeardOf() throws java.io.IOException
	{
		List<Node> others = new ArrayList<>();
		try (Node hub = Node.on(genesis).discoverPeers(false).keepingLocalAddresses();
			Node newcomer = Node.on(genesis).keepingLocalAddresses())
		{
			int hubPort = hub.listen(0);
			others.addAll(connectedInTurn(hub, hubPort, Node.MAXIMUM_OUTGOING + 2));
			for (int index = 0; index < 3; index++)
			{
				others.get(index).close();
			}
			await("the three closed nodes gone from the hub",
				() -> hub.peers().size() == Node.MAXIMUM_OUTGOING - 1);

			newcomer.connect("127.0.0.1", hubPort);

			await(() -> "three refused dials recorded; refusals " + newcomer.refusals(),
				() -> notConnected(newcomer) == 3);
			await(() -> "the newcomer full; peers " + newcomer.peers().size() + ", heard of "
				+ newcomer.heardOf(), () -> newcomer.peers().size() == Node.MAXIMUM_OUTGOING);
			assertTrue(newcomer.heardOf().isEmpty(),
				() -> "heard of " + newcomer.heardOf() + ", refusals " + newcomer.refusals());
		}
		finally
		{
			others.forEach(Node::close);
		}
	}
}
