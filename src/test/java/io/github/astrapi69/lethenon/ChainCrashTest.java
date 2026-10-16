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
package io.github.astrapi69.lethenon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The acceptance of #202: a process killed with {@code kill -9} at a random moment, while it writes
 * one block after another or rolls a chain back over several blocks, leaves a database that
 * verifies and holds the change whole or not at all - the blocks and the transfers waiting beside
 * them together.
 * <p>
 * A kill takes the process, not the operating system: what was written reached the system's
 * buffers and survives, so this test cannot see whether anything is synced to the disk. That is
 * {@link PowerCutTest}'s job.
 */
class ChainCrashTest
{

	/** Blocks of the growing chain; more than a writer gets through before it is killed */
	static final int GROWN_BLOCKS = 600;

	static List<BlockBody> grown;

	static List<BlockBody> first;

	static List<BlockBody> second;

	@TempDir
	Path directory;

	@BeforeAll
	static void mineTheChains()
	{
		grown = TestChains.extended(TestChains.chainWith(TestChains.MINER), GROWN_BLOCKS - 2,
			"grown");
		List<BlockBody> trunk = TestChains.extended(TestChains.chainWith(TestChains.MINER), 8,
			"trunk");
		first = TestChains.extended(trunk, 6, "first");
		second = TestChains.extended(trunk, 7, "second");
	}

	@Test
	@DisplayName("kill -9 while blocks are written: every block is in whole or not at all")
	void aKillWhileABlockIsWrittenLeavesTheBlockWholeOrNotAtAll() throws Exception
	{
		Path chain = CrashRuns.chainFixture(grown, directory.resolve("grown.chain"));
		Random random = new Random();
		Outcome outcome = new Outcome("kill -9 while growing");
		for (int run = 0; run < CrashRuns.runs(); run++)
		{
			Path database = prepared(directory.resolve("run-" + run), grown.subList(0, 1),
				ChainWriterProcess.waitingFor(1, ChainWriterProcess.transfers(1)));
			CrashRuns.Killed killed = CrashRuns.killAtRandom(
				List.of("grow", database.toString(), chain.toString()), Map.of(), random,
				database.resolveSibling("errors.txt"));
			outcome.count(killed, checkGrown(database, killed));
		}
		outcome.assertAllWhole();
	}

	@Test
	@DisplayName("kill -9 while a fork is rolled back: the chain is one side or the other, never between")
	void aKillWhileAForkIsRolledBackLeavesOneChainOrTheOther() throws Exception
	{
		Path firstChain = CrashRuns.chainFixture(first, directory.resolve("first.chain"));
		Path secondChain = CrashRuns.chainFixture(second, directory.resolve("second.chain"));
		Random random = new Random();
		Outcome outcome = new Outcome("kill -9 while rolling back");
		for (int run = 0; run < CrashRuns.runs(); run++)
		{
			Path database = prepared(directory.resolve("run-" + run), first, List.of());
			CrashRuns.Killed killed = CrashRuns.killAtRandom(
				List.of("fork", database.toString(), firstChain.toString(), secondChain.toString()),
				Map.of(), random, database.resolveSibling("errors.txt"));
			outcome.count(killed, checkForked(database, killed));
		}
		outcome.assertAllWhole();
	}

	/**
	 * A database holding a chain and its waiting transfers, in a directory of its own
	 *
	 * @param runDirectory
	 *            the directory, created
	 * @param chain
	 *            the chain
	 * @param waiting
	 *            the transfers
	 * @return the database
	 * @throws IOException
	 *             if it cannot be written
	 */
	static Path prepared(final Path runDirectory, final List<BlockBody> chain,
		final List<SignedTransaction> waiting) throws IOException
	{
		Files.createDirectories(runDirectory);
		Path database = runDirectory.resolve("chain.lethenon");
		new ChainFile(database).write(chain, waiting);
		return database;
	}

	/**
	 * What is wrong with a grown database after a kill
	 *
	 * @param database
	 *            the database
	 * @param killed
	 *            what the writer said
	 * @return nothing when it holds a state the writer had begun or finished, whole
	 */
	static String checkGrown(final Path database, final CrashRuns.Killed killed)
	{
		int done = Math.max(1, killed.lastDone());
		Set<Integer> allowed = new LinkedHashSet<>(List.of(done));
		if (killed.insideAWrite())
		{
			allowed.add(killed.lastBegun());
		}
		try
		{
			ChainFile file = new ChainFile(database);
			List<BlockBody> found = file.read();
			int waiting = file.readPending().size();
			if (!allowed.contains(found.size()))
			{
				return "holds " + found.size() + " blocks where " + allowed + " are allowed";
			}
			if (!found.equals(grown.subList(0, found.size())))
			{
				return "holds " + found.size() + " blocks that are not the chain's";
			}
			if (waiting != found.size() % ChainWriterProcess.WAITING_CYCLE)
			{
				return "holds " + found.size() + " blocks and " + waiting
					+ " waiting transfers, which were never written together";
			}
			return null;
		}
		catch (IOException | RuntimeException unreadable)
		{
			return "cannot be read: " + unreadable;
		}
	}

	/**
	 * What is wrong with a database the fork writer was killed over
	 *
	 * @param database
	 *            the database
	 * @param killed
	 *            what the writer said
	 * @return nothing when it holds one of the two chains a step began or finished, whole
	 */
	static String checkForked(final Path database, final CrashRuns.Killed killed)
	{
		Set<Integer> allowed = new LinkedHashSet<>(List.of(killed.lastDone()));
		if (killed.insideAWrite())
		{
			allowed.add(killed.lastBegun());
		}
		try
		{
			ChainFile file = new ChainFile(database);
			List<BlockBody> found = file.read();
			int waiting = file.readPending().size();
			for (int step : allowed)
			{
				boolean isSecond = step % 2 == 1;
				if (found.equals(isSecond ? second : first) && waiting == (isSecond ? 3 : 0))
				{
					return null;
				}
			}
			return "holds " + found.size() + " blocks with " + waiting
				+ " waiting, which is neither chain of steps " + allowed;
		}
		catch (IOException | RuntimeException unreadable)
		{
			return "cannot be read: " + unreadable;
		}
	}

	/** What a series of runs found, and the assertions over it */
	static final class Outcome
	{

		private final String name;

		private final String unit;

		private final List<String> broken = new ArrayList<>();

		private int runs;

		private int insideAWrite;

		private int exitedByItself;

		Outcome(final String name)
		{
			this(name, "runs");
		}

		Outcome(final String name, final String unit)
		{
			this.name = name;
			this.unit = unit;
		}

		void count(final CrashRuns.Killed killed, final String problem)
		{
			runs++;
			insideAWrite += killed.insideAWrite() ? 1 : 0;
			exitedByItself += killed.exitedByItself() ? 1 : 0;
			if (problem != null)
			{
				broken.add(unit.substring(0, unit.length() - 1) + " " + runs + " (begun " + killed.lastBegun() + ", done "
					+ killed.lastDone() + "): " + problem);
			}
		}

		void assertAllWhole()
		{
			String summary = name + ": " + runs + " " + unit + ", " + insideAWrite
				+ " killed inside a write, " + exitedByItself + " ended before the kill, "
				+ broken.size() + " broken";
			System.out.println(summary);
			assertEquals(List.of(), broken, summary);
			assertTrue(insideAWrite * 2 >= runs, "a test that rarely kills inside a write proves "
				+ "little: " + summary);
		}
	}
}
