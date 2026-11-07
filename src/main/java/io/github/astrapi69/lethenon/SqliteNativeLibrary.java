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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.sqlite.JDBC;
import org.sqlite.SQLiteJDBCLoader;
import org.sqlite.util.LibraryLoaderUtil;

/**
 * sqlite-jdbc's native library, kept once per user and sqlite-jdbc version instead of once per
 * process (#241).
 * <p>
 * Left alone, sqlite-jdbc extracts its library at every JVM start under a new name into its
 * temporary directory and deletes it only when the JVM ends normally: every process ended with
 * {@code kill -9}, a crash, or a power cut leaves 1.1 MB behind, and its clean-up keeps those
 * because their lock file stays as well. Here the library is put once into
 * {@code $XDG_CACHE_HOME/lethenon/native/<version>/<os>/}, or {@code ~/.cache/...} without
 * {@code XDG_CACHE_HOME}, and sqlite-jdbc is told through {@code org.sqlite.lib.path} and
 * {@code org.sqlite.lib.name} to load it from there, so it extracts nothing. A file already there
 * with the same bytes is kept as it is; a new one is written under a temporary name and moved
 * into place, so two processes starting at once do not race.
 * <p>
 * The two properties are set only while sqlite-jdbc loads the library, and taken back then. Every
 * sqlite-jdbc in the JVM reads them, and the JDK loads a native library into one class loader
 * only: a sqlite-jdbc in another class loader would fail on the file, log that as an error and
 * extract a copy of its own all the same. So only the first lethenon of a JVM points its
 * sqlite-jdbc at the cache, and it claims that in one step ({@code lethenon.sqlite.lib.claimed}).
 * A lethenon in another class loader, a plugin loaded again or one starting at the same moment,
 * waits until the first is done ({@code lethenon.sqlite.lib.prepared}) and leaves its sqlite-jdbc
 * to extract a copy as before. A sqlite-jdbc that is not lethenon's and loads its library in the
 * milliseconds the properties are set still reads them.
 * <p>
 * A host that set {@code org.sqlite.lib.path} itself keeps its choice, and a name it set in
 * {@code org.sqlite.lib.name} is put back after the load. Where the directory cannot be written,
 * nothing is set and sqlite-jdbc extracts as before.
 */
final class SqliteNativeLibrary
{

	/** The property sqlite-jdbc reads the directory of its native library from */
	static final String PATH_PROPERTY = "org.sqlite.lib.path";

	/** The property sqlite-jdbc reads the file name of its native library from */
	static final String NAME_PROPERTY = "org.sqlite.lib.name";

	/**
	 * Set in one step, JVM-wide, by the first lethenon of the JVM that prepares the library, to the
	 * cache's directory: every later one, in whatever class loader, finds it set and leaves the
	 * cache to the first
	 */
	static final String CLAIMED_PROPERTY = "lethenon.sqlite.lib.claimed";

	/**
	 * Set by the lethenon that claimed the library once it is done and the two properties are taken
	 * back, whatever its sqlite-jdbc loaded: the cached file, a copy of its own, or nothing
	 */
	static final String PREPARED_PROPERTY = "lethenon.sqlite.lib.prepared";

	/** The longest a lethenon waits for the one that claimed the library */
	static final Duration CLAIMANT_WAIT = Duration.ofSeconds(5);

	private static boolean prepared;

	private SqliteNativeLibrary()
	{
	}

