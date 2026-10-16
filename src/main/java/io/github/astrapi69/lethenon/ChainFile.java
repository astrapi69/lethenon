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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A chain on disk, and the transfers waiting for its next block (lethenon#32, #202).
 * <p>
 * Since 0.6.0 the file at the chain's path is an SQLite database (ADR 0006). Every change is one
 * transaction: a new block, a rollback over a fork of any depth, and the waiting transfers written
 * with them are on the disk whole or not at all, after a killed process as after a power cut.
 * Every read verifies the chain against its hashes before it returns it, and a database that does
 * not verify is refused, not used.
 * <p>
 * A chain file of an earlier version is migrated the first time it is read or written: copied into
 * the database, compared block by block, and kept beside it as {@code <chain>.legacy} (see
 * {@link ChainMigration}).
 * <p>
 * The command line and the desktop plugin both read and write chains through this class, so there
 * is one format and one way to write it.
 */
public final class ChainFile
{

	/**
	 * The version of the pending file of 0.5.0 and earlier, {@code <chain>.pending}: a version
	 * byte, the number of transfers, then each transfer as its length and its canonical encoding.
	 * Read when such a chain is migrated, never written
	 */
	public static final byte PENDING_VERSION = 1;

	private final Path chain;

	/**
	 * A chain at the given path, which need not exist yet
	 *
	 * @param chain
	 *            the chain's file
	 */
	public ChainFile(final Path chain)
	{
		this.chain = Objects.requireNonNull(chain);
	}

	/**
	 * The chain, verified; empty when there is none yet
	 *
	 * @return the blocks, genesis first
	 * @throws IOException
	 *             if it cannot be read, or does not verify
	 */
	public List<BlockBody> read() throws IOException
	{
		if (!databaseExists())
		{
			return List.of();
		}
		return database().blocks();
	}

	/**
	 * The chain, which has to exist
	 *
	 * @return the blocks, genesis first
	 * @throws IllegalArgumentException
	 *             when there is no chain yet
	 * @throws IOException
	 *             if it cannot be read, or does not verify
	 */
	public List<BlockBody> require() throws IOException
	{
		List<BlockBody> blocks = read();
		if (blocks.isEmpty())
		{
			throw new IllegalArgumentException(
				"there is no chain at " + chain + " yet; mine its genesis block first");
		}
		return blocks;
	}

	/**
	 * Writes the chain in one transaction, leaving the waiting transfers as they are
	 *
	 * @param blocks
	 *            the whole chain, genesis first
	 * @throws IOException
	 *             if it cannot be written; then nothing of it is
	 */
	public void write(final List<BlockBody> blocks) throws IOException
	{
		change(Objects.requireNonNull(blocks), null);
	}

	/**
	 * Writes the chain and the transfers waiting for its next block, in one transaction
	 *
	 * @param blocks
	 *            the whole chain, genesis first
	 * @param transfers
	 *            all the transfers to wait, in order
	 * @throws IOException
	 *             if they cannot be written; then nothing of them is
	 */
	public void write(final List<BlockBody> blocks, final List<SignedTransaction> transfers)
		throws IOException
	{
		change(Objects.requireNonNull(blocks), Objects.requireNonNull(transfers));
	}

	/**
	 * The transfers waiting for the next block
	 *
	 * @return the transfers, in the order they were signed; none when there is no chain yet
	 * @throws IOException
	 *             if they cannot be read
	 */
	public List<SignedTransaction> readPending() throws IOException
	{
		if (!databaseExists())
		{
			return List.of();
		}
		return database().waiting();
	}

	/**
	 * Replaces the transfers waiting for the next block, in one transaction
	 *
	 * @param transfers
	 *            all of them; none leaves none waiting
	 * @throws IOException
	 *             if they cannot be written
	 */
	public void writePending(final List<SignedTransaction> transfers) throws IOException
	{
		change(null, Objects.requireNonNull(transfers));
	}

	/**
	 * The settings the database runs with (ADR 0006)
	 *
	 * @return journal_mode, synchronous and fullfsync as SQLite reports them
	 * @throws IOException
	 *             if the database cannot be opened
	 */
	Map<String, String> settingsInForce() throws IOException
	{
		return database().settingsInForce();
	}

	private void change(final List<BlockBody> blocks, final List<SignedTransaction> transfers)
		throws IOException
	{
		databaseExists();
		database().change(blocks, transfers);
	}

	/**
	 * Whether there is a database at the chain's path, after completing whatever migration a
	 * chain file of an earlier version or an interrupted one asks for
	 */
	private boolean databaseExists() throws IOException
	{
		ChainMigration migration = new ChainMigration(chain);
		if (Files.exists(chain))
		{
			if (!ChainDatabase.isDatabase(chain))
			{
				migration.run();
			}
			return true;
		}
		if (migration.wasInterruptedBeforeTheDatabaseTookItsPlace())
		{
			migration.finish();
			return true;
		}
		return false;
	}

	private ChainDatabase database()
	{
		return new ChainDatabase(chain);
	}
}
