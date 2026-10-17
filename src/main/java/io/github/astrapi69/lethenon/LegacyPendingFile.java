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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The pending file of lethenon 0.5.0 and earlier, {@code <chain>.pending}: the transfers waiting for
 * a block, next to the chain file. One byte for its version, {@link ChainFile#PENDING_VERSION}, the
 * number of transfers as a four-byte integer, then each transfer as its length and its canonical
 * encoding. The migration reads it (#202), the export writes it (#224); both in this one place, so
 * that the two cannot read and write different formats.
 */
final class LegacyPendingFile
{

	private LegacyPendingFile()
	{
	}

	/**
	 * The transfers a pending file holds
	 *
	 * @param file
	 *            the pending file
	 * @return its transfers, in their order
	 * @throws IOException
	 *             when it cannot be read
	 * @throws IllegalArgumentException
	 *             when it is of another version, or a transfer cannot be decoded
	 */
	static List<SignedTransaction> read(final Path file) throws IOException
	{
		ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file));
		byte version = buffer.get();
		if (version != ChainFile.PENDING_VERSION)
		{
			throw new IllegalArgumentException(file + " is pending-file version " + version
				+ " and this build reads version " + ChainFile.PENDING_VERSION);
		}
		int count = buffer.getInt();
		List<SignedTransaction> transfers = new ArrayList<>(count);
		for (int index = 0; index < count; index++)
		{
			byte[] encoded = new byte[buffer.getInt()];
			buffer.get(encoded);
			transfers.add(CanonicalEncoding.readSignedTransaction(encoded));
		}
		return transfers;
	}

	/**
	 * The bytes of a pending file holding the given transfers
	 *
	 * @param transfers
	 *            the transfers, in their order
	 * @return what 0.5.0 writes for them
	 */
	static byte[] encode(final List<SignedTransaction> transfers)
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(ChainFile.PENDING_VERSION);
		bytes.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(transfers.size()).array());
		for (SignedTransaction transfer : transfers)
		{
			byte[] encoded = CanonicalEncoding.encode(transfer);
			bytes.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
			bytes.writeBytes(encoded);
		}
		return bytes.toByteArray();
	}
}
