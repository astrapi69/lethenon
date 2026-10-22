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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.DifficultyRule;
import io.github.astrapi69.lethenon.Mining;
import io.github.astrapi69.lethenon.SignedTransaction;

/**
 * Chains and waiting for the test networks: nodes run on their own threads, so a test waits for a
 * condition with a deadline instead of sleeping
 */
final class Networks
{

	static final long GENESIS_TIME = 1_759_500_000_000L;

	private Networks()
	{
	}

	/**
	 * A test chain of its genesis block, which pays the burn account (#148), and block 1, which
	 * pays the given holder, so that the holder can spend. The genesis pun names the holder: a
	 * genesis block pays nobody it could tell apart by, so two holders still mean two chains
	 */
	static List<BlockBody> testGenesis(final Bytes holder)
	{
		return genesis(Chain.TEST_IDENTIFIER, holder);
	}

	static List<BlockBody> genesis(final String chainIdentifier, final Bytes holder)
	{
		List<BlockBody> genesis = List.of(Blocks.mine(Mining.nextBlock(chainIdentifier, List.of(),
			holder, List.of(), "in the beginning of " + holder, GENESIS_TIME), 1_000_000L).orElseThrow());
		return extended(genesis, holder, List.of());
	}

	/**
	 * The chain extended by one mined block carrying the given transfers, at the target block
	 * time after the one before, so that a long chain keeps the minimum difficulty
	 */
	static List<BlockBody> extended(final List<BlockBody> chain, final Bytes miner,
		final List<SignedTransaction> transfers)
	{
		List<BlockBody> extended = new ArrayList<>(chain);
		extended.add(Blocks.mine(Mining.nextBlock(chain, miner, transfers, "block " + chain.size(),
			GENESIS_TIME + DifficultyRule.TARGET_BLOCK_MILLIS * chain.size()), 1_000_000L)
			.orElseThrow());
		return List.copyOf(extended);
	}

	/**
	 * Waits until the condition holds, at most ten seconds
	 *
	 * @throws AssertionError
	 *             naming what was waited for, when the deadline passes
	 */
	static void await(final String what, final BooleanSupplier condition)
	{
		await(what, condition, Duration.ofSeconds(10));
	}

	/**
	 * Waits until the condition holds, at most the given time.
	 * <p>
	 * When the time runs out, the message says how far the wall clock moved meanwhile and where
	 * every thread stands (#212). The deadline runs on the monotonic clock, which stands still while
	 * the machine sleeps, and a test report's duration on the wall clock, which does not: a wall
	 * clock far ahead of the limit is a machine that slept or a JVM that stood still, and the
	 * threads tell a hang from both.
	 *
	 * @throws AssertionError
	 *             naming what was waited for, when the deadline passes
	 */
	static void await(final String what, final BooleanSupplier condition, final Duration limit)
	{
		long wallStart = System.currentTimeMillis();
		long deadline = System.nanoTime() + limit.toNanos();
		while (!condition.getAsBoolean())
		{
			if (System.nanoTime() > deadline)
			{
				throw new AssertionError("waited " + readable(limit) + " for " + what
					+ "; the wall clock moved " + (System.currentTimeMillis() - wallStart)
					+ " ms meanwhile\n" + threads());
			}
			Thread.onSpinWait();
			try
			{
				Thread.sleep(10);
			}
			catch (InterruptedException interrupted)
			{
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted while waiting for " + what);
			}
		}
	}

	private static String readable(final Duration limit)
	{
		return limit.toMillis() % 1000 == 0 ? limit.toSeconds() + " s" : limit.toMillis() + " ms";
	}

	/**
	 * Every thread of this JVM with its state and the top of its stack
	 */
	private static String threads()
	{
		StringBuilder report = new StringBuilder("threads:");
		for (java.util.Map.Entry<Thread, StackTraceElement[]> thread : Thread.getAllStackTraces()
			.entrySet())
		{
			report.append("\n\"").append(thread.getKey().getName()).append("\" ")
				.append(thread.getKey().getState());
			StackTraceElement[] stack = thread.getValue();
			for (int frame = 0; frame < Math.min(stack.length, 12); frame++)
			{
				report.append("\n    at ").append(stack[frame]);
			}
		}
		return report.toString();
	}
}
