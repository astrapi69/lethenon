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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.ChainFile;

/**
 * A running node says how it stands at an interval, not only when it stops: a seed node runs for
 * months, and its operator reads the journal (#149). The line counts refusals and names no
 * address, because a remote address is personal data and a line repeated every minute would leave
 * the journal's retention to decide how long it is kept.
 * <p>
 * The timeout is part of the test: a wait that comes out as 0 milliseconds is a wait without a
 * limit, and a node whose --for ends that way never stops. That is what happened when the rounding
 * in NodeCommand.millisUntil was taken out on purpose, so a regression here fails instead of
 * hanging the build.
 */
@Timeout(60)
class NodeStatusLineTest extends AbstractCliTest
{

	private static final String STATUS = "status: ";

	@TempDir
	Path directory;

	private Path chain;

	@BeforeEach
	void aTestChainOfTwoBlocks()
	{
		chain = directory.resolve("chain.lethenon");
		String password = aPassword();
		String wallet = directory.resolve("miner.wallet").toString();
		assertEquals(0, run(password, "wallet", "create", "--wallet", wallet), err);
		assertEquals(0, run(password, "mine", "--testnet", "--chain", chain.toString(), "--wallet",
			wallet), err);
		assertEquals(0, run(password, "mine", "--chain", chain.toString(), "--wallet", wallet),
			err);
	}

	@Test
	@DisplayName("a running node prints its height, tip, peers and pool at the interval")
	void aRunningNode_printsAStatusLineAtTheInterval() throws IOException
	{
		List<BlockBody> blocks = new ChainFile(chain).require();
		String tip = Blocks.hashOf(blocks.get(blocks.size() - 1)).toString();

		assertEquals(0, run("", "node", "--chain", chain.toString(), "--listen", "0",
			"--no-discovery", "--for", "3", "--status-every", "1"), err);

		List<String> lines = statusLines();
		assertTrue(lines.size() >= 2, "one line a second for three seconds: " + out);
		String first = lines.get(0);
		assertTrue(first.contains("height 1"), first);
		assertTrue(first.contains("tip " + tip), first);
		assertTrue(first.contains("0 peer(s) connected"), first);
		assertTrue(first.contains("0 transfer(s) waiting"), first);
	}

	@Test
	@DisplayName("the status line counts refusals and names no address")
	void theStatusLine_countsRefusalsWithoutTheirAddresses() throws IOException
	{
		String unreachable = "127.0.0.1:" + aPortNobodyListensOn();

		assertEquals(0, run("", "node", "--chain", chain.toString(), "--listen", "0",
			"--no-discovery", "--peer", unreachable, "--for", "3", "--status-every", "1"), err);

		List<String> lines = statusLines();
		assertFalse(lines.isEmpty(), out);
		assertTrue(lines.get(0).contains("1 refusal(s) since the last line"), lines.get(0));
		lines.forEach(line -> assertFalse(line.contains(unreachable),
			"a status line repeated for months is no place for an address: " + line));
		assertTrue(err.contains(unreachable),
			"the stop report still names it, once, as before: " + err);
	}

	@Test
	@DisplayName("--status-every 0 prints no status line")
	void statusEveryZero_printsNone()
	{
		assertEquals(0, run("", "node", "--chain", chain.toString(), "--listen", "0",
			"--no-discovery", "--for", "2", "--status-every", "0"), err);

		assertTrue(statusLines().isEmpty(), out);
	}

	/**
	 * The wait between two looks at the clock. 0 means "no limit" to the stop's latch, so a wait that
	 * is merely short must never come out as 0, and a --for that is over must end the loop
	 */
	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
		neither --for nor a status line: wait without a limit | none       | none       | 0
		--for is over                                         | -1         | none       | -1
		--for ends exactly now                                | 0          | none       | -1
		half a millisecond of --for left rounds up, not to 0  | 500000     | none       | 1
		the status line comes first                           | 5000000000 | 1000000000 | 1000
		the end of --for comes first                          | 2000000000 | 9000000000 | 2000
		no --for, a line due in a second                      | none       | 1000000000 | 1000
		a line due half a millisecond from now rounds up      | none       | 500000     | 1
		""")
	void millisUntil_isTheEarlierOfTheTwo_andNeverZeroWhileOneIsDue(String caseName,
		String deadlineFromNow, String lineFromNow, long expected)
	{
		long now = 1_000_000_000_000L;

		assertEquals(expected, NodeCommand.millisUntil(now, at(now, deadlineFromNow),
			at(now, lineFromNow)), caseName);
	}

	/** "none" is the 0 that NodeCommand reads as "not set"; anything else is nanoseconds from now */
	private static long at(long now, String fromNow)
	{
		return "none".equals(fromNow) ? 0L : now + Long.parseLong(fromNow);
	}

	private List<String> statusLines()
	{
		return out.lines().filter(line -> line.startsWith(STATUS)).toList();
	}

	private static int aPortNobodyListensOn() throws IOException
	{
		try (ServerSocket socket = new ServerSocket(0))
		{
			return socket.getLocalPort();
		}
	}
}
