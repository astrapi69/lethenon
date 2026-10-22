package io.github.astrapi69.lethenon;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.sqlite.SQLiteConfig;

/**
 * Measurement only, for roundtrip.sh (the way back of lethenon 0.7.0); nothing of it is shipped.
 * Compiled and run twice, against the lib/ of the published 0.6.1 and against the lib/ of 0.7.0. In
 * lethenon's package, so that it reads the database through that version's own reader,
 * {@link ChainDatabase}, with its checks. It first served the round trip before mystic-crypt-ui 8.10.
 * <ul>
 * <li>{@code state <database>}: the version reads and replays the database and lists what
 * {@code Probe05 state} lists for a chain file, in the same form</li>
 * <li>{@code rows <database>}: every row of every table, read-only, in full, with the database's
 * application id, schema version and tables, for a comparison block by block</li>
 * </ul>
 */
public class ProbeDb
{

	public static void main(final String[] arguments) throws Exception
	{
		switch (arguments[0])
		{
			case "state" -> state(Path.of(arguments[1]));
			case "rows" -> rows(Path.of(arguments[1]));
			default -> throw new IllegalArgumentException("state or rows, not " + arguments[0]);
		}
	}

	private static void state(final Path database) throws Exception
	{
		ChainDatabase chain = new ChainDatabase(database);
		List<BlockBody> blocks = chain.blocks();
		Replay replay = Replay.verify(blocks);
		ChainState state = replay.finalState();
		SortedSet<String> accounts = new TreeSet<>();
		accounts.add(ChainState.POOL.toString());
		for (BlockBody block : blocks)
		{
			accounts.add(block.beneficiary().toString());
			for (SignedTransaction transfer : block.transactions())
			{
				accounts.add(transfer.body().sender().toString());
				accounts.add(transfer.body().recipient().key().toString());
			}
		}
		System.out.println("chain " + state.chainIdentifier() + ", " + replay.blocks() + " blocks, "
			+ replay.transactions() + " transfers, " + replay.signatures() + " signatures");
		System.out.println("supply " + state.supply() + ", total " + state.total() + ", minted "
			+ state.minted() + ", " + accounts.size() + " accounts");
		for (String account : accounts)
		{
			Bytes key = Bytes.ofHex(account);
			System.out.println("account " + account + " holds " + state.balanceOf(key)
				+ ", next nonce " + state.nextNonceOf(key));
		}
		for (SignedTransaction transfer : chain.waiting())
		{
			System.out.println("waiting " + sha256(CanonicalEncoding.encode(transfer)) + ", nonce "
				+ transfer.body().nonce() + ", " + transfer.body().amount() + ", " + transfer.suite());
		}
	}

	private static void rows(final Path database) throws Exception
	{
		SQLiteConfig readOnly = new SQLiteConfig();
		readOnly.setReadOnly(true);
		HexFormat hex = HexFormat.of();
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database,
			readOnly.toProperties()); Statement select = connection.createStatement())
		{
			for (String pragma : List.of("application_id", "user_version"))
			{
				try (ResultSet row = select.executeQuery("PRAGMA " + pragma))
				{
					row.next();
					System.out.println(pragma + " " + row.getLong(1));
				}
			}
			try (ResultSet rows = select.executeQuery(
				"SELECT type, name, sql FROM sqlite_schema ORDER BY type, name"))
			{
				while (rows.next())
				{
					System.out.println("schema " + rows.getString(1) + " " + rows.getString(2) + ": "
						+ rows.getString(3));
				}
			}
			try (ResultSet rows = select.executeQuery("SELECT height, hash, body FROM block ORDER BY height"))
			{
				while (rows.next())
				{
					System.out.println("block " + rows.getLong(1) + " " + hex.formatHex(rows.getBytes(2))
						+ " " + hex.formatHex(rows.getBytes(3)));
				}
			}
			try (ResultSet rows = select.executeQuery("SELECT position, body FROM waiting ORDER BY position"))
			{
				while (rows.next())
				{
					System.out.println("waiting " + rows.getLong(1) + " " + hex.formatHex(rows.getBytes(2)));
				}
			}
		}
	}

	private static String sha256(final byte[] bytes) throws Exception
	{
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
