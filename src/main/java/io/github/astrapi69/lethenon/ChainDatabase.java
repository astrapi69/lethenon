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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One SQLite database holding a chain and the transfers waiting for its next block (#202, ADR
 * 0006).
 * <p>
 * Every change is one transaction, taken with the write lock up front, so that a second program
 * waits for it instead of failing half way. Every read verifies what it reads before it returns
 * it: SQLite's own integrity check, heights from zero without a gap, every block under the hash
 * stored for it, and every block naming the hash of the one below. A database that fails is not
 * used and not repaired; the exception says what failed and where, and the file stays as it is.
 * <p>
 * The settings that make a commit survive a power cut are set on every connection, in
 * {@link #open(Path)}, and nowhere else.
 */
final class ChainDatabase
{

	/** "LETH": what marks an SQLite database as a lethenon chain */
	static final int APPLICATION_ID = 0x4C455448;

	/** The version of the tables below; a later version of lethenon may change them */
	static final int SCHEMA_VERSION = 1;

	private static final List<String> SCHEMA = List.of(
		"CREATE TABLE block (height INTEGER PRIMARY KEY, hash BLOB NOT NULL, body BLOB NOT NULL)",
		"CREATE TABLE waiting (position INTEGER PRIMARY KEY, body BLOB NOT NULL)",
		"PRAGMA application_id = " + APPLICATION_ID, "PRAGMA user_version = " + SCHEMA_VERSION);

	/** How long a program waits for another one's write before it gives up */
	private static final int BUSY_TIMEOUT_MILLIS = 30_000;

	private final Path path;

	ChainDatabase(final Path path)
	{
		this.path = path;
	}

	/**
	 * The chain, verified
	 *
	 * @return the blocks, genesis first; none in a database that was created and never written
	 * @throws IOException
	 *             when the database cannot be read or does not verify
	 */
	List<BlockBody> blocks() throws IOException
	{
		try (Connection connection = open(path))
		{
			if (requireIdentity(connection) == 0)
			{
				return List.of();
			}
			requireIntegrity(connection);
			return verifiedBlocks(connection);
		}
		catch (SQLException unreadable)
		{
			throw new IOException("the chain database at " + path + " cannot be read: "
				+ unreadable.getMessage(), unreadable);
		}
	}

	/**
	 * The transfers waiting for the next block
	 *
	 * @return them, in the order they wait
	 * @throws IOException
	 *             when the database cannot be read
	 */
	List<SignedTransaction> waiting() throws IOException
	{
		try (Connection connection = open(path))
		{
			if (requireIdentity(connection) == 0)
			{
				return List.of();
			}
			List<SignedTransaction> transfers = new ArrayList<>();
			try (Statement select = connection.createStatement();
				ResultSet rows = select.executeQuery("SELECT body FROM waiting ORDER BY position"))
			{
				while (rows.next())
				{
					transfers.add(CanonicalEncoding.readSignedTransaction(rows.getBytes(1)));
				}
			}
			return transfers;
		}
		catch (SQLException unreadable)
		{
			throw new IOException("the chain database at " + path + " cannot be read: "
				+ unreadable.getMessage(), unreadable);
		}
	}

	/**
	 * Changes the chain, the waiting transfers or both, in one transaction: the blocks above the
	 * highest one the database holds with the same hash are deleted, the new ones inserted
	 *
	 * @param blocks
	 *            the whole chain as it is to be, genesis first; null leaves the chain as it is
	 * @param transfers
	 *            all the transfers to wait; null leaves them as they are
	 * @throws IOException
	 *             when it cannot be written; then nothing of it is
	 */
	void change(final List<BlockBody> blocks, final List<SignedTransaction> transfers)
		throws IOException
	{
		try (Connection connection = open(path))
		{
			try (Statement transaction = connection.createStatement())
			{
				transaction.execute("BEGIN IMMEDIATE");
				try
				{
					if (requireIdentity(connection) == 0)
					{
						createTables(connection);
					}
					if (blocks != null)
					{
						replaceBlocks(connection, blocks);
					}
					if (transfers != null)
					{
						replaceWaiting(connection, transfers);
					}
					transaction.execute("COMMIT");
				}
				catch (SQLException | IOException | RuntimeException failed)
				{
					transaction.execute("ROLLBACK");
					throw failed;
				}
			}
		}
		catch (SQLException unwritable)
		{
			throw new IOException("the chain database at " + path + " cannot be written: "
				+ unwritable.getMessage(), unwritable);
		}
	}

	/**
	 * The settings a connection runs with, for the test that they are the ones ADR 0006 names
	 *
	 * @return journal_mode, synchronous and fullfsync as SQLite reports them
	 * @throws IOException
	 *             when the database cannot be opened
	 */
	Map<String, String> settingsInForce() throws IOException
	{
		try (Connection connection = open(path); Statement pragma = connection.createStatement())
		{
			Map<String, String> settings = new LinkedHashMap<>();
			for (String name : List.of("journal_mode", "synchronous", "fullfsync"))
			{
				try (ResultSet value = pragma.executeQuery("PRAGMA " + name))
				{
					settings.put(name, value.next() ? value.getString(1) : null);
				}
			}
			return settings;
		}
		catch (SQLException unreadable)
		{
			throw new IOException("the chain database at " + path + " cannot be read: "
				+ unreadable.getMessage(), unreadable);
		}
	}

	/**
	 * Whether a file is an SQLite database, by its first sixteen bytes
	 *
	 * @param file
	 *            the file, which exists
	 * @return true for an SQLite database
	 * @throws IOException
	 *             if it cannot be read
	 */
	static boolean isDatabase(final Path file) throws IOException
	{
		byte[] header = "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		try (var in = Files.newInputStream(file))
		{
			return java.util.Arrays.equals(header, in.readNBytes(header.length));
		}
	}

	/**
	 * Deletes a database and the files SQLite keeps beside it
	 *
	 * @param database
	 *            the database
	 * @throws IOException
	 *             if one cannot be deleted
	 */
	static void delete(final Path database) throws IOException
	{
		for (String suffix : List.of("", "-wal", "-shm", "-journal"))
		{
			Files.deleteIfExists(database.resolveSibling(database.getFileName() + suffix));
		}
	}

	private static Connection open(final Path database) throws SQLException
	{
		Connection connection = DriverManager
			.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
		try (Statement pragma = connection.createStatement())
		{
			pragma.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
			pragma.execute("PRAGMA journal_mode = WAL");
			pragma.execute("PRAGMA synchronous = FULL");
			pragma.execute("PRAGMA fullfsync = ON");
			pragma.execute("PRAGMA checkpoint_fullfsync = ON");
		}
		catch (SQLException refused)
		{
			connection.close();
			throw refused;
		}
		return connection;
	}

	/**
	 * Refuses a database of another program or of a later schema
	 *
	 * @return the schema version, 0 for a database without tables yet
	 */
	private int requireIdentity(final Connection connection) throws SQLException, IOException
	{
		int application = pragmaNumber(connection, "application_id");
		int version = pragmaNumber(connection, "user_version");
		if (application == 0 && version == 0 && isEmpty(connection))
		{
			return 0;
		}
		if (application != APPLICATION_ID)
		{
			throw new IOException(path + " is an SQLite database but not a lethenon chain "
				+ "(application id " + application + "); it is not used");
		}
		if (version != SCHEMA_VERSION)
		{
			throw new IOException("the chain database at " + path + " has tables of version "
				+ version + " and this build reads version " + SCHEMA_VERSION + "; it is not used");
		}
		return version;
	}

	private void requireIntegrity(final Connection connection) throws SQLException, IOException
	{
		List<String> found = new ArrayList<>();
		try (Statement check = connection.createStatement();
			ResultSet rows = check.executeQuery("PRAGMA integrity_check"))
		{
			while (rows.next())
			{
				found.add(rows.getString(1));
			}
		}
		if (!found.equals(List.of("ok")))
		{
			throw refused("SQLite's integrity check reports " + found);
		}
	}

	private List<BlockBody> verifiedBlocks(final Connection connection)
		throws SQLException, IOException
	{
		List<BlockBody> blocks = new ArrayList<>();
		Bytes below = null;
		try (Statement select = connection.createStatement();
			ResultSet rows = select
				.executeQuery("SELECT height, hash, body FROM block ORDER BY height"))
		{
			while (rows.next())
			{
				long height = rows.getLong(1);
				if (height != blocks.size())
				{
					throw refused("there is no block at height " + blocks.size()
						+ "; the next one is at height " + height);
				}
				BlockBody block = decoded(height, rows.getBytes(3));
				Bytes hash = Blocks.hashOf(block);
				if (!hash.equals(Bytes.of(rows.getBytes(2))))
				{
					throw refused("the block at height " + height
						+ " does not have the hash stored for it");
				}
				if (below != null && !below.equals(block.previousHash()))
				{
					throw refused("the block at height " + height
						+ " does not name the block below it");
				}
				blocks.add(block);
				below = hash;
			}
		}
		return blocks;
	}

	private BlockBody decoded(final long height, final byte[] body) throws IOException
	{
		List<BlockBody> decoded;
		try
		{
			decoded = CanonicalEncoding.readChain(body);
		}
		catch (IllegalArgumentException unreadable)
		{
			throw refused("the block at height " + height + " cannot be decoded: "
				+ unreadable.getMessage());
		}
		if (decoded.size() != 1 || decoded.getFirst().height() != height)
		{
			throw refused("the bytes stored at height " + height + " are not the block of that "
				+ "height");
		}
		return decoded.getFirst();
	}

	private IOException refused(final String what)
	{
		return new IOException("the chain database at " + path + " does not verify: " + what
			+ "; it is not used, and it is left as it is");
	}

	private static void createTables(final Connection connection) throws SQLException
	{
		try (Statement create = connection.createStatement())
		{
			for (String statement : SCHEMA)
			{
				create.execute(statement);
			}
		}
	}

	private static void replaceBlocks(final Connection connection, final List<BlockBody> blocks)
		throws SQLException
	{
		int kept = heldAlready(connection, blocks);
		try (PreparedStatement delete = connection
			.prepareStatement("DELETE FROM block WHERE height >= ?"))
		{
			delete.setLong(1, kept);
			delete.executeUpdate();
		}
		try (PreparedStatement insert = connection
			.prepareStatement("INSERT INTO block (height, hash, body) VALUES (?, ?, ?)"))
		{
			for (BlockBody block : blocks.subList(kept, blocks.size()))
			{
				insert.setLong(1, block.height());
				insert.setBytes(2, Blocks.hashOf(block).toByteArray());
				insert.setBytes(3, CanonicalEncoding.encodeChain(List.of(block)));
				insert.executeUpdate();
			}
		}
	}

	/**
	 * How many blocks at the bottom of the chain the database holds already: the height above the
	 * highest block it holds with the same hash, searched from the top, since a block's hash
	 * covers every block below it
	 */
	private static int heldAlready(final Connection connection, final List<BlockBody> blocks)
		throws SQLException
	{
		try (PreparedStatement hashAt = connection
			.prepareStatement("SELECT hash FROM block WHERE height = ?"))
		{
			for (int height = Math.min(blocks.size(), storedCount(connection)) - 1; height >= 0;
				height--)
			{
				hashAt.setLong(1, height);
				try (ResultSet stored = hashAt.executeQuery())
				{
					if (stored.next() && Blocks.hashOf(blocks.get(height))
						.equals(Bytes.of(stored.getBytes(1))))
					{
						return height + 1;
					}
				}
			}
		}
		return 0;
	}

	private static int storedCount(final Connection connection) throws SQLException
	{
		try (Statement count = connection.createStatement();
			ResultSet rows = count.executeQuery("SELECT count(*) FROM block"))
		{
			rows.next();
			return rows.getInt(1);
		}
	}

	private static void replaceWaiting(final Connection connection,
		final List<SignedTransaction> transfers) throws SQLException
	{
		try (Statement delete = connection.createStatement())
		{
			delete.execute("DELETE FROM waiting");
		}
		try (PreparedStatement insert = connection
			.prepareStatement("INSERT INTO waiting (position, body) VALUES (?, ?)"))
		{
			for (int position = 0; position < transfers.size(); position++)
			{
				insert.setInt(1, position);
				insert.setBytes(2, CanonicalEncoding.encode(transfers.get(position)));
				insert.executeUpdate();
			}
		}
	}

	private static int pragmaNumber(final Connection connection, final String name)
		throws SQLException
	{
		try (Statement pragma = connection.createStatement();
			ResultSet value = pragma.executeQuery("PRAGMA " + name))
		{
			return value.next() ? value.getInt(1) : 0;
		}
	}

	private static boolean isEmpty(final Connection connection) throws SQLException
	{
		try (Statement count = connection.createStatement();
			ResultSet rows = count.executeQuery("SELECT count(*) FROM sqlite_schema"))
		{
			rows.next();
			return rows.getInt(1) == 0;
		}
	}
}
