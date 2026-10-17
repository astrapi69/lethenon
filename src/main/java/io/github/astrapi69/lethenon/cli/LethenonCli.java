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

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The command line of the chain (lethenon#2, milestone 4).
 * <p>
 * Everything works on two kinds of file: the chain file, which anybody can replay, and a wallet
 * file, which only its password opens. There is no server and no network here: a balance is
 * computed from the chain file, never asked of anybody.
 * <p>
 * A password is never an argument - an argument list is visible to every process on the machine.
 * It is read from the first line of standard input.
 * <p>
 * Exit codes: 0 done; 1 refused, with the reason on standard error - a chain that does not
 * verify, a wrong password, a transfer the account cannot cover; 2 a command line that was not
 * understood.
 */
@Command(name = "lethenon", mixinStandardHelpOptions = true, versionProvider = BuildVersion.class,
	description = "A protest chain against the politics of surveillance.",
	subcommands = { WalletCommand.class, MineCommand.class, SendCommand.class,
			FaucetCommand.class, BalanceCommand.class, SweepCommand.class,
			NodeCommand.class, GenesisCommand.class, SyncCommand.class, ExportCommand.class })
public class LethenonCli
{

	/**
	 * Creates the root command; picocli instantiates it reflectively
	 */
	public LethenonCli()
	{
	}

	/**
	 * Runs the command line and returns its exit code instead of ending the process, so that it
	 * can be driven from tests
	 *
	 * @param args
	 *            the command line
	 * @return the exit code
	 */
	public static int execute(final String... args)
	{
		return new CommandLine(new LethenonCli()).execute(args);
	}

	/**
	 * Runs the command line and ends the process with its exit code
	 *
	 * @param args
	 *            the command line
	 */
	public static void main(final String[] args)
	{
		System.exit(execute(args));
	}
}
