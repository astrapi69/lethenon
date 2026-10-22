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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Moves a chain file of version 0.5.0 or earlier into a database (#202, ADR 0006).
 * <p>
 * The chain and its waiting transfers are written into {@code <chain>.migrating} in one
 * transaction, read back through the same verification as every read, and compared block by block
 * and transfer by transfer with what the old files hold. Only then do the old files step aside, as
 * {@code <chain>.legacy} and {@code <chain>.pending.legacy}, and the database takes the chain's
 * name. A crash at any point leaves one of two states, both of which the next start completes: the
 * old chain file still in place, which migrates again from the start, or a compared database next
 * to a {@code .legacy} file, which is compared once more and put in place.
 * <p>
 * A chain file whose blocks do not fit together is refused before anything is written, as a chain
 * that does not verify, naming the file (#216): the database would refuse it after the copy, and
 * then the message would name the copy, and the copy would be left behind.
 */
final class ChainMigration
{

	private final Path chain;

	ChainMigration(final Path chain)
	{
		this.chain = chain;
	}

	/**
	 * Whether a migration was interrupted between its two renames
	 *
	 * @return true when the chain's name is free, the database and the old chain beside it
	 */
	boolean wasInterruptedBeforeTheDatabaseTookItsPlace()
	{
		return !Files.exists(chain) && Files.exists(migrating()) && Files.exists(legacy());
	}

	/**
	 * Migrates the chain file at the chain's path
	 *
	 * @throws IOException
	 *             when it cannot be read, written or renamed, or the database differs from it;
	 *             the old files are then where they were
	 * @throws IllegalArgumentException
	 *             when the old files cannot be decoded
	 * @throws ChainRejected
	 *             when the blocks of the chain file do not fit together; nothing is written then
	 */
	void run() throws IOException
	{
		List<BlockBody> blocks = CanonicalEncoding.readChain(Files.readAllBytes(chain));
		requireTheBlocksFitTogether(blocks);
		List<SignedTransaction> waiting = legacyWaiting();
		ChainDatabase.delete(migrating());
		new ChainDatabase(migrating()).change(blocks, waiting);
		requireTheSame(blocks, waiting);
		if (Files.exists(pending()))
		{
			move(pending(), pendingLegacy());
		}
		move(chain, legacy());
		takeThePlace();
	}

	/**
	 * Completes a migration that was interrupted after the old chain stepped aside
	 *
	 * @throws IOException
	 *             when the database differs from the old chain; nothing is renamed then
	 */
	void finish() throws IOException
	{
		List<BlockBody> blocks = CanonicalEncoding.readChain(Files.readAllBytes(legacy()));
		requireTheSame(blocks, legacyWaiting());
		takeThePlace();
	}

	/**
	 * Checks what the database checks on every read - each block at its height, naming the block
	 * below it - on the old chain file, before the copy is made. Whether the chain follows the
	 * consensus rules is the replay's question, asked after the migration as on every read
	 */
	private void requireTheBlocksFitTogether(final List<BlockBody> blocks)
	{
		Bytes below = null;
		for (int position = 0; position < blocks.size(); position++)
		{
			BlockBody block = blocks.get(position);
			if (block.height() != position)
			{
				throw notMigrated("block " + position + " claims height " + block.height());
			}
			if (below != null && !below.equals(block.previousHash()))
			{
				throw notMigrated("block " + position + " names " + block.previousHash()
					+ " as the previous hash, and the block before it hashes to " + below);
			}
			below = Blocks.hashOf(block);
		}
	}

	private ChainRejected notMigrated(final String reason)
	{
		return new ChainRejected(chain.toAbsolutePath() + ": " + reason
			+ "; the chain file is left as it was, and it was not migrated");
	}

	private void takeThePlace() throws IOException
	{
		move(migrating(), chain);
		syncDirectory(chain.toAbsolutePath().getParent());
	}

	private void requireTheSame(final List<BlockBody> blocks, final List<SignedTransaction> waiting)
		throws IOException
	{
		ChainDatabase database = new ChainDatabase(migrating());
		List<BlockBody> migrated = database.blocks();
		if (migrated.size() != blocks.size())
		{
			throw differs("it holds " + migrated.size() + " blocks and the chain file "
				+ blocks.size());
		}
		for (int height = 0; height < blocks.size(); height++)
		{
			if (!java.util.Arrays.equals(CanonicalEncoding.encodeChain(List.of(blocks.get(height))),
				CanonicalEncoding.encodeChain(List.of(migrated.get(height)))))
			{
				throw differs("block " + height + " is not the same");
			}
		}
		List<SignedTransaction> migratedWaiting = database.waiting();
		if (migratedWaiting.size() != waiting.size())
		{
			throw differs("it holds " + migratedWaiting.size()
				+ " waiting transfers and the pending file " + waiting.size());
		}
		for (int position = 0; position < waiting.size(); position++)
		{
			if (!java.util.Arrays.equals(CanonicalEncoding.encode(waiting.get(position)),
				CanonicalEncoding.encode(migratedWaiting.get(position))))
			{
				throw differs("waiting transfer " + position + " is not the same");
			}
		}
	}

	private IOException differs(final String what)
	{
		return new IOException("the migrated database " + migrating() + " differs from the chain "
			+ "file it was made from: " + what + "; nothing was replaced, the old files are where "
			+ "they were");
	}

	private List<SignedTransaction> legacyWaiting() throws IOException
	{
		Path file = Files.exists(pending()) ? pending() : pendingLegacy();
		if (!Files.exists(file))
		{
			return List.of();
		}
		return LegacyPendingFile.read(file);
	}

	private static void move(final Path from, final Path to) throws IOException
	{
		Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Makes the renames in a directory durable. Windows cannot open a directory to sync it, and
	 * NTFS journals its renames itself; everywhere else a failure is an error
	 */
	private static void syncDirectory(final Path directory) throws IOException
	{
		if (System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"))
		{
			return;
		}
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ))
		{
			channel.force(true);
		}
	}

	private Path migrating()
	{
		return sibling(".migrating");
	}

	private Path legacy()
	{
		return sibling(".legacy");
	}

	private Path pending()
	{
		return sibling(".pending");
	}

	private Path pendingLegacy()
	{
		return sibling(".pending.legacy");
	}

	private Path sibling(final String suffix)
	{
		return chain.resolveSibling(chain.getFileName() + suffix);
	}
}
