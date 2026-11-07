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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * sqlite-jdbc's native library when lethenon shares a JVM with another copy of itself or of
 * sqlite-jdbc, each in a class loader of its own, as in a plugin host (#241): the first lethenon
 * loads the one copy in the user's cache, every other copy extracts its own as before, and
 * sqlite-jdbc logs no failure to load the library. Each scenario runs in a JVM of its own
 * ({@link NativeLibraryHost}), with the user's cache and the temporary directory under this test's
 * directory: the system properties and the class loader the library is bound to are JVM-wide, and
 * the cache under test is not the developer's or the runner's
 */
class NativeLibraryInClassLoadersTest
{

	/** JVMs whose two lethenons open their first chain at the same moment */
	private static final int TOGETHER_RUNS = 5;

	/** A library name of the host's own, for the scenario that sets one */
	private static final String HOST_NAME = "libsqlitejdbc-of-the-host.so";

	@TempDir
	Path directory;

	@Test
	@DisplayName("a lethenon loaded again, in a class loader of its own, writes, and sqlite-jdbc logs no failure to load the native library (#241)")
	void aLethenonLoadedAgain_loadsTheLibraryWithoutAFailure() throws Exception
	{
		List<String> said = hosted(List.of(), "again", "again");

		assertEquals(List.of(), failures(said), "sqlite-jdbc logged a failure to load its library");
		assertWritten("again", said, "first.lethenon", "again.lethenon");
		assertEquals(1, librariesInTheCache(), "the first lethenon put the library into the cache");
	}

	@Test
	@DisplayName("another program's sqlite-jdbc, in a class loader of its own, connects after lethenon, and logs no failure to load the native library (#241)")
	void anotherSqliteJdbc_afterLethenon_loadsTheLibraryWithoutAFailure() throws Exception
	{
		List<String> said = hosted(List.of(), "other", "other");

		assertEquals(List.of(), failures(said), "sqlite-jdbc logged a failure to load its library");
		assertWritten("other", said, "lethenon.lethenon");
		assertTrue(said.contains("answered 1"), () -> "the other program's connection answered: "
			+ said);
		assertEquals(1, librariesInTheCache(), "lethenon put the library into the cache");
	}

	@Test
	@DisplayName("two lethenons that open their first chain at the same moment, each in a class loader of its own, write, and sqlite-jdbc logs no failure to load the native library, in five JVMs (#241)")
	void twoLethenonsTogether_loadTheLibraryWithoutAFailure() throws Exception
	{
		// the cache holds the library already, as on every start after the first
		hosted(List.of(), "again", "warm");
		List<Integer> withFailures = new ArrayList<>();
		for (int run = 0; run < TOGETHER_RUNS; run++)
		{
			List<String> said = hosted(List.of(), "together", "together-" + run);
			assertWritten("together-" + run, said, "one.lethenon", "two.lethenon");
			if (!failures(said).isEmpty())
			{
				withFailures.add(run);
			}
		}

		assertEquals(List.of(), withFailures, "the runs that logged a failure, of " + TOGETHER_RUNS
			+ " with two lethenons at one moment");
		assertEquals(1, librariesInTheCache(), "one copy in the cache");
	}

	@Test
	@DisplayName("a library name the host set in org.sqlite.lib.name is back after lethenon's first connection (#241)")
	void aLibraryNameOfTheHost_isBackAfterLethenonsFirstConnection() throws Exception
	{
		List<String> said = hosted(List.of("-Dorg.sqlite.lib.name=" + HOST_NAME), "host-name",
			"host-name");

		assertWritten("host-name", said, "host.lethenon");
		assertTrue(said.contains("lib.name " + HOST_NAME), () -> "the host's name is back: " + said);
	}

	@Test
	@DisplayName("a host that set org.sqlite.lib.path keeps it: lethenon writes, claims nothing and leaves the cache empty (#241)")
	void aLibraryPathOfTheHost_isKeptAndTheCacheLeftAlone() throws Exception
	{
		Path hostLibraries = Files.createDirectories(directory.resolve("host-libraries"));
		List<String> said = hosted(List.of("-Dorg.sqlite.lib.path=" + hostLibraries), "host-path",
			"host-path");

		assertEquals(List.of(), failures(said), "sqlite-jdbc logged a failure to load its library");
		assertWritten("host-path", said, "host.lethenon");
		assertTrue(said.contains("lib.path " + hostLibraries), () -> "the host's path: " + said);
		assertTrue(said.contains("claimed null"), () -> "lethenon claimed nothing: " + said);
		assertEquals(0, librariesInTheCache(), "lethenon left the cache alone");
	}

	@Test
	@DisplayName("today a lethenon on an interrupted thread waits the full five seconds for a claim nobody finishes, and one on a thread that is not waits them and writes (#241)")
	void aStaleClaim_keepsAnInterruptedLethenonWaiting() throws Exception
	{
		List<String> said = hosted(List.of("-D" + SqliteNativeLibrary.CLAIMED_PROPERTY
			+ "=a lethenon that never finishes"), "stale-claim", "stale-claim");

		assertEquals(List.of(), failures(said), "sqlite-jdbc logged a failure to load its library");
		assertWritten("stale-claim", said, "patient.lethenon");
		assertTrue(said.contains("interrupt kept true"), () -> "the interrupt was kept: " + said);
		long interrupted = millis(said, "interrupted waited ");
		assertTrue(interrupted >= SqliteNativeLibrary.CLAIMANT_WAIT.toMillis() - 100,
			() -> "the interrupted lethenon waited the full time: " + said);
		long patient = millis(said, "patient waited ");
		assertTrue(patient >= SqliteNativeLibrary.CLAIMANT_WAIT.toMillis() - 100,
			() -> "the patient lethenon waited for the claim until the time ran out: " + said);
	}

	/** The milliseconds on the line the host said with the given start */
	private static long millis(final List<String> said, final String start)
	{
		String line = said.stream().filter(each -> each.startsWith(start)).findFirst()
			.orElseThrow(() -> new AssertionError("no line '" + start + "...': " + said));
		return Long.parseLong(line.substring(start.length()).split(" ")[0]);
	}

	/**
	 * Runs a scenario of {@link NativeLibraryHost} in a JVM of its own, with the user's cache and
	 * the temporary directory under this test's directory, and checks that the host could see what
	 * it reports: it ended, its capture of sqlite-jdbc's log works, and no SLF4J took the log
	 */
	private List<String> hosted(final List<String> options, final String scenario,
		final String run) throws IOException, InterruptedException
	{
		Path chains = Files.createDirectories(directory.resolve(run).resolve("chains"));
		Path temporary = Files.createDirectories(directory.resolve(run).resolve("tmp"));
		Path output = directory.resolve(run).resolve("output.txt");
		Path errors = directory.resolve(run).resolve("errors.txt");
		List<String> command = new ArrayList<>(
			List.of(ProcessHandle.current().info().command().orElseThrow(),
				"--enable-native-access=ALL-UNNAMED", "-Xmx256m", "-Djava.io.tmpdir=" + temporary));
		command.addAll(options);
		command.addAll(List.of("-cp", System.getProperty("java.class.path"),
			NativeLibraryHost.class.getName(), scenario, chains.toString()));
		ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(output.toFile())
			.redirectError(errors.toFile());
		builder.environment().put("XDG_CACHE_HOME", directory.resolve("cache").toString());
		Process host = builder.start();
		boolean ended;
		try
		{
			ended = host.waitFor(60, TimeUnit.SECONDS);
		}
		finally
		{
			if (host.isAlive())
			{
				host.destroyForcibly().waitFor();
			}
		}

		assertTrue(ended, "the host ended within a minute");
		List<String> said = Files.readAllLines(output);
		String errorOutput = Files.readString(errors);
		assertEquals(0, host.exitValue(), () -> "the host failed: " + said + " " + errorOutput);
		assertTrue(said.contains("done"), () -> "the host ran to its end: " + said);
		assertTrue(said.contains("capture works"),
			() -> "the host sees what sqlite-jdbc logs: " + said);
		assertTrue(said.contains("slf4j absent"), () -> "sqlite-jdbc logs through SLF4J, where "
			+ "the host does not listen, so a failure would go unseen: " + said);
		return said;
	}

	private static List<String> failures(final List<String> said)
	{
		return said.stream().filter(line -> line.startsWith("failure ")).toList();
	}

	/** Each chain is reported written, and its file is an SQLite database */
	private void assertWritten(final String run, final List<String> said, final String... chains)
		throws IOException
	{
		byte[] header = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
		for (String chain : chains)
		{
			assertTrue(said.contains("written " + chain), () -> chain + " reported written: " + said);
			Path file = directory.resolve(run).resolve("chains").resolve(chain);
			try (InputStream in = Files.newInputStream(file))
			{
				assertArrayEquals(header, in.readNBytes(header.length), file + " is a database");
			}
		}
	}

	/** The copies of the native library in the user's cache of this test */
	private long librariesInTheCache() throws IOException
	{
		if (!Files.isDirectory(directory.resolve("cache")))
		{
			return 0;
		}
		try (Stream<Path> files = Files.walk(directory.resolve("cache")))
		{
			return files.filter(Files::isRegularFile)
				.filter(file -> file.getFileName().toString().contains("sqlitejdbc")).count();
		}
	}
}
