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
package io.github.astrapi69.lethenon.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import io.github.astrapi69.lethenon.AddressText;
import io.github.astrapi69.lethenon.Amount;
import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Bytes;
import io.github.astrapi69.lethenon.Chain;
import io.github.astrapi69.lethenon.Destination;
import io.github.astrapi69.lethenon.OneTimeAddresses;
import io.github.astrapi69.lethenon.PublishedAddress;
import io.github.astrapi69.lethenon.SignatureSuite;
import io.github.astrapi69.lethenon.SignedTransaction;
import io.github.astrapi69.lethenon.Transfers;
import io.github.astrapi69.lethenon.Wallet;
import io.github.astrapi69.lethenon.transport.Handover;
import io.github.astrapi69.lethenon.transport.PeerAddress;
import picocli.CommandLine.Option;

/**
 * Signs a transfer and leaves it waiting for the next block - what {@code send} and
 * {@code faucet} share.
 * <p>
 * The nonce and the balance are taken from the replayed chain and the transfers already waiting
 * from the same account, so a second {@code send} before the next block gets the next nonce and
 * cannot spend the same money twice. A transfer the account cannot cover is refused before
 * anything is signed.
 */
abstract class TransferCommand extends ChainCommand
{

	@Option(names = "--to",
		description = "the recipient's account, as 'wallet create' and 'balance' print it: "
			+ "tleth1: or leth1:, the key and a checksum; on the test chain also the bare key in "
			+ "hex, which has no checksum. The transparent case - the chain shows who was paid")
	String recipient;

	@Option(names = "--to-address",
		description = "the recipient's published address, as 'wallet create' and 'balance' print "
			+ "it: tleth1: or leth1:, the view key, the spend key and a checksum; on the test "
			+ "chain also the old view:spend in hex. The transfer then goes to a one-time "
			+ "destination derived from it, and two payments to the same address have nothing "
			+ "visibly in common")
	String recipientAddress;

	@Option(names = "--fee", defaultValue = "0",
		description = "the fee in LETH, up to 8 decimals; default: 0")
	String fee;

	@Option(names = "--node",
		description = "host:port of a running node that serves the chain file named by --chain; the "
			+ "transfer is handed to it instead of being written to the pool file, which the node "
			+ "keeps")
	String node;

	@Option(names = "--suite", defaultValue = "ed25519",
		description = "the signature suite of the sending account: ed25519 or ml-dsa-65")
	String suite;

	/**
	 * Creates the command; picocli instantiates it reflectively
	 */
	TransferCommand()
	{
	}

	/**
	 * Signs a transfer from the wallet's account and appends it to the waiting transfers
	 *
	 * @param out
	 *            where to report
	 * @param sender
	 *            the opened wallet
	 * @param blocks
	 *            the chain, already read
	 * @param amount
	 *            what is transferred
	 * @param memo
	 *            the signed memo
	 * @return the exit code
	 * @throws IOException
	 *             if the waiting transfers cannot be read or written
	 */
	int transfer(final PrintStream out, final Wallet sender, final List<BlockBody> blocks,
		final Amount amount, final String memo) throws IOException
	{
		List<SignedTransaction> waiting = new ArrayList<>(readPending());
		String chainIdentifier = chainOf(blocks);
		Recipient to = recipient(chainIdentifier);
		SignedTransaction signed = Transfers.prepare(sender, SignatureSuite.withIdentifier(suite),
			blocks, waiting, to.destination(), amount, amountOf(fee), memo);
		String signedLine = "signed a transfer of " + amount + " LETH to " + to.named()
			+ " with nonce " + signed.body().nonce();
		if (node != null)
		{
			int count = handOver(blocks, signed);
			out.println(signedLine + " and handed it to the node at " + node
				+ "; it waits in its pool (" + count + " waiting)");
			return 0;
		}
		waiting.add(signed);
		writePending(waiting);
		out.println(signedLine + "; it waits for the next block (" + waiting.size() + " waiting)");
		return 0;
	}

	/**
	 * Hands the transfer to the node and reads its pool file afterwards: the handover returns once
	 * the node has handled the frame, so the file then says whether it was admitted (ADR 0003)
	 *
	 * @return how many transfers wait in the node's pool
	 * @throws IllegalStateException
	 *             when the node's pool file does not carry the transfer
	 */
	private int handOver(final List<BlockBody> blocks, final SignedTransaction signed)
		throws IOException
	{
		Handover.send(PeerAddress.parse(node), blocks, signed);
		List<SignedTransaction> after = readPending();
		if (!after.contains(signed))
		{
			throw new IllegalStateException("the node at " + node + " took the transfer, and it "
				+ "is not waiting in " + chain + ": a node that serves another chain file keeps "
				+ "its pool there, and a node that refused it says why in its output");
		}
		return after.size();
	}

	/**
	 * Where the money goes: a transparent account, or a one-time destination derived from a
	 * published address, each read as ADR 0007 reads it, for the chain of the transfer (#309).
	 * Exactly one of the two options says so - a transfer that names both leaves which one wins to
	 * the reader, and one that names neither has no recipient at all. An old form without a
	 * checksum is taken on the test chain, and the command says that a typing mistake in it would
	 * not have been noticed.
	 */
	private Recipient recipient(final String chainIdentifier)
	{
		boolean hasKey = recipient != null && !recipient.isBlank();
		boolean hasAddress = recipientAddress != null && !recipientAddress.isBlank();
		if (hasKey == hasAddress)
		{
			throw new IllegalArgumentException("name the recipient once: --to for an account, "
				+ "or --to-address for a published address");
		}
		String given = hasKey ? recipient : recipientAddress;
		if (AddressText.hasNoChecksum(given) && !Chain.IDENTIFIER.equals(chainIdentifier))
		{
			System.err.println("this address has no checksum, so a typing mistake in it would "
				+ "not have been noticed; ask for its " + AddressText.prefixFor(chainIdentifier)
				+ AddressText.SEPARATOR + " form");
		}
		if (hasKey)
		{
			Bytes account = AddressText.account(recipient, chainIdentifier);
			// a direct transfer names its recipient in the clear anyway, so printing it costs nothing
			return new Recipient(Destination.direct(account),
				AddressText.ofAccount(account, chainIdentifier));
		}
		PublishedAddress address = AddressText.published(recipientAddress, chainIdentifier);
		// a published address is NOT printed as the destination it produced: the point of a
		// one-time destination is that nobody but the recipient can connect the two, and a
		// sender's own terminal is a place where that connection would be written down
		return new Recipient(
			OneTimeAddresses.destinationFor(address, OneTimeAddresses.newEphemeralKeyPair()),
			"a one-time destination of " + AddressText.of(address, chainIdentifier));
	}

	/**
	 * The recipient of a transfer and how the line printed afterwards names it
	 *
	 * @param destination
	 *            where the money goes
	 * @param named
	 *            what the printed line says it went to
	 */
	private record Recipient(Destination destination, String named)
	{
	}

	/**
	 * An amount as a person types it - "12.5", "1984", "0.00000001" - turned into lethe exactly;
	 * {@link Amount#parseLeth(String)} since lethenon#32, where the desktop plugin reads it too
	 *
	 * @param text
	 *            the amount in LETH
	 * @return the amount
	 * @throws IllegalArgumentException
	 *             for more than eight decimals, a negative amount, or no number at all
	 */
	static Amount amountOf(final String text)
	{
		return Amount.parseLeth(text);
	}
}
