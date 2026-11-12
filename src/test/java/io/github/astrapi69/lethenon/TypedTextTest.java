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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The spaces typed text may carry where they mean nothing, one list for the words of a seed phrase
 * and for an address (ADR 0007, #309). The list replaces the character class the phrase reader had
 * since #286; these tests are what shows that the phrase reader reads exactly what it read before.
 */
class TypedTextTest
{

	/** What SeedPhrase split at before the list, without its comma */
	private static final Pattern BEFORE = Pattern.compile("[\\s\\p{Z}]");

	static Stream<Integer> spaces()
	{
		return TypedText.SPACES.stream();
	}

	@Test
	@DisplayName("the list holds exactly the code points [\\s\\p{Z}] matches on this runtime, over every code point")
	void theList_isWhatThePhraseReaderReadBefore()
	{
		List<String> differences = new ArrayList<>();
		for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++)
		{
			boolean before = BEFORE.matcher(Character.toString(codePoint)).matches();
			if (before != TypedText.isSpace(codePoint))
			{
				differences.add(String.format("U+%04X before %b, now %b", codePoint, before,
					!before));
			}
		}
		assertTrue(differences.isEmpty(), "a JDK whose Unicode tables differ, or a list that "
			+ "lost or gained a code point: " + differences);
		assertEquals(24, TypedText.SPACES.size());
	}

	@ParameterizedTest(name = "code point {0} (decimal) separates two words of a phrase")
	@MethodSource("spaces")
	@DisplayName("every code point of the list separates two words, as it did before")
	void everyCodePointOfTheList_separatesTwoWords(final int codePoint)
	{
		assertEquals(List.of("abandon", "ability"),
			SeedPhrase.parse("abandon" + Character.toString(codePoint) + "ability"));
	}

	@ParameterizedTest(name = "code point {0} (decimal) separates nothing")
	@ValueSource(ints = { 0x200B, 0xFEFF, 0x202E })
	@DisplayName("an invisible or a direction control is no space, in a phrase as before")
	void anInvisibleCharacter_separatesNothing(final int codePoint)
	{
		String word = "abandon" + Character.toString(codePoint) + "ability";

		assertEquals(List.of(word), SeedPhrase.parse(word));
	}
}
