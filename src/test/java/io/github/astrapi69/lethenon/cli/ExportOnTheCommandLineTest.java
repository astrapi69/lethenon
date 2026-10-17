/**
 * The MIT License
 *
 * Copyright (C) 2026 Asterios Raptis
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.astrapi69.lethenon.ChainFile;

/**
 * {@code lethenon export}, the way back to the chain file of 0.5.0 (#224), as a person runs it: on
 * the database a chain file of the published 0.5.0 was migrated to (the fixtures'
 * src/test/resources/chain/README.md).
 */
class ExportOnTheCommandLineTest extends AbstractCliTest
{

	@TempDir
	Path directory;

	@Test
	@DisplayName("export writes the chain and its waiting transfers as 0.5.0 wrote them, and says so (#224)")
	void export_writesWhat050Wrote() throws Exception
	{
		Path database = directory.resolve("chain.lethenon");
		Files.write(database, fixture("written-by-0.5.0.lethenon"));
		Files.write(directory.resolve("chain.lethenon.pending"),
			fixture("written-by-0.5.0.lethenon.pending"));
		new ChainFile(database).read();
		Path back = directory.resolve("back.lethenon");

		int exit = run("", "export", "--chain", database.toString(), "--to", back.toString());

		assertEquals(0, exit, err);
		assertTrue(out.contains("wrote 4 blocks to " + back), out);
		assertTrue(out.contains("3 waiting transfers to " + back + ".pending"), out);
		assertTrue(out.contains("lethenon 0.5.0 reads"), out);
		assertArrayEquals(fixture("written-by-0.5.0.lethenon"), Files.readAllBytes(back));
		assertArrayEquals(fixture("written-by-0.5.0.lethenon.pending"),
			Files.readAllBytes(directory.resolve("back.lethenon.pending")));
	}

	@Test
	@DisplayName("export refuses a chain file of 0.5.0 with the reason, and leaves it as it is (#224)")
	void export_refusesAChainFileOf050() throws Exception
	{
		Path old = directory.resolve("old.lethenon");
		Files.write(old, fixture("written-by-0.5.0.lethenon"));

		int exit = run("", "export", "--chain", old.toString(), "--to",
			directory.resolve("back.lethenon").toString());

		assertEquals(1, exit, out);
		assertTrue(err.contains("is not a chain database"), err);
		assertArrayEquals(fixture("written-by-0.5.0.lethenon"), Files.readAllBytes(old));
		assertFalse(Files.exists(directory.resolve("back.lethenon")));
	}

	private static byte[] fixture(final String name) throws IOException
	{
		try (InputStream stream = ExportOnTheCommandLineTest.class
			.getResourceAsStream("/chain/" + name))
		{
			return stream.readAllBytes();
		}
	}
}
