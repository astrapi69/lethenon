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
package io.github.astrapi69.lethenon.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

import picocli.CommandLine.IVersionProvider;

/**
 * What {@code lethenon --version} prints: the version of the build, which the build writes into
 * {@code version.properties} next to this class from {@code projectVersion} (#193). Until 0.6.0 it
 * was the fixed string "lethenon 0.1", whatever the build.
 */
final class BuildVersion implements IVersionProvider
{

	/** The resource the build fills, beside this class */
	static final String RESOURCE = "version.properties";

	@Override
	public String[] getVersion()
	{
		return new String[] { "lethenon " + version() };
	}

	/**
	 * The version of this build
	 *
	 * @return the project version, for example 0.6.0
	 * @throws IllegalStateException
	 *             when the build did not write the resource, which only a broken build can cause
	 */
	static String version()
	{
		try (InputStream stream = BuildVersion.class.getResourceAsStream(RESOURCE))
		{
			if (stream == null)
			{
				throw new IllegalStateException(RESOURCE + " is missing beside "
					+ BuildVersion.class.getName() + ": the build did not write it");
			}
			Properties properties = new Properties();
			properties.load(stream);
			return properties.getProperty("version");
		}
		catch (IOException unreadable)
		{
			throw new UncheckedIOException(unreadable);
		}
	}
}
