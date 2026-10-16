import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.CanonicalEncoding;
import io.github.astrapi69.lethenon.ChainFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a chain one block at a time through whichever ChainFile is on the class path, the way a
 * node does: the first 2 blocks, the first 3, ..., all of them (#202).
 * Arguments: the chain in its canonical encoding, the chain file to write.
 */
public class WriteLoad {
    public static void main(String[] args) throws Exception {
        List<BlockBody> chain = CanonicalEncoding.readChain(Files.readAllBytes(Path.of(args[0])));
        ChainFile file = new ChainFile(Path.of(args[1]));
        file.write(chain.subList(0, 1));
        long start = System.nanoTime();
        for (int length = 2; length <= chain.size(); length++) {
            file.write(chain.subList(0, length));
        }
        System.out.printf("wrote %d blocks one at a time in %.1f s%n", chain.size() - 1,
            (System.nanoTime() - start) / 1e9);
    }
}
