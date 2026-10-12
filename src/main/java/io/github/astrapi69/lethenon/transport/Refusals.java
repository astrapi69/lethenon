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

import java.util.ArrayDeque;
import java.util.List;

/**
 * Why a node's connections were refused: the most recent {@link #KEPT} of them, oldest first, and
 * how many there were in all (#183).
 * <p>
 * A seed node runs for months. An unbounded list grew with every refused connection and kept every
 * refused remote address, which is personal data, for the node's whole run. The count is kept
 * apart from the lines, because the status line counts refusals and must not stop counting when
 * the lines stop growing.
 */
final class Refusals
{

	/** How many refusals are kept: about 10 KB of lines, whatever the uptime */
	static final int KEPT = 100;

	private final ArrayDeque<String> recent = new ArrayDeque<>();

	private long count;

	/**
	 * Records a refusal, dropping the oldest kept one when there are {@link #KEPT} already
	 *
	 * @param refusal
	 *            why a connection was refused
	 */
	synchronized void add(final String refusal)
	{
		count++;
		recent.addLast(refusal);
		if (recent.size() > KEPT)
		{
			recent.removeFirst();
		}
	}

	/**
	 * The kept refusals
	 *
	 * @return a snapshot, oldest first, at most {@link #KEPT}
	 */
	synchronized List<String> recent()
	{
		return List.copyOf(recent);
	}

	/**
	 * How many refusals there were in all, kept or not
	 *
	 * @return the count
	 */
	synchronized long count()
	{
		return count;
	}
}
