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
package io.github.astrapi69.lethenon.transport;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A wait that runs out says what the time was spent on (#212): on the maintainer's machine a wait of
 * ten seconds failed after 56 minutes, and nothing the run kept could tell a hang from a machine that
 * slept.
 */
class NetworksTest
{

	@Test
	@DisplayName("a wait that runs out names what it waited for, how long it waited, and how far the wall clock moved meanwhile")
	void aWaitThatRunsOut_namesItsLimitAndTheWallClock()
	{
		AssertionError ranOut = assertThrows(AssertionError.class,
			() -> Networks.await("nothing at all", () -> false, Duration.ofMillis(200)));

		String message = ranOut.getMessage();
		assertTrue(message.startsWith("waited 200 ms for nothing at all"), message);
		assertTrue(message.contains("the wall clock moved "), message);
	}

	@Test
	@DisplayName("a wait that runs out lists the threads and where each one stands, so a hang names itself")
	void aWaitThatRunsOut_listsTheThreads() throws InterruptedException
	{
		CountDownLatch release = new CountDownLatch(1);
		Thread parked = Thread.ofPlatform().name("networks-test-parked").start(() -> {
			try
			{
				release.await();
			}
			catch (InterruptedException interrupted)
			{
				Thread.currentThread().interrupt();
			}
		});
		try
		{
			AssertionError ranOut = assertThrows(AssertionError.class,
				() -> Networks.await("the parked thread", () -> false, Duration.ofMillis(100)));

			String message = ranOut.getMessage();
			assertTrue(message.contains("\"networks-test-parked\" WAITING"), message);
			assertTrue(message.contains("java.util.concurrent.CountDownLatch.await"), message);
		}
		finally
		{
			release.countDown();
			parked.join();
		}
	}
}
