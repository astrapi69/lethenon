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
import java.util.Arrays;

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
 * A host that set {@code org.sqlite.lib.path} itself keeps its choice. Where the directory cannot
 * be written, nothing is set and sqlite-jdbc extracts as before.
 */
final class SqliteNativeLibrary
{

	/** The property sqlite-jdbc reads the directory of its native library from */
	static final String PATH_PROPERTY = "org.sqlite.lib.path";

	/** The property sqlite-jdbc reads the file name of its native library from */
	static final String NAME_PROPERTY = "org.sqlite.lib.name";

	private static boolean prepared;

	private SqliteNativeLibrary()
	{
	}

	/**
	 * Puts the native library into the user's cache and points sqlite-jdbc at it, once per JVM;
	 * before the first connection
	 */
	static synchronized void prepare()
	{
		if (prepared)
		{
			return;
		}
		prepared = true;
		if (System.getProperty(PATH_PROPERTY) != null)
		{
			return;
		}
		String resourceFolder = LibraryLoaderUtil.getNativeLibResourcePath();
		String name = LibraryLoaderUtil.getNativeLibName();
		Path directory = cacheHome().resolve("lethenon").resolve("native")
			.resolve(SQLiteJDBCLoader.getVersion())
			.resolve(resourceFolder.replaceFirst("^/org/sqlite/native/", ""));
		try (InputStream library = JDBC.class.getResourceAsStream(resourceFolder + "/" + name))
		{
			if (library == null)
			{
				return;
			}
			placed(library.readAllBytes(), directory.resolve(name));
			System.setProperty(PATH_PROPERTY, directory.toString());
			System.setProperty(NAME_PROPERTY, name);
		}
		catch (IOException | UncheckedIOException | SecurityException notWritable)
		{
			// the cache cannot hold the library: sqlite-jdbc extracts it as it always did, which
			// costs one copy per killed process and nothing else
			return;
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
