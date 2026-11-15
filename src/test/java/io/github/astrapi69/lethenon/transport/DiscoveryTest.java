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

import static io.github.astrapi69.lethenon.transport.Networks.await;
import static io.github.astrapi69.lethenon.transport.Networks.testGenesis;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.astrapi69.lethenon.Bytes;

/**
 * Discovery does not dial an address whose connection is still in its handshake: between the
 * open socket and the added peer, the address is neither dialling nor a peer, and dialling it
 * again opened a second connection to the same node (#117). A dial this node refused for its own
 * limit leaves the address in the book (#126); one that failed keeps it too, and it waits (#296).
 */
class DiscoveryTest
{

	/** Documentation addresses (RFC 5737): a local one never enters the book (#194) */
	private static final PeerAddress IN_HANDSHAKE = new PeerAddress("198.51.100.1", 18481);

	private static final PeerAddress NEW = new PeerAddress("198.51.100.1", 18482);

	private static final PeerAddress ANOTHER = new PeerAddress("198.51.100.1", 18483);

	private static final PeerAddress A_THIRD = new PeerAddress("198.51.100.1", 18484);

	@Test
	@DisplayName("an address in its handshake is not dialled again, a new one is")
	void anAddressInItsHandshake_isNotDialledAgain() throws Exception
	{
		List<PeerAddress> dialled = new CopyOnWriteArrayList<>();
		Discovery.Dialer dialer = new Discovery.Dialer()
		{
			@Override
			public int outgoing()
			{
				return 0;
			}

			@Override
			public int maximum()
			{
				return Node.MAXIMUM_OUTGOING;
			}

			@Override
			public void dial(final PeerAddress address)
			{
				dialled.add(address);
			}

			/**
			 * As Node does: a dialled address is in its handshake from the moment its socket is
			 * open, which is when dial returns
			 */
			@Override
			public Set<PeerAddress> handshaking()
			{
				Set<PeerAddress> busy = new HashSet<>(dialled);
				busy.add(IN_HANDSHAKE);
				return busy;
			}
		};
		ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
		try (ServerSocket server = new ServerSocket(0);
			Socket ours = new Socket("127.0.0.1", server.getLocalPort());
			Socket theirs = server.accept())
		{
			Discovery discovery = new Discovery(List.of(), new Refusals(), dialer,
				threads);
			Peer peer = peerOver(ours);
			peer.expectPeers();

			discovery.learn(peer, new PeerList(List.of(IN_HANDSHAKE, NEW)));

			threads.shutdown();
			threads.awaitTermination(5, TimeUnit.SECONDS);
			assertEquals(List.of(NEW), dialled);
		}
		finally
		{
			threads.shutdownNow();
		}
	}

	/** How a dial ends, and whether the address is still heard of afterwards */
	record Ending(String name, IOException thrown, boolean keptInTheBook)
	{
		@Override
		public String toString()
		{
			return name;
		}
	}

