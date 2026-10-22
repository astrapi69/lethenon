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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A chain and the transfers waiting for its next block live in one SQLite database at the chain's
 * path (#202, ADR 0006): every change is one transaction, a database that does not verify is
 * refused rather than used, and a chain file of an earlier version is migrated and compared before
 * it is replaced. What a killed process or a power cut leaves behind is tested in
 * {@link ChainCrashTest} and {@link PowerCutTest}.
 */
class ChainFileTest
{

	@TempDir
	Path directory;

	private final KeyPair holder = TransactionSigner.newKeyPair(SignatureSuite.ED25519);

	private final Bytes holderKey = TransactionSigner.asBytes(holder.getPublic());

	@Test
	void aChainThatWasNotWrittenYetIsEmpty_andRequiringItSaysSo() throws Exception
	{
		ChainFile file = new ChainFile(directory.resolve("chain.lethenon"));

		assertEquals(List.of(), file.read());
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			file::require);
		assertTrue(refused.getMessage().contains("chain.lethenon"), refused.getMessage());
		assertFalse(Files.exists(directory.resolve("chain.lethenon")),
			"reading a chain that is not there creates nothing");
	}

	@Test
	void aWrittenChainReadsBackAsTheSameBlocks_fromAnSqliteDatabaseAtThePath() throws Exception
	{
		Path path = directory.resolve("chain.lethenon");
		ChainFile file = new ChainFile(path);
		List<BlockBody> chain = TestChains.chainWith(holderKey, transfer(0L, 3L));

		file.write(chain);

		assertEquals(chain, file.read());
		assertEquals(chain, new ChainFile(path).read(), "and so does a second reader");
		byte[] header = Arrays.copyOf(Files.readAllBytes(path), 16);
		assertEquals("SQLite format 3\0", new String(header, StandardCharsets.US_ASCII));
	}

	@Test
	@DisplayName("the database runs with the durability settings ADR 0006 names")
	void theDatabaseRunsWithTheSettingsOfAdr0006() throws Exception
	{
		ChainFile file = new ChainFile(directory.resolve("chain.lethenon"));
		file.write(TestChains.extended(TestChains.chainWith(holderKey), 1, "settings"));

		Map<String, String> settings = file.settingsInForce();

		assertEquals("wal", settings.get("journal_mode"));
		assertEquals("2", settings.get("synchronous"), "2 is FULL");
		assertEquals("1", settings.get("fullfsync"));
	}

	@Test
	void waitingTransfersReadBackInTheOrderTheyWereSigned() throws Exception
	{
		ChainFile file = new ChainFile(directory.resolve("chain.lethenon"));
		List<SignedTransaction> waiting = List.of(transfer(0L, 3L), transfer(1L, 4L));

		assertEquals(List.of(), file.readPending(), "no database is no waiting transfer");
		file.writePending(waiting);

		assertEquals(waiting, file.readPending());
	}

	@Test
	void writingNoWaitingTransfersLeavesNoneWaiting() throws Exception
	{
		ChainFile file = new ChainFile(directory.resolve("chain.lethenon"));
		file.writePending(List.of(transfer(0L, 3L)));

		file.writePending(List.of());

		assertEquals(List.of(), file.readPending());
	}

	@Test
	void aChainAndItsWaitingTransfersAreWrittenTogether() throws Exception
	{
		ChainFile file = new ChainFile(directory.resolve("chain.lethenon"));
		List<BlockBody> chain = TestChains.extended(TestChains.chainWith(holderKey), 2, "both");
		List<SignedTransaction> waiting = List.of(transfer(0L, 3L));

		file.write(chain, waiting);

		assertEquals(chain, file.read());
		assertEquals(waiting, file.readPending());
	}

	@Test
	void writingOnlyTheChainKeepsTheWaitingTransfers() throws Exception
	{
		ChainFile file = new ChainFile(directory.resolve("chain.lethenon"));
		List<SignedTransaction> waiting = List.of(transfer(0L, 3L));
		file.writePending(waiting);

		file.write(TestChains.extended(TestChains.chainWith(holderKey), 1, "only"));

		assertEquals(waiting, file.readPending());
	}

	@Test
	void aForkReplacesTheBlocksAboveTheCommonAncestor_andBackAgain() throws Exception
	{
		ChainFile file = new ChainFile(directory.resolve("chain.lethenon"));
		List<BlockBody> first = TestChains.extended(TestChains.chainWith(holderKey), 6, "first");
		List<BlockBody> second = TestChains.extended(first.subList(0, 4), 5, "second");
		file.write(first);

		file.write(second);
		assertEquals(second, file.read());

		file.write(first);
		assertEquals(first, file.read());
	}

	@Test
	void aShorterChainRemovesTheBlocksAboveIt() throws Exception
	{
		ChainFile file = new ChainFile(directory.resolve("chain.lethenon"));
		List<BlockBody> longer = TestChains.extended(TestChains.chainWith(holderKey), 6, "long");
		file.write(longer);

		file.write(longer.subList(0, 3));

		assertEquals(longer.subList(0, 3), file.read());
	}

	@Test
	void aChainOfAnotherGenesisBlockReplacesTheWholeChain() throws Exception
	{
		ChainFile file = new ChainFile(directory.resolve("chain.lethenon"));
		file.write(TestChains.extended(TestChains.chainWith(holderKey), 3, "one"));
		List<BlockBody> other = TestChains.extended(
			TestChains.funding(Chain.TEST_IDENTIFIER, holderKey, 1_760_000_000_000L), 2, "other");

		file.write(other);

		assertEquals(other, file.read());
	}

	static Stream<Arguments> damage()
	{
		return Stream.of(
			Arguments.of("a block whose bytes changed", "height 3",
				"UPDATE block SET body = ? WHERE height = 3", (Damage)ChainFileTest::otherBlockBytes),
			Arguments.of("a block that went missing", "height 3",
				"DELETE FROM block WHERE height = 3", null),
			Arguments.of("a stored hash that changed", "height 4",
				"UPDATE block SET hash = zeroblob(32) WHERE height = 4", null),
			Arguments.of("a block that names another predecessor", "height 3",
				"UPDATE block SET body = ?, hash = ? WHERE height = 3",
				(Damage)ChainFileTest::wellFormedButUnlinked));
	}

	@ParameterizedTest(name = "{0} is refused, not used")
	@MethodSource("damage")
	void aDatabaseThatDoesNotVerifyIsRefusedAndLeftAsItIs(final String what, final String where,
		final String statement, final Damage replacement) throws Exception
	{
		Path path = directory.resolve("chain.lethenon");
		List<BlockBody> chain = TestChains.extended(TestChains.chainWith(holderKey), 4, "damage");
		new ChainFile(path).write(chain);
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
			PreparedStatement update = connection.prepareStatement(statement))
		{
			if (replacement != null)
			{
				replacement.bind(update, chain);
			}
			update.executeUpdate();
		}
		byte[] damaged = Files.readAllBytes(path);

		IOException refused = assertThrows(IOException.class, () -> new ChainFile(path).read());

		assertTrue(refused.getMessage().contains(path.toString()), refused.getMessage());
		assertTrue(refused.getMessage().contains(where), refused.getMessage());
		assertTrue(refused.getMessage().contains("not used"), refused.getMessage());
		assertArrayEquals(damaged, Files.readAllBytes(path), "a refused database is not repaired");
	}

	@Test
	void anSqliteDatabaseOfAnotherProgramIsRefused() throws Exception
	{
		Path path = directory.resolve("chain.lethenon");
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
			Statement statement = connection.createStatement())
		{
			statement.execute("CREATE TABLE notes(text TEXT)");
		}

		IOException refused = assertThrows(IOException.class, () -> new ChainFile(path).read());

		assertTrue(refused.getMessage().contains("not a lethenon chain"), refused.getMessage());
	}

	@Test
	void aChainFileOfAnEarlierVersionIsMigrated_andKeptBeside() throws Exception
	{
		Path path = directory.resolve("chain.lethenon");
		List<BlockBody> chain = TestChains.extended(TestChains.chainWith(holderKey), 3, "legacy");
		List<SignedTransaction> waiting = List.of(transfer(0L, 3L), transfer(1L, 4L));
		byte[] legacyChain = CanonicalEncoding.encodeChain(chain);
		byte[] legacyPending = legacyPending(waiting);
		Files.write(path, legacyChain);
		Files.write(directory.resolve("chain.lethenon.pending"), legacyPending);

		ChainFile file = new ChainFile(path);

		assertEquals(chain, file.read());
		assertEquals(waiting, file.readPending());
		assertEquals("SQLite format 3\0",
			new String(Arrays.copyOf(Files.readAllBytes(path), 16), StandardCharsets.US_ASCII));
		assertArrayEquals(legacyChain, Files.readAllBytes(directory.resolve("chain.lethenon.legacy")));
		assertArrayEquals(legacyPending,
			Files.readAllBytes(directory.resolve("chain.lethenon.pending.legacy")));
		assertFalse(Files.exists(directory.resolve("chain.lethenon.pending")));
		assertFalse(Files.exists(directory.resolve("chain.lethenon.migrating")));
	}

	@Test
	void aMigrationThatWasInterruptedBeforeTheComparisonStartsAgain() throws Exception
	{
		Path path = directory.resolve("chain.lethenon");
		List<BlockBody> chain = TestChains.extended(TestChains.chainWith(holderKey), 2, "again");
		Files.write(path, CanonicalEncoding.encodeChain(chain));
		Files.write(directory.resolve("chain.lethenon.migrating"), new byte[] { 1, 2, 3 });

		assertEquals(chain, new ChainFile(path).read());
		assertFalse(Files.exists(directory.resolve("chain.lethenon.migrating")));
	}

	@Test
	void aMigrationThatWasInterruptedBetweenTheTwoRenamesIsFinished() throws Exception
	{
		Path path = directory.resolve("chain.lethenon");
		List<BlockBody> chain = TestChains.extended(TestChains.chainWith(holderKey), 2, "finish");
		new ChainFile(directory.resolve("chain.lethenon.migrating")).write(chain);
		Files.write(directory.resolve("chain.lethenon.legacy"), CanonicalEncoding.encodeChain(chain));

		assertEquals(chain, new ChainFile(path).read());
		assertFalse(Files.exists(directory.resolve("chain.lethenon.migrating")));
	}

	@Test
	void aMigratedDatabaseThatDiffersFromTheOldFileIsRefused_andNothingIsRenamed() throws Exception
	{
		Path path = directory.resolve("chain.lethenon");
		List<BlockBody> chain = TestChains.extended(TestChains.chainWith(holderKey), 3, "differs");
		new ChainFile(directory.resolve("chain.lethenon.migrating")).write(chain.subList(0, 3));
		Files.write(directory.resolve("chain.lethenon.legacy"), CanonicalEncoding.encodeChain(chain));

		IOException refused = assertThrows(IOException.class, () -> new ChainFile(path).read());

		assertTrue(refused.getMessage().contains("differs"), refused.getMessage());
		assertTrue(Files.exists(directory.resolve("chain.lethenon.migrating")));
		assertTrue(Files.exists(directory.resolve("chain.lethenon.legacy")));
		assertFalse(Files.exists(path));
	}

	/**
	 * Chain files of an earlier version whose blocks do not fit together, the shape the consumer
	 * check of 0.6.0 handed the library (#216)
	 */
	static Stream<Arguments> unfittingLegacyChains()
	{
		List<BlockBody> chain = TestChains.extended(TestChains.chainWith(Bytes.of(new byte[] { 7 })), 3,
			"legacy");
		List<BlockBody> other = TestChains.extended(TestChains.chainWith(Bytes.of(new byte[] { 7 })), 3,
			"another chain");
		List<BlockBody> gap = new java.util.ArrayList<>(chain);
		gap.remove(2);
		List<BlockBody> foreign = new java.util.ArrayList<>(chain.subList(0, 3));
		foreign.add(other.get(3));
		return Stream.of(
			Arguments.of("a block of another chain on top", foreign,
				"block 3 names " + other.get(3).previousHash() + " as the previous hash, and the "
					+ "block before it hashes to " + Blocks.hashOf(chain.get(2))),
			Arguments.of("a block missing in the middle", gap, "block 2 claims height 3"));
	}

	@ParameterizedTest(name = "{0}: refused before the migration, the file left as it was")
	@MethodSource("unfittingLegacyChains")
	@DisplayName("a chain file of an earlier version whose blocks do not fit together is refused as a chain, naming the file, and nothing is migrated (#216)")
	void aLegacyChainWhoseBlocksDoNotFitIsRefusedBeforeTheMigration(final String what,
		final List<BlockBody> blocks, final String reason) throws Exception
	{
		Path path = directory.resolve("chain.lethenon");
		byte[] legacy = CanonicalEncoding.encodeChain(blocks);
		Files.write(path, legacy);

		ChainRejected refused = assertThrows(ChainRejected.class, () -> new ChainFile(path).read());

		assertTrue(refused.getMessage().startsWith(path.toAbsolutePath().toString()),
			"the message names the file the person gave: " + refused.getMessage());
		assertTrue(refused.getMessage().contains(reason), refused.getMessage());
		assertTrue(refused.getMessage().contains("was not migrated"), refused.getMessage());
		assertArrayEquals(legacy, Files.readAllBytes(path), "the file stays as it was");
		assertFalse(Files.exists(directory.resolve("chain.lethenon.migrating")),
			"a refused migration leaves no database behind");
		assertFalse(Files.exists(directory.resolve("chain.lethenon.legacy")));
	}

	@Test
	void aFileThatIsNeitherAChainNorADatabaseIsRefused_andLeftAsItIs() throws Exception
	{
		Path path = directory.resolve("chain.lethenon");
		byte[] garbage = "not a chain at all".getBytes(StandardCharsets.US_ASCII);
		Files.write(path, garbage);

		assertThrows(RuntimeException.class, () -> new ChainFile(path).read());

		assertArrayEquals(garbage, Files.readAllBytes(path));
		assertFalse(Files.exists(directory.resolve("chain.lethenon.legacy")));
	}

	@Test
	void aLegacyPendingFileOfAnotherVersionIsRefusedWithBothVersions() throws Exception
	{
		Path path = directory.resolve("chain.lethenon");
		Files.write(path, CanonicalEncoding.encodeChain(TestChains.chainWith(holderKey)));
		Files.write(directory.resolve("chain.lethenon.pending"), new byte[] { 9, 0, 0, 0, 0 });

		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> new ChainFile(path).read());

		assertTrue(refused.getMessage().contains("version 9"), refused.getMessage());
		assertTrue(refused.getMessage().contains("reads version " + ChainFile.PENDING_VERSION),
			refused.getMessage());
		assertFalse(Files.exists(directory.resolve("chain.lethenon.legacy")));
	}

	@Test
	@DisplayName("the test chain fixture of an earlier version migrates block by block")
	void theChainFixtureMigratesBlockByBlock() throws Exception
	{
		Path path = directory.resolve("chain.lethenon");
		byte[] fixture;
		try (var stream = ChainFileTest.class.getResourceAsStream("/chain/chain.lethenon"))
		{
			fixture = stream.readAllBytes();
		}
		List<BlockBody> expected = CanonicalEncoding.readChain(fixture);
		Files.write(path, fixture);

		List<BlockBody> migrated = new ChainFile(path).read();

		assertEquals(expected.size(), migrated.size());
		for (int height = 0; height < expected.size(); height++)
		{
			assertArrayEquals(CanonicalEncoding.encodeChain(List.of(expected.get(height))),
				CanonicalEncoding.encodeChain(List.of(migrated.get(height))), "block " + height);
		}
	}

	@FunctionalInterface
	interface Damage
	{
		void bind(PreparedStatement update, List<BlockBody> chain) throws SQLException;
	}

	private static void otherBlockBytes(final PreparedStatement update, final List<BlockBody> chain)
		throws SQLException
	{
		update.setBytes(1, CanonicalEncoding.encodeChain(List.of(chain.get(2))));
	}

	private static void wellFormedButUnlinked(final PreparedStatement update,
		final List<BlockBody> chain) throws SQLException
	{
		BlockBody original = chain.get(3);
		BlockBody unlinked = Blocks.mine(new BlockBody(original.chainIdentifier(), 3L,
			Bytes.of(new byte[32]), original.beneficiary(), List.of(), original.timestamp(),
			DifficultyRule.MINIMUM, "unlinked"), 1_000_000L).orElseThrow();
		update.setBytes(1, CanonicalEncoding.encodeChain(List.of(unlinked)));
		update.setBytes(2, Blocks.hashOf(unlinked).toByteArray());
	}

	private static byte[] legacyPending(final List<SignedTransaction> transfers)
	{
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		bytes.write(ChainFile.PENDING_VERSION);
		bytes.writeBytes(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(transfers.size()).array());
		for (SignedTransaction transfer : transfers)
		{
			byte[] encoded = CanonicalEncoding.encode(transfer);
			bytes.writeBytes(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
			bytes.writeBytes(encoded);
		}
		return bytes.toByteArray();
	}

	private SignedTransaction transfer(final long nonce, final long leth)
	{
		return TransactionSigner.sign(new TransactionBody(Chain.IDENTIFIER, nonce, holderKey,
			Destination.direct(Bytes.of(new byte[] { 7 })), Amount.ofLeth(leth), Amount.ZERO,
			"waiting " + nonce), SignatureSuite.ED25519, holder.getPrivate());
	}
}
