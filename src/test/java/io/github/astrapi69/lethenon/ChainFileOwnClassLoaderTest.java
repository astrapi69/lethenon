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

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.JDBC;
import org.junit.jupiter.api.io.TempDir;

/**
 * A host that loads lethenon in a class loader of its own, as mystic-crypt-ui's plugin manager
 * does (#223): lethenon and sqlite-jdbc are visible to that loader, not to the application's
 * class loader the JDBC driver registry was set up from
 */
class ChainFileOwnClassLoaderTest
{

	@Test
	@DisplayName("a chain written from a class loader of its own opens, and reads back")
	void aChainWrittenFromItsOwnClassLoader_opens(@TempDir final Path directory) throws Exception
	{
		// the registry loads its drivers once, from whichever loader asks first: the
		// application's, as in a host where lethenon is not on the application class path
		DriverManager.getDrivers();
		Path chain = directory.resolve("own.lethenon");
		try (URLClassLoader own = new URLClassLoader(classPath(),
			ClassLoader.getPlatformClassLoader()))
		{
			writeNoTransfers(own, chain);
		}

		assertTrue(Files.isRegularFile(chain), "the chain database was written");
		assertEquals(List.of(), new ChainFile(chain).readPending(),
			"and holds the empty list of waiting transfers it was given");
	}

	@Test
	@DisplayName("today a lethenon loaded again, in a class loader of its own, logs that its sqlite-jdbc cannot load the native library, and writes all the same")
	void aLethenonLoadedAgain_logsThatItCannotLoadTheNativeLibrary(@TempDir final Path directory)
		throws Exception
	{
		Path first = directory.resolve("first.lethenon");
		Path again = directory.resolve("again.lethenon");
		List<String> failures;
		try (
			URLClassLoader once = new URLClassLoader(classPath(),
				ClassLoader.getPlatformClassLoader());
			URLClassLoader loadedAgain = new URLClassLoader(classPath(),
				ClassLoader.getPlatformClassLoader()))
		{
			failures = nativeLibraryFailuresWhile(() -> {
				writeNoTransfers(once, first);
				writeNoTransfers(loadedAgain, again);
			});
		}

		assertFalse(failures.isEmpty(), "sqlite-jdbc logged no failure to load its library");
		assertEquals(List.of(), new ChainFile(again).readPending(),
			"the lethenon loaded again wrote its chain");
	}

	@Test
	@DisplayName("today another program's sqlite-jdbc, in a class loader of its own, logs after lethenon that it cannot load the native library, and connects all the same")
	void anotherSqliteJdbc_afterLethenon_logsThatItCannotLoadTheNativeLibrary(
		@TempDir final Path directory) throws Exception
	{
		URL sqliteJdbc = JDBC.class.getProtectionDomain().getCodeSource().getLocation();
		AtomicInteger answer = new AtomicInteger();
		List<String> failures;
		try (
			URLClassLoader lethenon = new URLClassLoader(classPath(),
				ClassLoader.getPlatformClassLoader());
			URLClassLoader otherProgram = new URLClassLoader(new URL[] { sqliteJdbc },
				ClassLoader.getPlatformClassLoader()))
		{
			failures = nativeLibraryFailuresWhile(() -> {
				writeNoTransfers(lethenon, directory.resolve("lethenon.lethenon"));
				answer.set(selectOne(otherProgram, directory.resolve("other.db")));
			});
		}

		assertFalse(failures.isEmpty(), "sqlite-jdbc logged no failure to load its library");
		assertEquals(1, answer.get(), "the other program's connection answered");
	}

	/** Writes an empty list of waiting transfers through the ChainFile of the given loader */
	private static void writeNoTransfers(final ClassLoader loader, final Path chain)
		throws Exception
	{
		Thread current = Thread.currentThread();
		ClassLoader context = current.getContextClassLoader();
		current.setContextClassLoader(ClassLoader.getPlatformClassLoader());
		try
		{
			Class<?> chainFile = Class.forName(ChainFile.class.getName(), true, loader);
			Object file = chainFile.getConstructor(Path.class).newInstance(chain);
			chainFile.getMethod("writePending", List.class).invoke(file, new ArrayList<>());
		}
		finally
		{
			current.setContextClassLoader(context);
		}
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
	 * The failures to load the native library that sqlite-jdbc logs while the action runs: it logs
	 * through java.util.logging, where no SLF4J is on its class path, as here
	 */
	private static List<String> nativeLibraryFailuresWhile(final Action action) throws Exception
	{
		Logger sqlite = Logger.getLogger("org.sqlite");
		List<String> failures = new CopyOnWriteArrayList<>();
		Handler listening = new Handler()
		{
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
		};
		sqlite.addHandler(listening);
		try
		{
			action.run();
		}
		finally
		{
			sqlite.removeHandler(listening);
		}
		return failures;
	}

	/** What a test does while it listens to sqlite-jdbc's log */
	private interface Action
	{
		/**
		 * Runs the action
		 *
		 * @throws Exception
		 *             whatever the action throws
		 */
		void run() throws Exception;
	}

	/** This test's class path, lethenon's classes and sqlite-jdbc among it */
	private static URL[] classPath() throws MalformedURLException
	{
		List<URL> urls = new ArrayList<>();
		for (String entry : System.getProperty("java.class.path").split(File.pathSeparator))
		{
			urls.add(new File(entry).toURI().toURL());
		}
		return urls.toArray(URL[]::new);
	}
}