	static Stream<Ending> endings()
	{
		return Stream.of(
			new Ending("refused for this node's own limit: the address is kept",
				new NoRoom("this node has 12 outgoing connections, its limit (ADR 0003)"), true),
			new Ending("refused for the anonymity zone's limit: the address is kept",
				new NoRoom("the anonymity zone has 2 anonymity connection(s), its limit (ADR 0004)"),
				true),
			new Ending("the address could not be reached: it is kept, and waits (#296)",
				new IOException("Connection refused"), true));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("endings")
	void aDialThatThrows_keepsTheAddress(final Ending ending) throws Exception
	{
		Discovery.Dialer dialer = new Discovery.Dialer()
		{
			@Override
			public int outgoing()
			{
				return 0;
			}

			@Override
			public int maximum()
			{
				return Node.MAXIMUM_OUTGOING;
			}

			@Override
			public void dial(final PeerAddress address) throws IOException
			{
				throw ending.thrown();
			}

			@Override
			public Set<PeerAddress> handshaking()
			{
				return Set.of();
			}
		};
		Refusals refusals = new Refusals();
		ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
		try (ServerSocket server = new ServerSocket(0);
			Socket ours = new Socket("127.0.0.1", server.getLocalPort());
			Socket theirs = server.accept())
		{
			Discovery discovery = new Discovery(List.of(), refusals, dialer, threads);
			Peer peer = peerOver(ours);
			peer.expectPeers();

			discovery.learn(peer, new PeerList(List.of(NEW)));

			threads.shutdown();
			threads.awaitTermination(5, TimeUnit.SECONDS);
			assertEquals(ending.keptInTheBook(), discovery.heardOf().contains(NEW),
				"heard of afterwards: " + discovery.heardOf());
			assertTrue(refusals.recent().stream().anyMatch(reason -> reason.startsWith(NEW.toString())
				&& reason.contains(ending.thrown().getMessage())), refusals.recent().toString());
		}
		finally
		{
			threads.shutdownNow();
		}
	}

	/**
	 * A fill that runs while a dial holds its slot, the dial still counted as dialling as well,
	 * sees the node full; the fill at the end of that dial sees the true count and takes the slot
	 * (#278: newcomers stuck at eleven outgoing connections with four addresses heard of). The
	 * order is forced: the first dial counts its slot as Node.connect does, the fill of the PEERS
	 * answer returns, and a second fill runs while the dial has not returned yet, as the PEERS
	 * answer of another connection would
	 */
	@Test
	@DisplayName("a fill while a dial holds its slot stops early, and the dial fills the slot once it returns")
	void aFillWhileADialHoldsItsSlot_isFollowedByOneThatTakesTheSlot() throws Exception
	{
		AtomicInteger outgoing = new AtomicInteger(Node.MAXIMUM_OUTGOING - 2);
		List<PeerAddress> dialled = new CopyOnWriteArrayList<>();
		CountDownLatch slotCounted = new CountDownLatch(1);
		CountDownLatch learnReturned = new CountDownLatch(1);
		Discovery[] discovery = new Discovery[1];
		Discovery.Dialer dialer = new Discovery.Dialer()
		{
			@Override
			public int outgoing()
			{
				return outgoing.get();
			}

			@Override
			public int maximum()
			{
				return Node.MAXIMUM_OUTGOING;
			}

			/** As Node.connect does: the slot is counted first, the socket opened after */
			@Override
			public void dial(final PeerAddress address)
			{
				outgoing.incrementAndGet();
				dialled.add(address);
				if (dialled.size() == 1)
				{
					slotCounted.countDown();
					awaitLatch(learnReturned);
					discovery[0].fill();
				}
			}

			@Override
			public Set<PeerAddress> handshaking()
			{
				return new HashSet<>(dialled);
			}
		};
		FirstDialHoldsTheFill threads = new FirstDialHoldsTheFill(slotCounted);
		try (ServerSocket server = new ServerSocket(0);
			Socket ours = new Socket("127.0.0.1", server.getLocalPort());
			Socket theirs = server.accept())
		{
			discovery[0] = new Discovery(List.of(), new Refusals(), dialer, threads);
			Peer peer = peerOver(ours);
			peer.expectPeers();

			discovery[0].learn(peer, new PeerList(List.of(NEW, ANOTHER, A_THIRD)));
			learnReturned.countDown();
			threads.awaitFinished();

			assertEquals(Node.MAXIMUM_OUTGOING, outgoing.get(),
				() -> "outgoing connections, with " + dialled + " dialled");
			assertEquals(2, dialled.size(), () -> "dialled " + dialled);
		}
	}

	/**
	 * A retry of an address that waited out its pause ends without a handshake, in Node's order
	 * (Node.serve): the address leaves handshaking in the inner finally, and only the outer finally
	 * records the failure through unreachable. A fill that runs between the two, as the PEERS
	 * answer of another peer would, does not take the address again: the retry set its last try
	 * when it started, and it waits from then (#296). It dialled the address a third time before
	 */
	@Test
	@DisplayName("a retry whose handshake has just ended is not dialled again before its failure is recorded")
	void aRetryWhoseHandshakeHasJustEnded_isNotDialledAgain_beforeItsFailureIsRecorded()
		throws Exception
	{
		ManualClock clock = new ManualClock();
		List<PeerAddress> dialled = new CopyOnWriteArrayList<>();
		Set<PeerAddress> handshaking = ConcurrentHashMap.newKeySet();
		Discovery.Dialer dialer = handshakingDialer(dialled, handshaking, () -> 0, address -> {
		});
		try (ServerSocket server = new ServerSocket(0);
			Socket ours = new Socket("127.0.0.1", server.getLocalPort());
			Socket theirs = server.accept())
		{
			Discovery discovery = new Discovery(List.of(), new Refusals(), dialer,
				new OnTheCallingThread(), clock);
			Peer peer = peerOver(ours);
			peer.expectPeers();
			discovery.learn(peer, new PeerList(List.of(NEW)));
			handshaking.remove(NEW);
			discovery.unreachable(NEW);
			clock.advance(PeerBook.FIRST_PAUSE);
			discovery.fill();
			assertEquals(List.of(NEW, NEW), dialled, "the retry after the pause");

			handshaking.remove(NEW);
			discovery.fill();

			assertEquals(List.of(NEW, NEW), dialled,
				"a fill between the end of the retry's handshake and its recorded failure");
			assertEquals(List.of(NEW), discovery.waiting(), "waiting before the failure is recorded");
			discovery.unreachable(NEW);
			assertEquals(List.of(NEW), discovery.waiting());
		}
	}

	/**
	 * A retry this node had no room for, because a slot was taken between the fill and the dial,
	 * was not an attempt on the address (#126): it gives back the last try it set, and the address
	 * is dialled at once when there is room, not a pause later (#296)
	 */
	@Test
	@DisplayName("a retry this node had no room for keeps the address's last try, and it is dialled at once")
	void aRetryThisNodeHadNoRoomFor_keepsTheLastTry_andIsDialledAtOnce() throws Exception
	{
		ManualClock clock = new ManualClock();
		List<PeerAddress> dialled = new CopyOnWriteArrayList<>();
		Set<PeerAddress> handshaking = ConcurrentHashMap.newKeySet();
		AtomicInteger dials = new AtomicInteger();
		AtomicBoolean full = new AtomicBoolean();
		Discovery.Dialer dialer = handshakingDialer(dialled, handshaking,
			() -> full.get() ? Node.MAXIMUM_OUTGOING : 0, address -> {
				if (dials.incrementAndGet() == 2)
				{
					full.set(true);
					throw new NoRoom("this node has 12 outgoing connections, its limit (ADR 0003)");
				}
			});
		try (ServerSocket server = new ServerSocket(0);
			Socket ours = new Socket("127.0.0.1", server.getLocalPort());
			Socket theirs = server.accept())
		{
			Discovery discovery = new Discovery(List.of(), new Refusals(), dialer,
				new OnTheCallingThread(), clock);
			Peer peer = peerOver(ours);
			peer.expectPeers();
			discovery.learn(peer, new PeerList(List.of(NEW)));
			handshaking.remove(NEW);
			discovery.unreachable(NEW);
			clock.advance(PeerBook.FIRST_PAUSE);
			discovery.fill();
			assertEquals(List.of(NEW), dialled, "the retry this node had no room for");

			full.set(false);
			discovery.fill();

			assertEquals(List.of(NEW, NEW), dialled, "the next fill, with room again");
		}
	}

	/** What a dial does before it counts as open, or how it is refused */
	interface BeforeTheSocketOpens
	{
		void run(PeerAddress address) throws IOException;
	}

	/**
	 * As Node.connect: an address that is not refused is in its handshake once the socket is open,
	 * which is when dial returns; the test ends the handshake by removing it
	 */
	private static Discovery.Dialer handshakingDialer(final List<PeerAddress> dialled,
		final Set<PeerAddress> handshaking, final IntSupplier outgoing,
		final BeforeTheSocketOpens before)
	{
		return new Discovery.Dialer()
		{
			@Override
			public int outgoing()
			{
				return outgoing.getAsInt();
			}

			@Override
			public int maximum()
			{
				return Node.MAXIMUM_OUTGOING;
			}

			@Override
			public void dial(final PeerAddress address) throws IOException
			{
				before.run(address);
				dialled.add(address);
				handshaking.add(address);
			}

			@Override
			public Set<PeerAddress> handshaking()
			{
				return Set.copyOf(handshaking);
			}
		};
	}

	/**
	 * Two fills at once, as the PEERS answers of two peers would run: the second fill is held in
	 * its selection, after it copied dialing, until the first fill has taken the address or waits
	 * on a lock the second holds. The dials are held, so a taken address stays on dialing. Taking a
	 * retry starts its attempt, so a second fill finds it waiting and leaves it (#296)
	 */
	@Test
	@DisplayName("two fills at once take a retry once")
	void twoFillsAtOnce_takeARetryOnce() throws Exception
	{
		assertEquals(1, dialsSubmittedByTwoFillsAtOnce(true), "dials of " + NEW + " submitted");
	}

	/**
	 * The same for a first attempt, which waits for nothing: the selection and the entry on dialing
	 * are one locked step, so the first fill waits until the second has taken the address, and then
	 * finds it busy (#296). Before, the second fill copied dialing before the first put the address
	 * on it, and both took it
	 */
	@Test
	@DisplayName("two fills at once take a first attempt once: the selection is one locked step")
	void twoFillsAtOnce_takeAFirstAttemptOnce() throws Exception
	{
		assertEquals(1, dialsSubmittedByTwoFillsAtOnce(false), "dials of " + NEW + " submitted");
	}

	/**
	 * Runs two fills at once on an address heard of, a retry after its pause or a first attempt,
	 * and counts the dials they submit
	 */
	private int dialsSubmittedByTwoFillsAtOnce(final boolean retry) throws Exception
	{
		ManualClock clock = new ManualClock();
		AtomicReference<Thread> firstFill = new AtomicReference<>();
		AtomicReference<Thread> secondFill = new AtomicReference<>();
		CountDownLatch secondInItsSelection = new CountDownLatch(1);
		CountDownLatch firstTookTheAddress = new CountDownLatch(1);
		Discovery.Dialer dialer = new Discovery.Dialer()
		{
			@Override
			public int outgoing()
			{
				return 0;
			}

			@Override
			public int maximum()
			{
				return Node.MAXIMUM_OUTGOING;
			}

			@Override
			public void dial(final PeerAddress address)
			{
			}

			/** Discovery.busy() copies dialing first and asks for the handshakes after */
			@Override
			public Set<PeerAddress> handshaking()
			{
				if (Thread.currentThread() == secondFill.get()
					&& secondInItsSelection.getCount() > 0)
				{
					secondInItsSelection.countDown();
					letTheFirstFillGo(firstFill, firstTookTheAddress);
				}
				return Set.of();
			}
		};
		HoldsEveryDial threads = new HoldsEveryDial(() -> {
			if (Thread.currentThread() == firstFill.get())
			{
				firstTookTheAddress.countDown();
			}
		});
		try (ServerSocket server = new ServerSocket(0);
			Socket ours = new Socket("127.0.0.1", server.getLocalPort());
			Socket theirs = server.accept())
		{
			Discovery discovery = new Discovery(List.of(), new Refusals(), dialer, threads, clock);
			Peer peer = peerOver(ours);
			peer.expectPeers();
			discovery.dialling(false);
			discovery.learn(peer, new PeerList(List.of(NEW)));
			if (retry)
			{
				discovery.unreachable(NEW);
				clock.advance(PeerBook.FIRST_PAUSE);
			}
			discovery.dialling(true);
			Thread second = Thread.ofPlatform().name("second fill").unstarted(discovery::fill);
			secondFill.set(second);
			second.start();
			awaitLatch(secondInItsSelection);
			Thread first = Thread.ofPlatform().name("first fill").unstarted(discovery::fill);
			firstFill.set(first);
			first.start();

			first.join(TimeUnit.SECONDS.toMillis(10));
			second.join(TimeUnit.SECONDS.toMillis(10));
			assertFalse(first.isAlive() || second.isAlive(), "a fill did not end");
			return threads.held();
		}
	}

	/**
	 * Two fills at once for the last free slot dial once: the room is counted inside the locked
	 * step that takes the address, dialing before the outgoing connections, so the first fill
	 * waits until the second has taken the address and then finds no room (#325). Before, the
	 * second fill read the count, the first took an address and dialled it, and the second added a
	 * dialing that no longer held it to a count that did not hold it yet and took a second address
	 * for a slot that was gone: Node.connect refused it for the node's own limit, and the address
	 * stayed undialled while the node was full, the third way FailedDialTest failed under load
	 */
	@Test
	@DisplayName("two fills at once for the last free slot dial once")
	void twoFillsAtOnce_forTheLastFreeSlot_dialOnce() throws Exception
	{
		assertEquals(1, dialsForTheLastFreeSlot(), "dials for one free slot");
	}

	/**
	 * A dial under way while the room is counted is counted once, whichever way it moves
	 * meanwhile: dialing is read before the outgoing connections, and a dial counts as outgoing
	 * before it leaves dialing (#325). The node has one slot left and a dial under way for it;
	 * while a fill reads the count of outgoing connections, that dial counts itself and leaves
	 * dialing. Read the other way round, the fill missed it and took a second address for a slot
	 * that was gone
	 */
	@Test
	@DisplayName("a dial that moves while the room is counted is counted once")
	void aDialThatMovesWhileTheRoomIsCounted_isCountedOnce() throws Exception
	{
		AtomicInteger outgoing = new AtomicInteger(Node.MAXIMUM_OUTGOING - 1);
		List<PeerAddress> dialled = new CopyOnWriteArrayList<>();
		CountDownLatch letTheDialMove = new CountDownLatch(1);
		AtomicReference<Thread> counting = new AtomicReference<>();
		AtomicReference<Thread> dialThread = new AtomicReference<>();
		Discovery.Dialer dialer = new Discovery.Dialer()
		{
			/** While the fill counts, the dial under way counts itself and leaves dialing */
			@Override
			public int outgoing()
			{
				int count = outgoing.get();
				if (counting.compareAndSet(Thread.currentThread(), null))
				{
					letTheDialMove.countDown();
					awaitBlockedOn(dialThread.get(), Thread.currentThread());
				}
				return count;
			}

			@Override
			public int maximum()
			{
				return Node.MAXIMUM_OUTGOING;
			}

			/** As Node.connect: the slot counts from the dial on */
			@Override
			public void dial(final PeerAddress address)
			{
				if (dialThread.compareAndSet(null, Thread.currentThread()))
				{
					awaitLatch(letTheDialMove);
				}
				outgoing.incrementAndGet();
				dialled.add(address);
			}

			@Override
			public Set<PeerAddress> handshaking()
			{
				return Set.copyOf(dialled);
			}
		};
		OnPlatformThreads threads = new OnPlatformThreads();
		try (ServerSocket server = new ServerSocket(0);
			Socket ours = new Socket("127.0.0.1", server.getLocalPort());
			Socket theirs = server.accept())
		{
			Discovery discovery = new Discovery(List.of(), new Refusals(), dialer, threads);
			Peer peer = peerOver(ours);
			peer.expectPeers();
			discovery.dialling(false);
			discovery.learn(peer, new PeerList(List.of(NEW, ANOTHER)));
			discovery.dialling(true);
			discovery.fill();
			await("the dial under way", () -> dialThread.get() != null);

			counting.set(Thread.currentThread());
			discovery.fill();
			threads.awaitFinished();

			assertEquals(List.of(NEW), dialled, "dialled for one free slot");
		}
	}

	/**
	 * Waits until the thread is blocked on a lock the holder holds, which here is the moment a
	 * finished dial waits to fill again behind the counting fill
	 */
	private static void awaitBlockedOn(final Thread thread, final Thread holder)
	{
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		await("the dial blocked on the counting fill's lock", () -> {
			ThreadInfo state = threads.getThreadInfo(thread.threadId());
			return state != null && state.getLockOwnerId() == holder.threadId();
		});
	}

	/** Runs each dial on a platform thread of its own, which ThreadMXBean can see */
	private static final class OnPlatformThreads extends AbstractExecutorService
	{

		private final List<Thread> started = new CopyOnWriteArrayList<>();

		@Override
		public void execute(final Runnable task)
		{
			started.add(Thread.ofPlatform().start(task));
		}

		/** Waits for every dial, those a dial started included */
		void awaitFinished() throws InterruptedException
		{
			for (int index = 0; index < started.size(); index++)
			{
				started.get(index).join(TimeUnit.SECONDS.toMillis(10));
			}
		}

		@Override
		public void shutdown()
		{
		}

		@Override
		public List<Runnable> shutdownNow()
		{
			return List.of();
		}

		@Override
		public boolean isShutdown()
		{
			return false;
		}

		@Override
		public boolean isTerminated()
		{
			return started.stream().noneMatch(Thread::isAlive);
		}

		@Override
		public boolean awaitTermination(final long timeout, final TimeUnit unit)
			throws InterruptedException
		{
			awaitFinished();
			return isTerminated();
		}
	}

	/**
	 * Runs two fills at once while one slot is free, the first dialling on its own thread as soon
	 * as it takes an address, and counts the dials
	 */
	private int dialsForTheLastFreeSlot() throws Exception
	{
		AtomicInteger outgoing = new AtomicInteger(Node.MAXIMUM_OUTGOING - 1);
		List<PeerAddress> dialled = new CopyOnWriteArrayList<>();
		AtomicReference<Thread> firstFill = new AtomicReference<>();
		AtomicReference<Thread> secondFill = new AtomicReference<>();
		CountDownLatch secondReadTheCount = new CountDownLatch(1);
		CountDownLatch firstDialled = new CountDownLatch(1);
		Discovery.Dialer dialer = new Discovery.Dialer()
		{
			/** The second fill reads the count, then waits before it goes on with it */
			@Override
			public int outgoing()
			{
				int count = outgoing.get();
				if (Thread.currentThread() == secondFill.get() && secondReadTheCount.getCount() > 0)
				{
					secondReadTheCount.countDown();
					letTheFirstFillGo(firstFill, firstDialled);
				}
				return count;
			}

			@Override
			public int maximum()
			{
				return Node.MAXIMUM_OUTGOING;
			}

			/** As Node.connect: the slot counts from the dial on */
			@Override
			public void dial(final PeerAddress address)
			{
				outgoing.incrementAndGet();
				dialled.add(address);
				if (Thread.currentThread() == firstFill.get())
				{
					firstDialled.countDown();
				}
			}

			/** A dialled address is connected, and so busy */
			@Override
			public Set<PeerAddress> handshaking()
			{
				return Set.copyOf(dialled);
			}
		};
		try (ServerSocket server = new ServerSocket(0);
			Socket ours = new Socket("127.0.0.1", server.getLocalPort());
			Socket theirs = server.accept())
		{
			Discovery discovery = new Discovery(List.of(), new Refusals(), dialer,
				new OnTheCallingThread());
			Peer peer = peerOver(ours);
			peer.expectPeers();
			discovery.dialling(false);
			discovery.learn(peer, new PeerList(List.of(NEW, ANOTHER)));
			discovery.dialling(true);
			Thread second = Thread.ofPlatform().name("second fill").unstarted(discovery::fill);
			secondFill.set(second);
			second.start();
			awaitLatch(secondReadTheCount);
			Thread first = Thread.ofPlatform().name("first fill").unstarted(discovery::fill);
			firstFill.set(first);
			first.start();

			first.join(TimeUnit.SECONDS.toMillis(10));
			second.join(TimeUnit.SECONDS.toMillis(10));
			assertFalse(first.isAlive() || second.isAlive(), "a fill did not end");
			return dialled.size();
		}
	}

	/**
	 * Holds the second fill until the first has taken the address, or until the first waits on a
	 * lock the second fill holds, so that it cannot take the address before the second is done
	 */
	private static void letTheFirstFillGo(final AtomicReference<Thread> firstFill,
		final CountDownLatch firstTookTheAddress)
	{
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		long holder = Thread.currentThread().threadId();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (firstTookTheAddress.getCount() > 0)
		{
			Thread first = firstFill.get();
			ThreadInfo state = first == null ? null : threads.getThreadInfo(first.threadId());
			if (state != null && state.getLockOwnerId() == holder)
			{
				return;
			}
			if (System.nanoTime() > deadline)
			{
				throw new AssertionError("the first fill neither took the address nor waited on "
					+ "the second in 10 s");
			}
			Thread.onSpinWait();
		}
	}

	/**
	 * Holds every dial submitted to it, so that the address stays on dialing, counts them, and says
	 * so on the thread that submits
	 */
	private static final class HoldsEveryDial extends AbstractExecutorService
	{

		private final AtomicInteger held = new AtomicInteger();

		private final Runnable submitted;

		HoldsEveryDial(final Runnable submitted)
		{
			this.submitted = submitted;
		}

		@Override
		public void execute(final Runnable dial)
		{
			held.incrementAndGet();
			submitted.run();
		}

		int held()
		{
			return held.get();
		}

		@Override
		public void shutdown()
		{
		}

		@Override
		public List<Runnable> shutdownNow()
		{
			return List.of();
		}

		@Override
		public boolean isShutdown()
		{
			return false;
		}

		@Override
		public boolean isTerminated()
		{
			return true;
		}

		@Override
		public boolean awaitTermination(final long timeout, final TimeUnit unit)
		{
			return true;
		}
	}

	/** Runs each dial on the thread that submits it, so that the order of a test is the order run */
	private static final class OnTheCallingThread extends AbstractExecutorService
	{

		@Override
		public void execute(final Runnable task)
		{
			task.run();
		}

		@Override
		public void shutdown()
		{
		}

		@Override
		public List<Runnable> shutdownNow()
		{
			return List.of();
		}

		@Override
		public boolean isShutdown()
		{
			return false;
		}

		@Override
		public boolean isTerminated()
		{
			return false;
		}

		@Override
		public boolean awaitTermination(final long timeout, final TimeUnit unit)
		{
			return true;
		}
	}

	private static void awaitLatch(final CountDownLatch latch)
	{
		try
		{
			if (!latch.await(10, TimeUnit.SECONDS))
			{
				throw new AssertionError("the latch was not counted down in 10 s");
			}
		}
		catch (InterruptedException interrupted)
		{
			Thread.currentThread().interrupt();
			throw new AssertionError(interrupted);
		}
	}

	/**
	 * Runs each dial on a virtual thread of its own, and lets the fill that submitted the first one
	 * go on only once that dial has counted its slot, as when the filling thread is held up right
	 * after the submit
	 */
	private static final class FirstDialHoldsTheFill extends AbstractExecutorService
	{

		private final CountDownLatch slotCounted;

		private final List<Thread> started = new CopyOnWriteArrayList<>();

		FirstDialHoldsTheFill(final CountDownLatch slotCounted)
		{
			this.slotCounted = slotCounted;
		}

		@Override
		public void execute(final Runnable task)
		{
			started.add(Thread.ofVirtual().start(task));
			if (started.size() == 1)
			{
				awaitLatch(slotCounted);
			}
		}

		/** Waits for every dial, those a dial started included */
		void awaitFinished() throws InterruptedException
		{
			for (int index = 0; index < started.size(); index++)
			{
				started.get(index).join(TimeUnit.SECONDS.toMillis(10));
			}
		}

		@Override
		public void shutdown()
		{
		}

		@Override
		public List<Runnable> shutdownNow()
		{
			return List.of();
		}

		@Override
		public boolean isShutdown()
		{
			return false;
		}

		@Override
		public boolean isTerminated()
		{
			return started.stream().noneMatch(Thread::isAlive);
		}

		@Override
		public boolean awaitTermination(final long timeout, final TimeUnit unit)
			throws InterruptedException
		{
			awaitFinished();
			return isTerminated();
		}
	}

	private static Peer peerOver(final Socket socket) throws IOException
	{
		return new Peer(socket, new DataInputStream(socket.getInputStream()),
			new DataOutputStream(socket.getOutputStream()),
			Hello.of(testGenesis(Bytes.of(new byte[] { 1 }))), Node.ANSWER_MILLIS,
			reason -> {
			});
	}
}
