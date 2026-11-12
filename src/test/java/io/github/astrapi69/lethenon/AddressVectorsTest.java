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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The test vectors of ADR 0007 (#309), read from the one file they live in,
 * {@code address-vectors.txt} next to this class, which section 5 of the specification names: every
 * valid input reads as its written form, every invalid one is refused with its message.
 */
class AddressVectorsTest
{

	private static final String FILE = "address-vectors.txt";

	/**
	 * One line of the file
	 *
	 * @param valid
	 *            whether the input is read
	 * @param chain
	 *            the chain identifier it is read for
	 * @param published
	 *            true for a published address, false for the address of an account
	 * @param input
	 *            the input, its escapes decoded
	 * @param expected
	 *            the written form, or the message of the refusal
	 * @param note
	 *            what the vector shows
	 */
	record Vector(boolean valid, String chain, boolean published, String input, String expected,
		String note)
	{

		@Override
		public String toString()
		{
			return (valid ? "valid" : "invalid") + ", " + (published ? "published" : "account")
				+ " on " + chain + ": " + note;
		}
	}

	static List<Vector> vectors() throws IOException
	{
		List<Vector> vectors = new ArrayList<>();
		try (InputStream in = AddressVectorsTest.class.getResourceAsStream(FILE))
		{
			assertNotNull(in, FILE + " is next to the test");
			BufferedReader reader = new BufferedReader(
				new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line = reader.readLine(); line != null; line = reader.readLine())
			{
				if (line.isBlank() || line.startsWith("#"))
				{
					continue;
				}
				String[] fields = line.split(" \\| ", -1);
				assertEquals(6, fields.length, line);
				vectors.add(new Vector(outcome(fields[0]), chain(fields[1]), kind(fields[2]),
					decoded(fields[3]), fields[4], fields[5]));
			}
		}
		return vectors;
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("vectors")
	@DisplayName("every vector of the file reads as its written form or is refused with its message")
	void everyVector_isReadAsTheFileSays(final Vector vector)
	{
		if (vector.valid())
		{
			String written = vector.published()
				? AddressText.of(AddressText.published(vector.input(), vector.chain()),
					vector.chain())
				: AddressText.ofAccount(AddressText.account(vector.input(), vector.chain()),
					vector.chain());
			assertEquals(vector.expected(), written);
			return;
		}
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> read(vector));
		assertEquals(vector.expected(), refused.getMessage());
	}

	@Test
	@DisplayName("the file covers both chains, both kinds, valid and invalid, and the example of the record")
	void theFile_coversWhatTheRecordAsks() throws IOException
	{
		List<Vector> vectors = vectors();
		Set<String> covered = vectors.stream()
			.map(vector -> vector.valid() + "/" + vector.chain() + "/" + vector.published())
			.collect(Collectors.toSet());

		for (boolean valid : List.of(true, false))
		{
			for (String chain : List.of(Chain.IDENTIFIER, Chain.TEST_IDENTIFIER))
			{
				for (boolean published : List.of(true, false))
				{
					assertTrue(covered.contains(valid + "/" + chain + "/" + published),
						"a vector " + valid + " " + chain + " " + published);
				}
			}
		}
		assertTrue(vectors.stream().anyMatch(vector -> vector.expected().endsWith(":916000f6")));
		assertTrue(vectors.stream().anyMatch(vector -> vector.expected().endsWith(":0fc3d2e1")));
	}

	private static void read(final Vector vector)
	{
		if (vector.published())
		{
			AddressText.published(vector.input(), vector.chain());
		}
		else
		{
			AddressText.account(vector.input(), vector.chain());
		}
	}

	private static boolean outcome(final String field)
	{
		return switch (field)
		{
			case "valid" -> true;
			case "invalid" -> false;
			default -> throw new IllegalArgumentException("an outcome is valid or invalid: " + field);
		};
	}

	private static String chain(final String field)
	{
		return switch (field)
		{
			case "main" -> Chain.IDENTIFIER;
			case "test" -> Chain.TEST_IDENTIFIER;
			default -> throw new IllegalArgumentException("a chain is main or test: " + field);
		};
	}

	private static boolean kind(final String field)
	{
		return switch (field)
		{
			case "published" -> true;
			case "account" -> false;
			default -> throw new IllegalArgumentException("a kind is published or account: " + field);
		};
	}

	/** The input with its escapes \t, \r, \n and \\uXXXX turned into the characters they name */
	private static String decoded(final String field)
	{
		StringBuilder text = new StringBuilder();
		for (int index = 0; index < field.length(); index++)
		{
			char character = field.charAt(index);
			if (character != '\\')
			{
				text.append(character);
				continue;
			}
			char escape = field.charAt(++index);
			switch (escape)
			{
				case 't' -> text.append('\t');
				case 'r' -> text.append('\r');
				case 'n' -> text.append('\n');
				case 'u' ->
				{
					text.appendCodePoint(Integer.parseInt(field.substring(index + 1, index + 5), 16));
					index += 4;
				}
				default -> throw new IllegalArgumentException("an unknown escape \\" + escape);
			}
		}
		return text.toString();
	}
}
