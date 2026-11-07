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
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.sqlite.JDBC;

/**
 * A host of lethenon in a JVM of its own, for {@link NativeLibraryInClassLoadersTest}: it loads
 * lethenon, or sqlite-jdbc alone, into class loaders of their own, the way a plugin host does, and
 * says on standard output what sqlite-jdbc logged and what each of them wrote (#241). A JVM of its
 * own, because what is tested is JVM-wide: the system properties lethenon points sqlite-jdbc at its
 * cache with, and the class loader the JDK binds the native library to.
 * <p>
 * Arguments: the scenario ({@code again}, {@code other}, {@code together}, {@code host-name},
 * {@code host-path} or {@code stale-claim}) and the directory to write the chains into. Lines:
 * {@code capture works}, {@code slf4j absent}, {@code written <chain>}, {@code answered <n>},
 * {@code lib.name <value>}, {@code lib.path <value>}, {@code claimed <value>},
 * {@code interrupted waited <ms> cpu <ms>}, {@code interrupt kept <boolean>},
 * {@code patient waited <ms>}, one {@code failure <message>} for every failure to load the library
 * that sqlite-jdbc logged, {@code mapped <file>} for every copy of the library the JVM mapped, and
 * {@code done}
 */
public final class NativeLibraryHost
{

	/** sqlite-jdbc's loader logs through java.util.logging under its class name, without SLF4J */
	private static final String LOADER_LOGGER = "org.sqlite.SQLiteJDBCLoader";

	/** What the check of the capture logs */
	private static final String CAPTURE_CHECK = "capture check";

	private NativeLibraryHost()
	{
	}

	/**
	 * Runs one scenario and reports it
	 *
	 * @param arguments
	 *            the scenario and the directory for its chains
	 * @throws Exception
	 *             if the scenario fails; the test reads that from the exit code and standard error
	 */
	public static void main(final String[] arguments) throws Exception
	{
		Path directory = Path.of(arguments[1]);
		List<String> failures = new CopyOnWriteArrayList<>();
		Logger sqlite = Logger.getLogger("org.sqlite");
		sqlite.addHandler(new FailuresToLoad(failures));
		say(captured(failures) ? "capture works" : "capture broken");
		switch (arguments[0])
		{
			case "again" -> again(directory);
			case "other" -> other(directory);
			case "together" -> together(directory);
			case "host-name" -> hostName(directory);
			case "host-path" -> hostPath(directory);
			case "stale-claim" -> staleClaim(directory);
			default -> throw new IllegalArgumentException("no such scenario: " + arguments[0]);
		}
		failures.forEach(failure -> say("failure " + failure));
		mapped();
		say("done");
	}

	/** lethenon in one class loader, then lethenon again in another, as a plugin loaded again */
	private static void again(final Path directory) throws Exception
	{
		try (URLClassLoader once = OwnClassLoaders.overTheClassPath();
			URLClassLoader loadedAgain = OwnClassLoaders.overTheClassPath())
		{
			slf4j(once);
			written(once, directory.resolve("first.lethenon"));
			written(loadedAgain, directory.resolve("again.lethenon"));
		}
	}

	/** lethenon in one class loader, then another program's sqlite-jdbc in a loader of its own */
	private static void other(final Path directory) throws Exception
	{
		URL sqliteJdbc = JDBC.class.getProtectionDomain().getCodeSource().getLocation();
		try (URLClassLoader lethenon = OwnClassLoaders.overTheClassPath();
			URLClassLoader otherProgram = new URLClassLoader(new URL[] { sqliteJdbc },
				ClassLoader.getPlatformClassLoader()))
		{
			slf4j(otherProgram);
			written(lethenon, directory.resolve("lethenon.lethenon"));
			say("answered " + selectOne(otherProgram, directory.resolve("other.db")));
		}
	}

	/** Two lethenons in class loaders of their own that open their first chain at one moment */
	private static void together(final Path directory) throws Exception
	{
		try (URLClassLoader first = OwnClassLoaders.overTheClassPath();
			URLClassLoader second = OwnClassLoaders.overTheClassPath())
		{
			slf4j(first);
			CyclicBarrier start = new CyclicBarrier(2);
			List<Throwable> thrown = new CopyOnWriteArrayList<>();
			Thread one = Thread.ofPlatform().start(
				() -> writtenAfter(start, first, directory.resolve("one.lethenon"), thrown));
			Thread two = Thread.ofPlatform().start(
				() -> writtenAfter(start, second, directory.resolve("two.lethenon"), thrown));
			one.join();
			two.join();
			if (!thrown.isEmpty())
			{
				IllegalStateException failed = new IllegalStateException("a writer failed");
				thrown.forEach(failed::addSuppressed);
				throw failed;
			}
		}
	}

	/** lethenon in a class loader of its own, in a JVM started with a name of the host's own */
	private static void hostName(final Path directory) throws Exception
	{
		try (URLClassLoader own = OwnClassLoaders.overTheClassPath())
		{
			slf4j(own);
			written(own, directory.resolve("host.lethenon"));
		}
		say("lib.name " + System.getProperty("org.sqlite.lib.name"));
	}