	/**
	 * Puts the native library into the user's cache and has sqlite-jdbc load it from there, once
	 * per JVM; before the first connection
	 *
	 * @throws SQLException
	 *             if sqlite-jdbc, pointed at the cache, can load its native library neither from
	 *             there nor from anywhere else it looks
	 */
	static synchronized void prepare() throws SQLException
	{
		if (prepared)
		{
			return;
		}
		prepared = true;
		if (System.getProperty(PATH_PROPERTY) != null && System.getProperty(CLAIMED_PROPERTY) == null)
		{
			return;
		}
		String resourceFolder = LibraryLoaderUtil.getNativeLibResourcePath();
		String name = LibraryLoaderUtil.getNativeLibName();
		Path directory = cacheHome().resolve("lethenon").resolve("native")
			.resolve(SQLiteJDBCLoader.getVersion())
			.resolve(resourceFolder.replaceFirst("^/org/sqlite/native/", ""));
		if (System.getProperties().putIfAbsent(CLAIMED_PROPERTY, directory.toString()) != null)
		{
			awaitTheClaimant();
			return;
		}
		try
		{
			cachedAndLoaded(resourceFolder, name, directory);
		}
		finally
		{
			System.setProperty(PREPARED_PROPERTY, directory.toString());
		}
	}

	/** Puts the library into the cache and has sqlite-jdbc load it from there */
	private static void cachedAndLoaded(final String resourceFolder, final String name,
		final Path directory) throws SQLException
	{
		try (InputStream library = JDBC.class.getResourceAsStream(resourceFolder + "/" + name))
		{
			if (library == null)
			{
				return;
			}
			placed(library.readAllBytes(), directory.resolve(name));
		}
		catch (IOException | UncheckedIOException | SecurityException notWritable)
		{
			// the cache cannot hold the library: sqlite-jdbc extracts it as it always did, which
			// costs one copy per killed process and nothing else
			return;
		}
		loadedFrom(directory, name);
	}

	/**
	 * Has sqlite-jdbc load the library from the directory, with the two properties set only while
	 * it does: a path a host sets meanwhile stays, and a name it had set comes back
	 */
	private static void loadedFrom(final Path directory, final String name) throws SQLException
	{
		Properties properties = System.getProperties();
		String own = directory.toString();
		if (properties.putIfAbsent(PATH_PROPERTY, own) != null)
		{
			return;
		}
		Object hostName = properties.put(NAME_PROPERTY, name);
		try
		{
			SQLiteJDBCLoader.initialize();
		}
		catch (Exception notLoaded)
		{
			// SQLiteJDBCLoader.initialize declares Exception; what it throws names the paths tried
			throw new SQLException("sqlite-jdbc could not load its native library, " + own
				+ " tried first: " + notLoaded.getMessage(), notLoaded);
		}
		finally
		{
			properties.remove(PATH_PROPERTY, own);
			if (hostName == null)
			{
				properties.remove(NAME_PROPERTY, name);
			}
			else
			{
				properties.replace(NAME_PROPERTY, name, hostName);
			}
		}
	}

	/**
	 * Waits, {@link #CLAIMANT_WAIT} at most, until the lethenon that claimed the library is done,
	 * so that this one's sqlite-jdbc does not read the two properties while they point at a file
	 * that is loaded in another class loader; past that it goes on, at the cost of one logged error
	 */
	private static void awaitTheClaimant()
	{
		long giveUp = System.nanoTime() + CLAIMANT_WAIT.toNanos();
		while (System.getProperty(PREPARED_PROPERTY) == null && System.nanoTime() - giveUp < 0)
		{
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		}
	}

	/**
	 * The user's cache directory: {@code XDG_CACHE_HOME} when it names an absolute path, else
	 * {@code .cache} in the home directory
	 */
	static Path cacheHome()
	{
		String xdg = System.getenv("XDG_CACHE_HOME");
		if (xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute())
		{
			return Path.of(xdg);
		}
		return Path.of(System.getProperty("user.home"), ".cache");
	}

	private static void placed(final byte[] bytes, final Path target) throws IOException
	{
		if (Files.isRegularFile(target) && Files.size(target) == bytes.length
			&& Arrays.equals(Files.readAllBytes(target), bytes))
		{
			return;
		}
		Files.createDirectories(target.getParent());
		Path written = Files.createTempFile(target.getParent(), target.getFileName().toString(),
			".part");
		try
		{
			Files.write(written, bytes);
			Files.move(written, target, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		}
		finally
		{
			Files.deleteIfExists(written);
		}
	}
}
