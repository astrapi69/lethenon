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

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a wallet owns, worked out from the chain it holds and nothing else.
 * <p>
 * A wallet that asks a server for its balance tells that server which addresses belong to one
 * person, from which network address, and when - every time it is opened. That single message
 * undoes what one-time destinations are for, which is why milestone 3 asks for a balance that is
 * computed rather than requested (lethenon#2): the wallet replays the chain, recognises its own
 * destinations with the view private key, and reads the amounts out of the state its own replay
 * produced.
 * <p>
 * The balance is therefore a VERIFIED balance. A chain that does not replay has no balance at all
 * here - {@link Replay} throws and this method does not catch it - because a number taken from
 * unverified blocks is exactly the number a server would have been trusted for.
 * <p>
 * The spending key is not a parameter. Recognising payments needs the view private key alone, so a
 * scan can run where the key that moves money never goes.
 *
 * @param received
 *            the payments that belong to this address, in the order the chain carries them
 * @param balance
 *            the sum the replayed state holds at those destinations
 * @param blocksRead
 *            how many blocks were replayed and scanned
 * @param transactionsRead
 *            how many transfers were looked at, ours and everybody else's
 */
public record WalletScan(List<Received> received, Amount balance, long blocksRead,
	long transactionsRead)
{

	/**
	 * One payment that belongs to the scanning wallet
	 *
	 * @param height
	 *            the block it arrived in
	 * @param destination
	 *            the one-time destination it was paid to, whole rather than only its key: deriving
	 *            the key that SPENDS it needs the ephemeral key the destination carries (#37)
	 * @param amount
	 *            what the transfer carried
	 * @param memo
	 *            the memo signed with it
	 */
	public record Received(long height, Destination destination, Amount amount, String memo)
	{
	}

	/**
	 * Keeps the payments as an unmodifiable copy, so a scan cannot be changed after it was made
	 */
	public WalletScan
	{
		received = List.copyOf(received);
	}

	/**
	 * Replays the chain and collects what belongs to one published address
	 *
	 * @param chain
	 *            the blocks, genesis first
	 * @param address
	 *            the published address the payments were sent to
	 * @param viewPrivateKey
	 *            the private half of the view key, and nothing more
	 * @return what this address owns, and what was read to find it
	 * @throws ChainRejected
	 *             when the chain does not verify, in which case there is no balance to report
	 */
	public static WalletScan over(final List<BlockBody> chain, final PublishedAddress address,
		final PrivateKey viewPrivateKey)
	{
		Replay replay = Replay.verify(chain);
		List<Received> received = new ArrayList<>();
		long transactions = 0L;
		for (BlockBody block : chain)
		{
			for (SignedTransaction transaction : block.transactions())
			{
				transactions++;
				TransactionBody body = transaction.body();
				if (OneTimeAddresses.belongsTo(body.recipient(), address, viewPrivateKey))
				{
					received.add(new Received(block.height(), body.recipient(), body.amount(),
						body.memo()));
				}
			}
		}
		return new WalletScan(received, balanceOf(received, replay.finalState()), chain.size(),
			transactions);
	}

	/**
	 * Says what was read as well as what was found, so a scan that saw nothing cannot be mistaken
	 * for an address that was paid nothing
	 *
	 * @return the one-line report
	 */
	public String describe()
	{
		return "read " + blocksRead + " blocks and " + transactionsRead
			+ " transfers from the chain, recognised " + received.size() + " of them as ours, "
			+ "holding " + balance + " LETH; nothing was asked of anybody";
	}

	/**
	 * The sum the verified state holds at the destinations that were recognised. Each destination
	 * counts once: a sender that reuses an ephemeral key pays twice to one destination, and the
	 * state holds that sum already.
	 */
	private static Amount balanceOf(final List<Received> received, final ChainState state)
	{
		Set<Bytes> destinations = new LinkedHashSet<>();
		for (Received payment : received)
		{
			destinations.add(payment.destination().key());
		}
		Amount balance = Amount.ZERO;
		for (Bytes destination : destinations)
		{
			balance = balance.plus(state.balanceOf(destination));
		}
		return balance;
	}
}
