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
package io.github.astrapi69.lethenon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Writes a chain database as the chain file of lethenon 0.5.0 and earlier: the way back (#224).
 * <p>
 * 0.6.0 migrates such a file into a database on first use (ADR 0006), and 0.5.0 cannot read the
 * database. The file it was migrated from stays beside it as {@code <chain>.legacy}, but holds the
 * chain only as it was at that moment. The export writes the chain as it is now:
 * <ul>
 * <li>its blocks in block encoding version 2, the encoding the database keeps every block in and
 * the one 0.5.0 reads</li>
 * <li>its waiting transfers as {@code <file>.pending}, pending-file version 1
 * ({@link LegacyPendingFile}); with no transfer waiting there is no pending file, as with 0.5.0</li>
 * </ul>
 * Byte for byte what 0.5.0 writes for the same chain, measured against files the published 0.5.0
 * wrote ({@code ChainExportTest}).
 * <p>
 * It reads only a database, so that a chain file of 0.5.0 given by mistake is left as it is: read
 * through {@link ChainFile}, it would be migrated. It overwrites nothing. What it wrote is read back
 * and compared block by block and transfer by transfer, as the migration compares, and when it
 * differs, the files it wrote are removed again.
 */
public final class ChainExport
{

	/**
	 * What an export wrote
	 *
	 * @param blocks
	 *            the number of blocks
	 * @param waiting
	 *            the number of waiting transfers, 0 when no pending file was written
	 */
	public record Exported(int blocks, int waiting) {
	}

	private final Path database;

	/**
	 * An export of the chain database at the given path
	 *
	 * @param database
	 *            the chain database
	 */
	public ChainExport(final Path database)
	{
		this.database = Objects.requireNonNull(database);
	}

	/**
	 * Writes the chain as a chain file of 0.5.0, and its waiting transfers next to it
	 *
	 * @param target
	 *            the chain file to write; it and {@code <target>.pending} must not exist
	 * @return what was written
	 * @throws IllegalArgumentException
	 *             when there is no chain database at the path, or a file is in the way; nothing is
	 *             read, written or changed then
	 * @throws IOException
	 *             when the database cannot be read, a file cannot be written, or what was written
	 *             differs from the database; the files the export wrote are removed then
	 */
	public Exported to(final Path target) throws IOException
	{
		requireADatabase();
		Path pending = target.resolveSibling(target.getFileName() + ".pending");
		requireFree(target);
		requireFree(pending);
		ChainDatabase chain = new ChainDatabase(database);
		List<BlockBody> blocks = chain.blocks();
		List<SignedTransaction> waiting = chain.waiting();
		boolean pendingWritten = false;
		boolean chainWritten = false;
		try
		{
			if (!waiting.isEmpty())
			{
				writeNew(pending, LegacyPendingFile.encode(waiting));
				pendingWritten = true;
			}
			writeNew(target, CanonicalEncoding.encodeChain(blocks));
			chainWritten = true;
			requireTheSame(target, pending, blocks, waiting);
		}
		catch (IOException | RuntimeException failed)
		{
			if (chainWritten)
			{
				Files.deleteIfExists(target);
			}
			if (pendingWritten)
			{
				Files.deleteIfExists(pending);
			}
			throw failed;
		}
		return new Exported(blocks.size(), waiting.size());
	}

	private void requireADatabase() throws IOException
	{
		if (!Files.exists(database))
		{
			throw new IllegalArgumentException("there is no chain at " + database);
		}
		if (!ChainDatabase.isDatabase(database))
		{
			throw new IllegalArgumentException(database + " is not a chain database of lethenon "
				+ "0.6.0 or later, so there is nothing to export: lethenon 0.5.0 reads it as it is. "
				+ "It was left as it is");
		}
	}

	private static void requireFree(final Path file)
	{
		if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
		{
			throw new IllegalArgumentException(file + " exists already, and the export overwrites "
				+ "nothing");
		}
	}

	/** Written beside the target first, so that the target appears only complete */
	private static void writeNew(final Path target, final byte[] content) throws IOException
	{
		Path writing = target.resolveSibling(target.getFileName() + ".writing");
		Files.write(writing, content);
		Files.move(writing, target);
	}

	private static void requireTheSame(final Path target, final Path pending,
		final List<BlockBody> blocks, final List<SignedTransaction> waiting) throws IOException
	{
		List<BlockBody> written = CanonicalEncoding.readChain(Files.readAllBytes(target));
		if (written.size() != blocks.size())
		{
			throw differs(target, "it holds " + written.size() + " blocks and the database "
				+ blocks.size());
		}
		for (int height = 0; height < blocks.size(); height++)
		{
			if (!Arrays.equals(CanonicalEncoding.encodeChain(List.of(blocks.get(height))),
				CanonicalEncoding.encodeChain(List.of(written.get(height)))))
			{
				throw differs(target, "block " + height + " is not the same");
			}
		}
		List<SignedTransaction> writtenWaiting = waiting.isEmpty()
			? List.of()
			: LegacyPendingFile.read(pending);
		if (writtenWaiting.size() != waiting.size())
		{
			throw differs(pending, "it holds " + writtenWaiting.size()
				+ " waiting transfers and the database " + waiting.size());
		}
		for (int position = 0; position < waiting.size(); position++)
		{
			if (!Arrays.equals(CanonicalEncoding.encode(waiting.get(position)),
				CanonicalEncoding.encode(writtenWaiting.get(position))))
			{
				throw differs(pending, "waiting transfer " + position + " is not the same");
			}
		}
	}

	private static IOException differs(final Path file, final String what)
	{
		return new IOException("the exported file " + file + " differs from the database it was "
			+ "written from: " + what + "; the files the export wrote were removed");
	}
}
