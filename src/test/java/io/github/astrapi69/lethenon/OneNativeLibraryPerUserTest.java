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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * sqlite-jdbc's native library and the processes that are killed instead of ending (#241): every
 * writer here runs in a JVM of its own, opens a chain database, and is ended with {@code kill -9}
 * after its first write, the way a node that is killed, a crash test or a host that crashes ends
 */
class OneNativeLibraryPerUserTest
{

	private static final int WRITERS = 3;

	private static List<BlockBody> chain;

	@TempDir
	Path directory;

	@BeforeAll
	static void aChain()
	{
		chain = TestChains.extended(TestChains.chainWith(TestChains.MINER), 20, "native");
	}

	@Test
	@DisplayName("killed writers leave no copy in the temporary directory; the user's cache holds one, written once and reused (#241)")
	void killedWriters_shareOneCopyInTheCache() throws Exception
	{
		Path temporary = Files.createDirectories(directory.resolve("tmp"));
		Path cache = Files.createDirectories(directory.resolve("cache"));

		killAfterItsFirstWrite(0, temporary, cache);
		Path library = theOnlyLibraryIn(cache);
		FileTime written = Files.getLastModifiedTime(library);
		for (int writer = 1; writer < WRITERS; writer++)
		{
			killAfterItsFirstWrite(writer, temporary, cache);
		}

		assertEquals(0, librariesIn(temporary), "no copy is left in the temporary directory");
		assertEquals(library, theOnlyLibraryIn(cache), "one copy, in the same place");
		assertEquals(written, Files.getLastModifiedTime(library), "reused, not written again");
		assertTrue(library.startsWith(cache.resolve("lethenon").resolve("native")), library.toString());
	}

	private static Path theOnlyLibraryIn(final Path root) throws IOException
	{
		try (Stream<Path> files = Files.walk(root))
		{
			List<Path> libraries = files.filter(Files::isRegularFile)
				.filter(file -> file.getFileName().toString().contains("sqlitejdbc")).toList();
			assertEquals(1, libraries.size(), libraries.toString());
			return libraries.getFirst();
		}
	}

	private void killAfterItsFirstWrite(final int writer, final Path temporary, final Path cache)
		throws IOException, InterruptedException
	{
		Path fixture = CrashRuns.chainFixture(chain, directory.resolve("chain-" + writer));
		Path database = ChainCrashTest.prepared(directory.resolve("writer-" + writer),
			chain.subList(0, 1), List.of());
		CrashRuns.killAfter("done 2", 0, List.of("grow", database.toString(), fixture.toString()),
			Map.of("XDG_CACHE_HOME", cache.toString()), database.resolveSibling("errors.txt"),
			temporary);
	}

	/** The copies of sqlite-jdbc's native library under the directory, lock files not counted */
	private static long librariesIn(final Path root) throws IOException
	{
		try (Stream<Path> files = Files.walk(root))
		{
			return files.filter(Files::isRegularFile)
				.map(file -> file.getFileName().toString())
				.filter(name -> name.contains("sqlitejdbc") && !name.endsWith(".lck")).count();
		}
	}
}
