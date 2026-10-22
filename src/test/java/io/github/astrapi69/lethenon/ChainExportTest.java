/**
 * The MIT License
 *
 * Copyright (C) 2026 Asterios Raptis
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The way back from the chain database to the chain file of 0.5.0 (#224), measured as the round
 * trip that was the condition for mystic-crypt-ui 8.10: a chain file that the published 0.5.0 wrote
 * is migrated, exported, and migrated again. What 0.5.0 writes is not computed here: the fixtures are
 * files the published 0.5.0 wrote (src/test/resources/chain/README.md), so that the export is
 * compared with the old program and not with this build's idea of it.
 */
class ChainExportTest
{

	@TempDir
	Path directory;

	static Stream<Arguments> chainFilesThe050Wrote()
	{
		return Stream.of(
			Arguments.argumentSet("the test network's chain as 0.5.0 left it",
				"lethenon-test-2-at-0.5.0.lethenon", 733, 0),
			Arguments.argumentSet(
				"a chain 0.5.0 wrote, with ML-DSA-65, a published address and three transfers waiting",
				"written-by-0.5.0.lethenon", 4, 3));
	}

	@ParameterizedTest
	@MethodSource("chainFilesThe050Wrote")
	@DisplayName("a chain file of 0.5.0, migrated and exported, is the file 0.5.0 wrote, byte for byte, pool included, and migrates again to the same blocks and balances (#224)")
	void theRoundTrip_endsWhereItBegan(final String fixture, final int blocks, final int waiting)
		throws Exception
	{
		byte[] original = fixture(fixture).orElseThrow();
		Optional<byte[]> originalPending = fixture(fixture + ".pending");
		Path first = directory.resolve("first.lethenon");
		Files.write(first, original);
		if (originalPending.isPresent())
		{
			Files.write(pending(first), originalPending.get());
		}
		ChainFile migrated = new ChainFile(first);
		List<BlockBody> migratedBlocks = migrated.read();
		List<SignedTransaction> migratedWaiting = migrated.readPending();
		assertEquals(blocks, migratedBlocks.size());
		assertEquals(waiting, migratedWaiting.size());
		Path back = directory.resolve("back.lethenon");

		ChainExport.Exported exported = new ChainExport(first).to(back);

		assertEquals(blocks, exported.blocks());
		assertEquals(waiting, exported.waiting());
		byte[] exportedBytes = Files.readAllBytes(back);
		assertArrayEquals(original, exportedBytes, "the chain file 0.5.0 wrote");
		if (originalPending.isPresent())
		{
			assertArrayEquals(originalPending.get(), Files.readAllBytes(pending(back)),
				"the pending file 0.5.0 wrote");
		}
		else
		{
			assertFalse(Files.exists(pending(back)), "no pool, no pending file, as in 0.5.0");
		}
		ChainFile again = new ChainFile(back);
		List<BlockBody> againBlocks = again.read();
		List<SignedTransaction> againWaiting = again.readPending();
		assertEquals(encoded(migratedBlocks), encoded(againBlocks), "the second migration");
		assertEquals(encodedTransfers(migratedWaiting), encodedTransfers(againWaiting), "the pool");
		SortedMap<String, String> before = ledger(CanonicalEncoding.readChain(original));
		assertEquals(before, ledger(migratedBlocks), "balances after the first migration");
		assertEquals(before, ledger(CanonicalEncoding.readChain(exportedBytes)),
			"balances of the file exported");
		assertEquals(before, ledger(againBlocks), "balances after the second migration");
	}

	@Test
	@DisplayName("a chain file of 0.5.0 is not exported, and is left as it is, not migrated (#224)")
	void aChainFileOf050_isRefusedAndLeftAsItIs() throws Exception
	{
		byte[] original = fixture("written-by-0.5.0.lethenon").orElseThrow();
		Path chain = directory.resolve("old.lethenon");
		Files.write(chain, original);

		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> new ChainExport(chain).to(directory.resolve("out.lethenon")));

