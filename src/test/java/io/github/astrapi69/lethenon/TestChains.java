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

import java.util.ArrayList;
import java.util.List;

/**
 * A short mined chain around given transfers, one transfer per block after the genesis block, for
 * tests that need a chain to replay rather than a chain to examine
 */
final class TestChains
{

	/** The account every block after the genesis block pays its reward to */
	static final Bytes MINER = Bytes.of(new byte[] { 5 });

	private TestChains()
	{
	}

	/**
	 * Replays a chain the way a test of a rule means it, against this node's clock: a main chain
	 * verifies only from its anchor (#161), so a main chain a test builds is replayed with its own
	 * genesis block filed as the anchor, the way the start day files the real one (ADR 0005); a test
	 * chain, which never gets an anchor, under {@link ConsensusRules#LETHENON} as it is
	 *
	 * @param chain
	 *            the blocks, genesis first
	 * @return what was verified
	 */
	static Replay replay(final List<BlockBody> chain)
	{
		return replay(chain, ConsensusRules.LETHENON);
	}

	/**
	 * Replays a chain under the given rules, with a main chain's own genesis block as its anchor
	 */
	static Replay replay(final List<BlockBody> chain, final ConsensusRules rules)
	{
		return Replay.verify(chain, anchoredTo(chain, rules));
	}

	/**
	 * Replays a chain under the given rules and clock, with a main chain's own genesis block as its
	 * anchor
	 */
	static Replay replay(final List<BlockBody> chain, final ConsensusRules rules, final long now)
	{
		return Replay.verify(chain, anchoredTo(chain, rules), now);
	}

	/**
	 * The given rules, and for a main chain that has no anchor in them its own genesis block filed
	 * as one. Everything else - activations, limits, the emission, a test chain's anchor - stays as
	 * it is.
	 *
	 * @param chain
	 *            the chain whose genesis block is filed when it is a main chain
	 * @param rules
	 *            the rules the test means
	 * @return the rules to replay the chain under
	 */
	static ConsensusRules anchoredTo(final List<BlockBody> chain, final ConsensusRules rules)
	{
		BlockBody genesis = chain.getFirst();
		if (!Chain.IDENTIFIER.equals(genesis.chainIdentifier())
			|| rules.anchorFor(Chain.IDENTIFIER).isPresent())
		{
			return rules;
		}
		List<BlockLimits> limits = new ArrayList<>();
		rules.limitsFor(Chain.IDENTIFIER).ifPresent(limits::add);
		rules.limitsFor(Chain.TEST_IDENTIFIER).ifPresent(limits::add);
		List<GenesisAnchor> anchors = new ArrayList<>(List.of(Genesis.anchorOf(genesis)));
		rules.anchorFor(Chain.TEST_IDENTIFIER).map(Genesis::anchorOf).ifPresent(anchors::add);
		return new ConsensusRules(rules.activations(), limits, anchors, rules.emission());
	}

	/**
	 * What the block at a height pays under {@link Emission#SCHEDULE} on a chain whose blocks carry
	 * no fees
	 *
	 * @param height
	 *            the block's height, 0 for the genesis block
	 * @return its reward
	 */
	static Amount rewardOfBlock(final int height)
	{
		Amount pool = Emission.MINING_POOL;
		for (int before = 0; before < height; before++)
		{
			pool = pool.minus(Emission.rewardFor(pool).fromPool());
		}
		return Emission.rewardFor(pool).total();
	}

	/**
	 * The first two blocks of a test chain whose holder can spend: the genesis block, which pays
	 * the burn account (#148), and block 1, which pays the holder - the first reward anybody holds
	 *
	 * @param chainIdentifier
	 *            the chain
	 * @param holder
	 *            the account block 1 pays
	 * @param time
	 *            the genesis block's timestamp; block 1 follows a minute later
	 * @return the two blocks, genesis first
	 */
	static List<BlockBody> funding(final String chainIdentifier, final Bytes holder,
		final long time)
	{
		BlockBody genesis = Blocks
			.mine(new BlockBody(chainIdentifier, 0L, Bytes.of(new byte[32]), Genesis.NOBODY,
				new ArrayList<>(), time, DifficultyRule.MINIMUM, "in the beginning was the pun"),
				1_000_000L)
			.orElseThrow();
		BlockBody first = Blocks
			.mine(new BlockBody(chainIdentifier, 1L, Blocks.hashOf(genesis), holder,
				new ArrayList<>(), time + 60_000L, DifficultyRule.MINIMUM, "the first reward"),
				1_000_000L)
			.orElseThrow();
		return List.of(genesis, first);
	}

	/**
	 * A chain made longer by empty blocks at the lowest difficulty, each paying {@link #MINER}: a
	 * chain to store rather than to replay, cheap to mine by the hundred, and a fork when it extends
	 * a prefix of another chain with other words
	 *
	 * @param chain
	 *            the chain to extend, genesis first, not empty
	 * @param blocks
	 *            how many blocks to add
	 * @param puns
	 *            the words of the added blocks, followed by each block's height
	 * @return the longer chain
	 */
	static List<BlockBody> extended(final List<BlockBody> chain, final int blocks,
		final String puns)
	{
		List<BlockBody> longer = new ArrayList<>(chain);
		for (int added = 0; added < blocks; added++)
		{
			BlockBody previous = longer.getLast();
			longer.add(Blocks
				.mine(new BlockBody(previous.chainIdentifier(), previous.height() + 1L,
					Blocks.hashOf(previous), MINER, List.of(), previous.timestamp() + 60_000L,
					DifficultyRule.MINIMUM, puns + " " + longer.size()), 1_000_000L)
				.orElseThrow());
		}
		return longer;
	}

	/**
	 * A test chain whose block 1 pays the holder, and one mined block per transfer after it, each
	 * paying {@link #MINER}
	 *
	 * @param holder
	 *            the account block 1 pays its reward to
	 * @param transfers
	 *            the transfers, in chain order
	 * @return the blocks, genesis first
	 */
	static List<BlockBody> chainWith(final Bytes holder, final SignedTransaction... transfers)
	{
		List<BlockBody> chain = new ArrayList<>(
			funding(Chain.TEST_IDENTIFIER, holder, 1_759_000_000_000L));
		for (SignedTransaction transfer : transfers)
		{
			BlockBody previous = chain.getLast();
			chain.add(Blocks
				.mine(new BlockBody(Chain.TEST_IDENTIFIER, previous.height() + 1L,
					Blocks.hashOf(previous), MINER, List.of(transfer),
					1_759_000_000_000L + 60_000L * chain.size(), 8, "block " + chain.size()),
					1_000_000L)
				.orElseThrow());
		}
		return chain;
	}
}
