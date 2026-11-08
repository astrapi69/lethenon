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

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What the release archive carries must not be what Dependabot flags (#231).
 * <p>
 * jackson and commons-beanutils are not this project's dependencies: they arrive through
 * mystic-crypt, from {@code jobj-copy} and {@code jobj-compare}, and the command line distribution
 * ships whatever the runtime class path holds. Up to mystic-crypt 13.6 they arrived at 2.16.0-rc1 and
 * 1.9.4 and a dependency constraint raised them; since 13.7 the libraries bring 2.22.4 and 1.11.0
 * themselves, and the constraint is gone. Dependabot cannot open a fix for a dependency nobody
 * declares, so the floor is held here, against the class path the tests run on, which holds the same
 * resolved versions as the runtime one: a library that brought a lower version back fails it.
 */
class RuntimeDependenciesTest
{

	/**
	 * A pre-release on the class path: a milestone, a release candidate, an alpha, a beta, a preview,
	 * an early access build or a snapshot, as the jar's name says it
	 */
	private static final Pattern PRE_RELEASE = Pattern
		.compile(".*-\\d[^/]*[-.](M\\d+|[Rr][Cc]\\d*|alpha\\d*|beta\\d*|preview\\d*|ea|SNAPSHOT)\\.jar");

	/**
	 * A release ships no pre-release. The family's comparator-extensions 2 brings the milestone
	 * commons-collections4 4.5.0-M2, through silly-collection 28.2, which mystic-crypt 13.9 and
	 * crypt-data 13.2 use, and the command line distribution of 0.8.0 shipped it; a dependency
	 * constraint raises it to 4.6.0 since 0.8.1
	 */
	@Test
	@DisplayName("no pre-release on the class path, commons-collections4 4.5.0-M2 among them")
	void theClassPath_holdsNoPreRelease()
	{
		assertEquals(List.of(), preReleases());
	}

	/**
	 * What the pattern takes for a pre-release, read from the jar's name. jaxb-api 2.4.0-b180830.0359
	 * is a numbered build of a 2.4.0 that was never released, and crypt-data 13.2 brings it
	 * (crypt-data#133); the pattern does not see that form (#301). Guava's placeholder and the -jre
	 * flavour of a Guava release are releases
	 *
	 * @param jar
	 *            the name of a jar on a class path
	 * @param preRelease
	 *            whether the pattern reads it as a pre-release
	 */
	@ParameterizedTest(name = "{0}: pre-release {1}")
	@CsvSource({ "commons-collections4-4.5.0-M2.jar, true", "jackson-core-2.16.0-rc1.jar, true",
			"example-3.0.0-beta.jar, true", "example-2.0.0-alpha2.jar, true",
			"lethenon-0.9.0-SNAPSHOT.jar, true", "jaxb-api-2.4.0-b180830.0359.jar, false",
			"jaxb-api-2.2.12-b141001.1542.jar, false", "commons-collections4-4.6.0.jar, false",
			"jaxb-api-2.3.1.jar, false", "guava-33.7.2-jre.jar, false",
			"listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar, false",
			"bcprov-jdk18on-1.86.jar, false", "commons-beanutils-1.11.0.jar, false" })
	void aJarName_isAPreRelease_asThePatternReadsIt(final String jar, final boolean preRelease)
	{
		assertEquals(preRelease, PRE_RELEASE.matcher(jar).matches(), jar);
	}

	private static List<String> preReleases()
	{
		return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
			.map(entry -> new File(entry).getName())
			.filter(name -> PRE_RELEASE.matcher(name).matches())
			.sorted()
			.toList();
	}

	@ParameterizedTest(name = "{0} is at least {1}")
	@CsvSource({ "jackson-core, 2.22.4", "jackson-databind, 2.22.4", "jackson-annotations, 2.22",
			"commons-beanutils, 1.11.0" })
	void aTransitiveDependencyIsAtLeastItsPatchedRelease(final String artifact,
		final String floor)
	{
		List<String> versions = versionsOnTheClassPath(artifact);

		assertEquals(1, versions.size(),
			"exactly one " + artifact + " on the class path, found " + versions);
		assertTrue(compare(versions.getFirst(), floor) >= 0,
			artifact + " " + versions.getFirst() + " is below " + floor);
	}

	private static List<String> versionsOnTheClassPath(final String artifact)
	{
		Pattern jar = Pattern.compile(Pattern.quote(artifact) + "-(\\d[^/]*)\\.jar");
		return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
			.map(entry -> jar.matcher(new File(entry).getName()))
			.filter(Matcher::matches)
			.map(matcher -> matcher.group(1))
			.toList();
	}

	/**
	 * Compares the numeric parts of two versions; a qualifier such as {@code -rc1} counts as below
	 * the release it precedes
	 */
	private static int compare(final String version, final String floor)
	{
		String[] left = version.split("-", 2);
		int[] have = numbers(left[0]);
		int[] want = numbers(floor);
		for (int index = 0; index < Math.max(have.length, want.length); index++)
		{
			int difference = Integer.compare(index < have.length ? have[index] : 0,
				index < want.length ? want[index] : 0);
			if (difference != 0)
			{
				return difference;
			}
		}
		return left.length > 1 ? -1 : 0;
	}

	private static int[] numbers(final String version)
	{
		return Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
	}
}