		assertTrue(refused.getMessage().contains("is not a chain database"), refused.getMessage());
		assertArrayEquals(original, Files.readAllBytes(chain));
		assertFalse(Files.exists(directory.resolve("old.lethenon.legacy")), "nothing migrated");
		assertFalse(Files.exists(directory.resolve("out.lethenon")), "nothing written");
	}

	@Test
	@DisplayName("a chain that does not exist is not exported (#224)")
	void aMissingChain_isRefused()
	{
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> new ChainExport(directory.resolve("none.lethenon"))
				.to(directory.resolve("out.lethenon")));

		assertTrue(refused.getMessage().contains("none.lethenon"), refused.getMessage());
		assertFalse(Files.exists(directory.resolve("out.lethenon")));
	}

	static Stream<Arguments> filesInTheWay()
	{
		return Stream.of(Arguments.argumentSet("the chain file", "out.lethenon"),
			Arguments.argumentSet("its pending file", "out.lethenon.pending"));
	}

	@ParameterizedTest
	@MethodSource("filesInTheWay")
	@DisplayName("an export overwrites nothing: a target that exists is refused and left as it is (#224)")
	void aTargetThatExists_isNotOverwritten(final String inTheWay) throws Exception
	{
		Path database = aDatabaseWithWaitingTransfers();
		Path existing = directory.resolve(inTheWay);
		Files.writeString(existing, "keep me", StandardCharsets.UTF_8);

		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> new ChainExport(database).to(directory.resolve("out.lethenon")));

		assertTrue(refused.getMessage().contains(existing.getFileName().toString()),
			refused.getMessage());
		assertEquals("keep me", Files.readString(existing, StandardCharsets.UTF_8));
	}

	/** The database the round trip's chain with waiting transfers migrates to */
	private Path aDatabaseWithWaitingTransfers() throws IOException
	{
		Path chain = directory.resolve("database.lethenon");
		Files.write(chain, fixture("written-by-0.5.0.lethenon").orElseThrow());
		Files.write(pending(chain), fixture("written-by-0.5.0.lethenon.pending").orElseThrow());
		new ChainFile(chain).read();
		return chain;
	}

	/**
	 * Every account the chain names, with its balance and next nonce, and the supply: what a replay
	 * of the chain says about each of them
	 */
	private static SortedMap<String, String> ledger(final List<BlockBody> chain)
	{
		ChainState state = Replay.verify(chain).finalState();
		SortedMap<String, String> ledger = new TreeMap<>();
		ledger.put("supply", state.supply().toString());
		List<Bytes> accounts = new java.util.ArrayList<>(List.of(ChainState.POOL));
		for (BlockBody block : chain)
		{
			accounts.add(block.beneficiary());
			for (SignedTransaction transfer : block.transactions())
			{
				accounts.add(transfer.body().sender());
				accounts.add(transfer.body().recipient().key());
			}
		}
		for (Bytes account : accounts)
		{
			ledger.put(account.toString(),
				state.balanceOf(account) + ", next nonce " + state.nextNonceOf(account));
		}
		return ledger;
	}

	private static List<String> encoded(final List<BlockBody> blocks)
	{
		return blocks.stream()
			.map(block -> java.util.HexFormat.of().formatHex(CanonicalEncoding.encodeChain(List.of(block))))
			.toList();
	}

	private static List<String> encodedTransfers(final List<SignedTransaction> transfers)
	{
		return transfers.stream()
			.map(transfer -> java.util.HexFormat.of().formatHex(CanonicalEncoding.encode(transfer)))
			.toList();
	}

	private static Path pending(final Path chain)
	{
		return chain.resolveSibling(chain.getFileName() + ".pending");
	}

	private static Optional<byte[]> fixture(final String name) throws IOException
	{
		try (InputStream stream = ChainExportTest.class.getResourceAsStream("/chain/" + name))
		{
			return stream == null ? Optional.empty() : Optional.of(stream.readAllBytes());
		}
	}
}
