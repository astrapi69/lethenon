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

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Class loaders of their own for lethenon, as a host such as mystic-crypt-ui's plugin manager makes
 * them: the test class path, lethenon's classes and sqlite-jdbc among it, under the platform class
 * loader, so nothing of the application class loader is shared
 */
final class OwnClassLoaders
{

	private OwnClassLoaders()
	{
	}

	/**
	 * A class loader of its own over this JVM's class path
	 *
	 * @return the class loader
	 * @throws MalformedURLException
	 *             if an entry of the class path is no URL
	 */
	static URLClassLoader overTheClassPath() throws MalformedURLException
	{
		return new URLClassLoader(classPath(), ClassLoader.getPlatformClassLoader());
	}

	/**
	 * Writes an empty list of waiting transfers through the ChainFile of the given loader
	 *
	 * @param loader
	 *            the class loader whose lethenon writes
	 * @param chain
	 *            the chain database
	 * @throws ReflectiveOperationException
	 *             if the loader has no ChainFile, or writing fails
	 */
	static void writeNoTransfers(final ClassLoader loader, final Path chain)
		throws ReflectiveOperationException
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

	/**
	 * This JVM's class path, lethenon's classes and sqlite-jdbc among it
	 *
	 * @return its entries as URLs
	 * @throws MalformedURLException
	 *             if an entry is no URL
	 */
	static URL[] classPath() throws MalformedURLException
	{
		List<URL> urls = new ArrayList<>();
		for (String entry : System.getProperty("java.class.path").split(File.pathSeparator))
		{
			urls.add(new File(entry).toURI().toURL());
		}
		return urls.toArray(URL[]::new);
	}
}
