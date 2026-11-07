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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
