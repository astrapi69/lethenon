import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.sqlite.SQLiteConfig;

import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.CanonicalEncoding;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.ChainState;
import io.github.astrapi69.lethenon.Replay;
import io.github.astrapi69.lethenon.SignedTransaction;

/**
 * Measurement only, for roundtrip.sh (the way back of lethenon 0.7.0); nothing of it is shipped.
 * Compiled and run against the lib/ of the published lethenon 0.5.0 alone, plus the SQLite driver to
 * read a chain database. It first served the round trip before mystic-crypt-ui 8.10.
 * <ul>
 * <li>{@code state <chain file>}: 0.5.0 replays the chain file and lists every account that appears
 * in it with its balance and next nonce, the supply, and the transfers waiting in its pool</li>
 * <li>{@code export <database> <chain file>}: reads the blocks and the waiting transfers out of a
 * chain database, decodes them with 0.5.0's own decoder, and writes them with 0.5.0's own chain
 * writer, {@link ChainFile#write} and {@link ChainFile#writePending}</li>
 * </ul>
 */
public class Probe05
{

	public static void main(final String[] arguments) throws Exception
	{
		switch (arguments[0])
		{
			case "state" -> state(Path.of(arguments[1]));
			case "export" -> export(Path.of(arguments[1]), Path.of(arguments[2]));
			default -> throw new IllegalArgumentException("state or export, not " + arguments[0]);
		}
	}

	private static void state(final Path chain) throws Exception
	{
		ChainFile file = new ChainFile(chain);
		List<BlockBody> blocks = file.require();
		print(blocks, Replay.verify(blocks), file.readPending());
	}

	private static void export(final Path database, final Path chain) throws Exception
	{
		if (Files.exists(chain))
		{
			throw new IllegalStateException("not overwriting " + chain);
		}
		List<BlockBody> blocks = new ArrayList<>();
		List<SignedTransaction> waiting = new ArrayList<>();
		SQLiteConfig readOnly = new SQLiteConfig();
		readOnly.setReadOnly(true);
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database,
			readOnly.toProperties()); Statement select = connection.createStatement())
		{
			try (ResultSet rows = select.executeQuery("SELECT height, body FROM block ORDER BY height"))
			{
				while (rows.next())
				{
					List<BlockBody> one = CanonicalEncoding.readChain(rows.getBytes(2));
					if (one.size() != 1 || one.get(0).height() != rows.getLong(1))
					{
						throw new IllegalStateException("the row at height " + rows.getLong(1)
							+ " does not hold that one block");
					}
					blocks.add(one.get(0));
				}
			}
			try (ResultSet rows = select.executeQuery("SELECT body FROM waiting ORDER BY position"))
			{
				while (rows.next())
				{
					waiting.add(CanonicalEncoding.readSignedTransaction(rows.getBytes(1)));
				}
			}
		}
		ChainFile file = new ChainFile(chain);
		file.write(blocks);
		file.writePending(waiting);
		System.out.println("0.5.0 wrote " + blocks.size() + " blocks and " + waiting.size()
			+ " waiting transfers to " + chain);
	}

	private static void print(final List<BlockBody> blocks, final Replay replay,
		final List<SignedTransaction> pending) throws Exception
	{
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
		for (SignedTransaction transfer : pending)
		{
			System.out.println("waiting " + sha256(CanonicalEncoding.encode(transfer)) + ", nonce "
				+ transfer.body().nonce() + ", " + transfer.body().amount() + ", " + transfer.suite());
		}
	}

	private static String sha256(final byte[] bytes) throws Exception
	{
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
