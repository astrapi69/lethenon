package io.github.astrapi69.lethenon.tmp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Throwaway: fails on purpose so the artifact step of #237 runs once. Never merged. */
class SaysWhyTest
{
	@Test
	void failsWithItsSummary()
	{
		System.out.println("power cut while growing: 125 disks, 40 killed inside a write, 3 broken");
		assertEquals(0, 3, "power cut while growing: 125 disks, 40 killed inside a write, 3 broken");
	}
}
