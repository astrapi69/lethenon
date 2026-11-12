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

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The spaces a person's typed or pasted text may carry where they mean nothing: between the words
 * of a seed phrase (#286) and anywhere in an address (ADR 0007, #309). Both readers take them from
 * this one list.
 * <p>
 * The list is enumerated code point by code point rather than written as a character class such
 * as {@code [\s\p{Z}]}, so that a later Unicode version of the Java runtime does not change what a
 * reader accepts. On OpenJDK 25 it is exactly what {@code [\s\p{Z}]} matches, which is what the
 * phrase reader split at before; {@code TypedTextTest} checks that over every code point.
 * <p>
 * One list, not one function: between words a space separates, inside an address it is dropped,
 * so the two readers use it differently.
 */
final class TypedText
{

	/**
	 * The code points: tab, line feed, line tabulation, form feed and carriage return; space and
	 * no-break space; the Ogham space mark; the spaces from en quad to hair space; the line and the
	 * paragraph separator; the narrow no-break, the medium mathematical and the ideographic space
	 */
	static final List<Integer> SPACES = List.of(0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020,
		0x00A0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008,
		0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000);

	private TypedText()
	{
	}

	/**
	 * Whether a code point is one of the spaces
	 *
	 * @param codePoint
	 *            the code point
	 * @return true for a code point of {@link #SPACES}
	 */
	static boolean isSpace(final int codePoint)
	{
		return SPACES.contains(codePoint);
	}

	/**
	 * The spaces as the inside of a regular expression's character class, one escape per code
	 * point, for a reader that splits at them
	 *
	 * @return the escapes, without the brackets
	 */
	static String asCharacterClass()
	{
		return SPACES.stream().map(codePoint -> String.format(Locale.ROOT, "\\x{%X}", codePoint))
			.collect(Collectors.joining());
	}
}