	/** lethenon in a class loader of its own, in a JVM started with a library path of the host's */
	private static void hostPath(final Path directory) throws Exception
	{
		try (URLClassLoader own = OwnClassLoaders.overTheClassPath())
		{
			slf4j(own);
			written(own, directory.resolve("host.lethenon"));
		}
		say("lib.path " + System.getProperty("org.sqlite.lib.path"));
		say("claimed " + System.getProperty("lethenon.sqlite.lib.claimed"));
	}

	/**
	 * A JVM started with a claim that nobody finishes, as one left by a lethenon that hangs: first
	 * a lethenon on an interrupted thread prepares the library, then one on a thread that is not
	 */
	private static void staleClaim(final Path directory) throws Exception
	{
		try (URLClassLoader interrupted = OwnClassLoaders.overTheClassPath();
			URLClassLoader patient = OwnClassLoaders.overTheClassPath())
		{
			slf4j(interrupted);
			Thread.currentThread().interrupt();
			long start = System.nanoTime();
			long cpuStart = cpuNanos();
			prepare(interrupted);
			say("interrupted waited " + millisSince(start) + " cpu " + (cpuNanos() - cpuStart)
				/ 1_000_000);
			say("interrupt kept " + Thread.interrupted());
			start = System.nanoTime();
			written(patient, directory.resolve("patient.lethenon"));
			say("patient waited " + millisSince(start));
		}
	}

	/** Calls the loader's SqliteNativeLibrary.prepare(), which is not public */
	private static void prepare(final ClassLoader loader) throws ReflectiveOperationException
	{
		Method prepare = Class.forName(SqliteNativeLibrary.class.getName(), true, loader)
			.getDeclaredMethod("prepare");
		prepare.setAccessible(true);
		prepare.invoke(null);
	}

	private static long cpuNanos()
	{
		return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
	}

	private static long millisSince(final long start)
	{
		return (System.nanoTime() - start) / 1_000_000;
	}

	private static void writtenAfter(final CyclicBarrier start, final ClassLoader loader,
		final Path chain, final List<Throwable> thrown)
	{
		try
		{
			start.await();
			written(loader, chain);
		}
		catch (Exception failed)
		{
			thrown.add(failed);
		}
	}

	private static void written(final ClassLoader loader, final Path chain)
		throws ReflectiveOperationException
	{
		OwnClassLoaders.writeNoTransfers(loader, chain);
		say("written " + chain.getFileName());
	}

	/** Asks {@code select 1} of a database through the sqlite-jdbc driver of the given loader */
	private static int selectOne(final ClassLoader loader, final Path database)
		throws ReflectiveOperationException, SQLException
	{
		Driver driver = (Driver)Class.forName(JDBC.class.getName(), true, loader)
			.getDeclaredConstructor().newInstance();
		try (Connection connection = driver.connect("jdbc:sqlite:" + database, new Properties());
			Statement statement = connection.createStatement();
			ResultSet selected = statement.executeQuery("select 1"))
		{
			return selected.getInt(1);
		}
	}

	/**
	 * Whether SLF4J is visible to the loader's sqlite-jdbc: then it logs there, where this host does
	 * not listen, and a failure to load the library would go unseen
	 */
	private static void slf4j(final ClassLoader loader)
	{
		try
		{
			Class.forName("org.slf4j.Logger", false, loader);
			say("slf4j present");
		}
		catch (ClassNotFoundException absent)
		{
			say("slf4j absent");
		}
	}

	/** Whether a failure logged where sqlite-jdbc's loader logs reaches the list */
	private static boolean captured(final List<String> failures)
	{
		Logger.getLogger(LOADER_LOGGER).log(Level.SEVERE, CAPTURE_CHECK,
			new UnsatisfiedLinkError(CAPTURE_CHECK));
		return failures.removeIf(failure -> failure.startsWith(CAPTURE_CHECK));
	}

	/** The copies of sqlite-jdbc's native library this JVM mapped, where /proc tells */
	private static void mapped() throws IOException
	{
		Path maps = Path.of("/proc/self/maps");
		if (!Files.isReadable(maps))
		{
			return;
		}
		Files.readAllLines(maps).stream().filter(line -> line.contains("sqlitejdbc"))
			.map(line -> line.substring(line.indexOf('/'))).distinct()
			.forEach(file -> say("mapped " + file));
	}

	private static void say(final String line)
	{
		System.out.println(line);
	}

	/** Collects every record that carries an UnsatisfiedLinkError, as a failure to load */
	private static final class FailuresToLoad extends Handler
	{

		private final List<String> failures;

		FailuresToLoad(final List<String> failures)
		{
			this.failures = failures;
		}

		@Override
		public void publish(final LogRecord logged)
		{
			if (logged.getThrown() instanceof UnsatisfiedLinkError refused)
			{
				failures.add(logged.getMessage() + ": " + refused.getMessage());
			}
		}

		@Override
		public void flush()
		{
		}

		@Override
		public void close()
		{
		}
	}
}
