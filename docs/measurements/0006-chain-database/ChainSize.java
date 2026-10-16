import io.github.astrapi69.lethenon.*;
import java.nio.file.*;
import java.util.*;
/**
 * The size of a chain of 366 blocks without transfers, as a chain file of 0.5.0 and as the database
 * of 0.6.0, written one block at a time (#202). Arguments: the chain file to write, the database.
 */
public class ChainSize {
    public static void main(String[] a) throws Exception {
        Bytes miner = Bytes.of(new byte[] { 5 });
        long time = 1_760_000_000_000L;
        List<BlockBody> chain = new ArrayList<>();
        chain.add(Genesis.candidate(Chain.TEST_IDENTIFIER, "empty", time));
        for (int h = 1; h <= 365; h++) {
            chain.add(Blocks.mine(Mining.nextBlock(Chain.TEST_IDENTIFIER, chain, miner, List.of(), "empty", time + 120_000L * h), 10_000_000L).orElseThrow());
        }
        Files.write(Path.of(a[0]), CanonicalEncoding.encodeChain(chain));
        ChainFile file = new ChainFile(Path.of(a[1]));
        for (int n = 1; n <= chain.size(); n++) file.write(chain.subList(0, n));
        System.out.println("blocks " + chain.size() + " legacy bytes " + Files.size(Path.of(a[0])) + " database bytes " + Files.size(Path.of(a[1])));
    }
}
