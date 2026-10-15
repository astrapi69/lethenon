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
import java.util.List;

/**
 * The chain files under {@code src/test/resources/chain/}, described in the README there
 */
public final class ChainFixtures
{

	private ChainFixtures()
	{
	}

	/**
	 * The test network's genesis block, as seed node 1 serves it (#197)
	 *
	 * @return the chain of that one block
	 */
	public static List<BlockBody> testNetworkGenesis()
	{
		return read("lethenon-test-2-genesis.lethenon");
	}

	private static List<BlockBody> read(final String name)
	{
		try (InputStream stream = ChainFixtures.class.getResourceAsStream("/chain/" + name))
		{
			if (stream == null)
			{
				throw new IllegalStateException(name + " is not on the test class path");
			}
			return CanonicalEncoding.readChain(stream.readAllBytes());
		}
		catch (IOException unreadable)
		{
			throw new UncheckedIOException(unreadable);
		}
	}
}
