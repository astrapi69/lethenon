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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Starts {@link ChainWriterProcess} in a JVM of its own and kills it with SIGKILL, which is what
 * {@code kill -9} sends and what {@link ProcessHandle#destroyForcibly()} sends on Linux and macOS
 * (#202).
 * <p>
 * How many runs a test makes is the system property {@code lethenon.crashRuns},
 * {@value #DEFAULT_RUNS} by default so that the everyday build stays fast; the gate before a
 * release runs a thousand
 * ({@code ./gradlew test --tests '*ChainCrashTest' --tests '*PowerCutTest' -PcrashRuns=1000}).
 */
final class CrashRuns
{

	/** Runs per test in the everyday build */
	static final int DEFAULT_RUNS = 25;

	/** The longest a run waits after the writer is ready before it kills it */
	static final int LONGEST_DELAY_MILLIS = 400;

	private CrashRuns()
	{
	}

	/**
	 * How many runs to make
	 *
	 * @return {@code lethenon.crashRuns}, or {@value #DEFAULT_RUNS}
	 */
	static int runs()
	{
		return Integer.getInteger("lethenon.crashRuns", DEFAULT_RUNS);
	}

	/**
	 * Starts a writer, lets it write for a random time, kills it
	 *
	 * @param arguments
	 *            the writer's arguments
	 * @param environment
	 *            added to the writer's environment
	 * @param random
	 *            chooses the moment
	 * @param errors
	 *            where the writer's standard error goes
	 * @param nativeLibraries
	 *            where the writer unpacks sqlite-jdbc's native library
	 * @return what the writer said before it died
	 * @throws IOException
	 *             if the writer cannot be started
	 * @throws InterruptedException
	 *             if the test is interrupted
	 */
	static Killed killAtRandom(final List<String> arguments, final Map<String, String> environment,
		final Random random, final Path errors, final Path nativeLibraries)
		throws IOException, InterruptedException
	{
		return killAfter("ready", random.nextInt(LONGEST_DELAY_MILLIS), arguments, environment,
			errors, nativeLibraries);
	}

	/**
	 * Starts a writer, waits until it has said the given line, lets it write for the given time,
	 * kills it
	 *
	 * @param line
	 *            the line to wait for, such as {@code ready}
	 * @param delayMillis
	 *            how long the writer goes on after it
	 * @param arguments
	 *            the writer's arguments
	 * @param environment
	 *            added to the writer's environment
	 * @param errors
	 *            where the writer's standard error goes
	 * @param nativeLibraries
	 *            where the writer unpacks sqlite-jdbc's native library ({@code org.sqlite.tmpdir}):
	 *            a writer killed with SIGKILL never deletes its copy, 1.1 MB each, and in
	 *            {@code java.io.tmpdir} one day of crash runs left 11,401 of them, which filled a
	 *            quota-bound {@code /tmp} (#240)
	 * @return what the writer said before it died
	 * @throws IOException
	 *             if the writer cannot be started
	 * @throws InterruptedException
	 *             if the test is interrupted
	 */
	static Killed killAfter(final String line, final int delayMillis, final List<String> arguments,
		final Map<String, String> environment, final Path errors, final Path nativeLibraries)
		throws IOException, InterruptedException
	{
		List<String> command = new ArrayList<>(List.of(
			ProcessHandle.current().info().command().orElseThrow(),
			"--enable-native-access=ALL-UNNAMED", "-Xmx128m",
			"-Dorg.sqlite.tmpdir=" + nativeLibraries.toAbsolutePath(), "-cp",
			System.getProperty("java.class.path"), ChainWriterProcess.class.getName()));
		command.addAll(arguments);
		ProcessBuilder builder = new ProcessBuilder(command)
			.redirectError(errors.toFile());
		builder.environment().putAll(environment);
		Process writer = builder.start();
		List<String> lines = Collections.synchronizedList(new ArrayList<>());
		Thread reader = Thread.ofVirtual().start(() -> readLines(writer, lines));
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		while (!lines.contains(line) && writer.isAlive() && System.nanoTime() < until)
		{
			Thread.sleep(1);
		}
		Thread.sleep(delayMillis);
		boolean exitedByItself = !writer.isAlive();
		kill(writer);
		reader.join();
		return new Killed(List.copyOf(lines), exitedByItself, writer.exitValue());
	}

	/**
	 * Kills a process with SIGKILL and waits until it is gone; what it wrote to its standard output
	 * before stays readable to the end
	 * <p>
	 * Through its {@link ProcessHandle}, which only sends the signal. {@link Process#destroyForcibly()}
	 * also closes the process's streams right after the signal (JDK 25,
	 * {@code java.lang.ProcessImpl.destroy}), which drops whatever the reader had not read yet: the
	 * test then saw a writer a step behind where it was, and called a whole chain broken (#240).
	 *
	 * @param process
	 *            the process
	 * @throws InterruptedException
	 *             if the test is interrupted
	 */
	static void kill(final Process process) throws InterruptedException
	{
		process.toHandle().destroyForcibly();
		process.waitFor();
	}

	/**
	 * Reads a process's standard output to its end
	 *
	 * @param writer
	 *            the process
	 * @param lines
	 *            where its lines go, and a last line {@code output ended: <reason>} when the output
	 *            could not be read to its end
	 */
	static void readLines(final Process writer, final List<String> lines)
	{
		try (BufferedReader out = new BufferedReader(
			new InputStreamReader(writer.getInputStream(), StandardCharsets.UTF_8)))
		{
			for (String line = out.readLine(); line != null; line = out.readLine())
			{
				lines.add(line);
			}
		}
		catch (IOException ended)
		{
			lines.add("output ended: " + ended.getMessage());
		}
	}

	/**
	 * Writes a chain where the writer reads it from
	 *
	 * @param chain
	 *            the chain
	 * @param where
	 *            the file
	 * @return the file
	 * @throws IOException
	 *             if it cannot be written
	 */
	static Path chainFixture(final List<BlockBody> chain, final Path where) throws IOException
	{
		return Files.write(where, CanonicalEncoding.encodeChain(chain));
	}

	/**
	 * What a killed writer said
	 *
	 * @param lines
	 *            its standard output
	 * @param exitedByItself
	 *            whether it had ended before the kill, so the kill hit nothing
	 * @param exitValue
	 *            its exit value
	 */
	record Killed(List<String> lines, boolean exitedByItself, int exitValue)
	{

		/** @return the last step it began, 0 for none */
		int lastBegun()
		{
			return last("begin ");
		}

		/** @return the last step it finished, 0 for none */
		int lastDone()
		{
			return last("done ");
		}

		/** @return whether the kill came between the start and the end of a write */
		boolean insideAWrite()
		{
			return lastBegun() > lastDone();
		}

		/** @return whether its output could not be read to its end, so its last lines are unknown */
		boolean outputCutShort()
		{
			return !lines.isEmpty() && lines.getLast().startsWith("output ended: ");
		}

		private int last(final String prefix)
		{
			int step = 0;
			for (String line : lines)
			{
				if (line.startsWith(prefix))
				{
					step = Integer.parseInt(line.substring(prefix.length()));
				}
			}
			return step;
		}
	}
}
