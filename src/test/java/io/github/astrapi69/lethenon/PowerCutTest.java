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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A power cut, simulated (#202): the writer runs with {@code src/test/native/iolog.c} preloaded,
 * which records every write, truncation, sync and deletion it makes in its directory. After the
 * kill the test builds what a disk could hold had the power gone at that moment: every operation
 * on a file before that file's last sync is on the disk, and every operation after it is there
 * whole, torn after a random number of 512-byte sectors, or missing - each chosen at random, a
 * later one independently of an earlier one, the way a disk reorders what it was not told to keep
 * in order. That is the model of SQLite's own crash tests, and it is what makes the difference
 * between a synced and an unsynced database visible: a kill alone cannot (ChainCrashTest).
 * <p>
 * Several such disks are built from each run, and each must hold a state the writer had finished,
 * or the one it had begun, whole. The shared-memory index ({@code -shm}) is left out: it is a cache
 * that SQLite rebuilds when the last connection is gone, and it lives in memory mapped pages, which
 * no record sees.
 * <p>
 * Runs on Linux with a C compiler; elsewhere it is skipped and says why.
 */
class PowerCutTest
{

	/** Disks built from each run */
	static final int DISKS_PER_RUN = 5;

	private static final Path SHIM = Path.of("src/test/native/iolog.c");

	static Path preload;

	@TempDir
	Path directory;

	@BeforeAll
	static void compileTheRecorder() throws Exception
	{
		assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux"),
			"the power-cut test preloads a library into the writer, which needs Linux");
		Path library = Files.createTempDirectory("iolog").resolve("iolog.so");
		Process compiler;
		try
		{
			compiler = new ProcessBuilder("cc", "-shared", "-fPIC", "-O2", "-o", library.toString(),
				SHIM.toString(), "-ldl", "-lpthread").redirectErrorStream(true).start();
		}
		catch (IOException noCompiler)
		{
			assumeTrue(false, "no C compiler to build " + SHIM + ": " + noCompiler.getMessage());
			return;
		}
		String said = new String(compiler.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!compiler.waitFor(60, TimeUnit.SECONDS) || compiler.exitValue() != 0)
		{
			throw new IllegalStateException("cc could not build " + SHIM + ": " + said);
		}
		preload = library;
		ChainCrashTest.mineTheChains();
	}

	@Test
	@DisplayName("a power cut while blocks are written loses no finished block and splits none")
	void aPowerCutWhileABlockIsWrittenLosesNoFinishedBlock() throws Exception
	{
		Path chain = CrashRuns.chainFixture(ChainCrashTest.grown, directory.resolve("grown.chain"));
		Random random = new Random();
		ChainCrashTest.Outcome outcome = new ChainCrashTest.Outcome("power cut while growing", "disks");
		for (int run = 0; run < CrashRuns.runs(); run++)
		{
			Path runDirectory = directory.resolve("run-" + run).toAbsolutePath();
			Path database = ChainCrashTest.prepared(runDirectory,
				ChainCrashTest.grown.subList(0, 1),
				ChainWriterProcess.waitingFor(1, ChainWriterProcess.transfers(1)));
			Map<String, byte[]> before = filesIn(runDirectory);
			CrashRuns.Killed killed = CrashRuns.killAtRandom(
				List.of("grow", database.toString(), chain.toString()),
				recording(runDirectory), random, directory.resolve("errors-" + run + ".txt"));
			List<Operation> operations = Operation.read(recordOf(runDirectory));
			for (int disk = 0; disk < DISKS_PER_RUN; disk++)
			{
				Path image = disk(before, operations, random,
					directory.resolve("disk-" + run + "-" + disk));
				outcome.count(killed, ChainCrashTest.checkGrown(image.resolve("chain.lethenon"),
					killed));
			}
		}
		outcome.assertAllWhole();
	}

	@Test
	@DisplayName("a power cut while a fork is rolled back leaves one chain or the other")
	void aPowerCutWhileAForkIsRolledBackLeavesOneChainOrTheOther() throws Exception
	{
		Path firstChain = CrashRuns.chainFixture(ChainCrashTest.first,
			directory.resolve("first.chain"));
		Path secondChain = CrashRuns.chainFixture(ChainCrashTest.second,
			directory.resolve("second.chain"));
		Random random = new Random();
		ChainCrashTest.Outcome outcome = new ChainCrashTest.Outcome("power cut while rolling back", "disks");
		for (int run = 0; run < CrashRuns.runs(); run++)
		{
			Path runDirectory = directory.resolve("run-" + run).toAbsolutePath();
			Path database = ChainCrashTest.prepared(runDirectory, ChainCrashTest.first, List.of());
			Map<String, byte[]> before = filesIn(runDirectory);
			CrashRuns.Killed killed = CrashRuns.killAtRandom(
				List.of("fork", database.toString(), firstChain.toString(),
					secondChain.toString()),
				recording(runDirectory), random, directory.resolve("errors-" + run + ".txt"));
			List<Operation> operations = Operation.read(recordOf(runDirectory));
			for (int disk = 0; disk < DISKS_PER_RUN; disk++)
			{
				Path image = disk(before, operations, random,
					directory.resolve("disk-" + run + "-" + disk));
				outcome.count(killed, ChainCrashTest.checkForked(image.resolve("chain.lethenon"),
					killed));
			}
		}
		outcome.assertAllWhole();
	}

	private static Map<String, String> recording(final Path runDirectory)
	{
		return Map.of("LD_PRELOAD", preload.toString(), "IOLOG_DIR", runDirectory + "/",
			"IOLOG_FILE", recordOf(runDirectory).toString());
	}

	private static Path recordOf(final Path runDirectory)
	{
		return runDirectory.resolveSibling(runDirectory.getFileName() + ".iolog");
	}

	private static Map<String, byte[]> filesIn(final Path runDirectory) throws IOException
	{
		Map<String, byte[]> files = new HashMap<>();
		try (Stream<Path> entries = Files.list(runDirectory))
		{
			for (Path entry : entries.toList())
			{
				files.put(entry.getFileName().toString(), Files.readAllBytes(entry));
			}
		}
		return files;
	}

	/**
	 * A disk as a power cut could have left it
	 *
	 * @param before
	 *            the files before the writer started, synced
	 * @param operations
	 *            what the writer did to them, in order
	 * @param random
	 *            decides the fate of every operation after its file's last sync
	 * @param where
	 *            the directory to build the disk in
	 * @return the directory
	 * @throws IOException
	 *             if it cannot be written
	 */
	static Path disk(final Map<String, byte[]> before, final List<Operation> operations,
		final Random random, final Path where) throws IOException
	{
		Map<String, Integer> lastSync = new HashMap<>();
		for (int index = 0; index < operations.size(); index++)
		{
			if (operations.get(index).kind() == 'S')
			{
				lastSync.put(operations.get(index).file(), index);
			}
		}
		Map<String, byte[]> files = new HashMap<>(before);
		for (int index = 0; index < operations.size(); index++)
		{
			Operation operation = operations.get(index);
			if (operation.file().endsWith("-shm"))
			{
				continue;
			}
			boolean synced = index < lastSync.getOrDefault(operation.file(), -1);
			operation.applyTo(files, synced ? Fate.WHOLE : Fate.values()[random.nextInt(3)],
				random);
		}
		Files.createDirectories(where);
		for (Map.Entry<String, byte[]> file : files.entrySet())
		{
			if (!file.getKey().endsWith("-shm"))
			{
				Files.write(where.resolve(file.getKey()), file.getValue());
			}
		}
		return where;
	}

	/** What becomes of an operation the disk was not told to keep */
	enum Fate
	{
		/** it reached the disk */
		WHOLE,
		/** it did not */
		MISSING,
		/** a write reached the disk up to a sector boundary */
		TORN
	}

	/**
	 * One operation of the record
	 *
	 * @param kind
	 *            W write, T truncate, S sync, U unlink
	 * @param file
	 *            the file's name in its directory
	 * @param offset
	 *            where a write starts, or the length a truncation leaves
	 * @param bytes
	 *            what a write wrote
	 */
	record Operation(char kind, String file, long offset, byte[] bytes)
	{

		static List<Operation> read(final Path record) throws IOException
		{
			List<Operation> operations = new ArrayList<>();
			if (!Files.exists(record))
			{
				return operations;
			}
			ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(record))
				.order(ByteOrder.nativeOrder());
			while (buffer.hasRemaining())
			{
				int start = buffer.position();
				try
				{
					operations.add(next(buffer));
				}
				catch (BufferUnderflowException cutOff)
				{
					// the writer was killed while recording its last operation: that operation
					// counts as never synced, the cautious reading (iolog.c)
					buffer.position(start);
					break;
				}
			}
			return operations;
		}

		private static Operation next(final ByteBuffer buffer)
		{
			{
				char kind = (char)buffer.get();
				byte[] path = new byte[Short.toUnsignedInt(buffer.getShort())];
				buffer.get(path);
				String file = Path.of(new String(path, StandardCharsets.UTF_8)).getFileName()
					.toString();
				switch (kind)
				{
					case 'W' -> {
						long offset = buffer.getLong();
						byte[] bytes = new byte[buffer.getInt()];
						buffer.get(bytes);
						return new Operation(kind, file, offset, bytes);
					}
					case 'T' -> {
						return new Operation(kind, file, buffer.getLong(), null);
					}
					case 'S', 'U' -> {
						return new Operation(kind, file, 0L, null);
					}
					default -> throw new IllegalStateException(
						"the record holds an operation '" + kind + "' it does not know");
				}
			}
		}

		void applyTo(final Map<String, byte[]> files, final Fate fate, final Random random)
		{
			if (fate == Fate.MISSING || kind == 'S')
			{
				return;
			}
			switch (kind)
			{
				case 'U' -> files.remove(file);
				case 'T' -> files.put(file,
					Arrays.copyOf(files.getOrDefault(file, new byte[0]), (int)offset));
				default -> write(files, fate == Fate.TORN
					? Arrays.copyOf(bytes, 512 * random.nextInt(bytes.length / 512 + 1))
					: bytes);
			}
		}

		private void write(final Map<String, byte[]> files, final byte[] written)
		{
			if (written.length == 0)
			{
				return;
			}
			byte[] content = files.getOrDefault(file, new byte[0]);
			int end = (int)offset + written.length;
			if (content.length < end)
			{
				content = Arrays.copyOf(content, end);
			}
			else
			{
				content = content.clone();
			}
			System.arraycopy(written, 0, content, (int)offset, written.length);
			files.put(file, content);
		}
	}
}
