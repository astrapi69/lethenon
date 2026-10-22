/**
 * The MIT License
 *
 * Copyright (C) 2026 Asterios Raptis
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
import java.nio.file.Path;
import java.util.concurrent.Callable;

import io.github.astrapi69.lethenon.ChainExport;
import io.github.astrapi69.lethenon.ChainRejected;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The way back to lethenon 0.5.0 (#224): the chain database written as the chain file 0.5.0 reads,
 * with the transfers waiting in it, as the chain is now. The {@code <chain>.legacy} file the
 * migration left holds the chain only as it was when it was migrated.
 * <p>
 * It needs no wallet: the chain is the same for everybody. It overwrites nothing, and it leaves a
 * chain file of 0.5.0 given by mistake as it is ({@link ChainExport}).
 */
@Command(name = "export", description = "Write the chain database as a chain file of lethenon 0.5.0, "
	+ "with its waiting transfers in <file>.pending, so that 0.5.0 reads the chain as it is now. "
	+ "Overwrites nothing.")
class ExportCommand implements Callable<Integer>
{

	@Option(names = "--chain", required = true, description = "the chain database")
	Path chain;

	@Option(names = "--to", required = true,
		description = "the chain file to write; it and <file>.pending must not exist")
	Path target;

	/**
	 * Creates the command; picocli instantiates it reflectively
	 */
	ExportCommand()
	{
	}

	@Override
	public Integer call()
	{
		try
		{
			ChainExport.Exported exported = new ChainExport(chain).to(target);
			System.out.println("wrote " + exported.blocks() + " blocks to " + target
				+ (exported.waiting() > 0
					? " and " + exported.waiting() + " waiting transfers to " + target + ".pending"
					: ", no transfer is waiting")
				+ "; lethenon 0.5.0 reads them, and 0.6.0 migrates them again when it is given them");
			return 0;
		}
		catch (ChainRejected | IllegalArgumentException | IllegalStateException | IOException refused)
		{
			System.err.println(refused.getMessage());
			return 1;
		}
	}
}
