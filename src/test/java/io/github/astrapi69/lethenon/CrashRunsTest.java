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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The harness of the crash tests (#240): what it reads from a killed writer and where the writer
 * leaves its files decide whether {@link ChainCrashTest} and {@link PowerCutTest} can be believed.
 * Both of its defects showed as broken chains in the release gate of 0.7.0, with every database
 * whole.
 */
class CrashRunsTest
{

	/** Lines {@link Talker} prints; well inside a pipe's buffer */
	static final int TALKED_LINES = 20;

	@TempDir
	Path directory;

	@Test
	@DisplayName("what a writer wrote before the kill can be read to its end after the kill")
	void theOutputWrittenBeforeTheKillCanBeReadAfterIt() throws Exception
	{
		Path said = directory.resolve("said");
		Process talker = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
			"-cp", System.getProperty("java.class.path"), Talker.class.getName(), said.toString())
			.redirectError(directory.resolve("errors.txt").toFile()).start();
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		while (!Files.exists(said) && talker.isAlive() && System.nanoTime() < until)
		{
			Thread.sleep(1);
		}
		assertTrue(Files.exists(said), "the talker did not get to the end of its lines");

		CrashRuns.kill(talker);
		List<String> lines = new ArrayList<>();
		CrashRuns.readLines(talker, lines);

		assertEquals(IntStream.rangeClosed(1, TALKED_LINES).mapToObj(n -> "line " + n).toList(),
			lines);
	}

	@Test
	@DisplayName("a killed writer leaves its copy of sqlite-jdbc's native library where it was told, not in java.io.tmpdir")
	void aKilledWriterLeavesItsNativeLibraryWhereItWasTold() throws Exception
	{
		List<BlockBody> trunk = TestChains.extended(TestChains.chainWith(TestChains.MINER), 2,
			"trunk");
		List<BlockBody> first = TestChains.extended(trunk, 1, "first");
		Path firstChain = CrashRuns.chainFixture(first, directory.resolve("first.chain"));
		Path secondChain = CrashRuns.chainFixture(TestChains.extended(trunk, 2, "second"),
			directory.resolve("second.chain"));
		Path database = ChainCrashTest.prepared(directory.resolve("run"), first, List.of());
		Path libraries = Files.createDirectories(directory.resolve("libraries"));

		CrashRuns.Killed killed = CrashRuns.killAfter("done 1", 0,
			List.of("fork", database.toString(), firstChain.toString(), secondChain.toString()),
			Map.of(), directory.resolve("errors.txt"), libraries);

		assertFalse(killed.exitedByItself(), "the writer ended before the kill: " + killed);
		assertTrue(killed.lastDone() >= 1, "the writer never finished a write: " + killed);
		try (Stream<Path> files = Files.list(libraries))
		{
			assertEquals(1, files.map(file -> file.getFileName().toString())
				.filter(name -> name.startsWith("sqlite-") && name.contains("sqlitejdbc")
					&& !name.endsWith(".lck"))
				.count(), "copies of the native library in " + libraries);
		}
	}

	/**
	 * A process that prints {@value #TALKED_LINES} lines, then creates the file it is given, then
	 * waits to be killed
	 */
	static final class Talker
	{

		private Talker()
		{
		}

		/**
		 * Talks, then waits
		 *
		 * @param arguments
		 *            the file to create once every line is printed
		 * @throws Exception
		 *             on anything; the test reads it as a missing file
		 */
		public static void main(final String[] arguments) throws Exception
		{
			PrintStream out = System.out;
			for (int line = 1; line <= TALKED_LINES; line++)
			{
				out.println("line " + line);
			}
			out.flush();
			Files.createFile(Path.of(arguments[0]));
			Thread.sleep(Duration.ofMinutes(1));
		}
	}
}
