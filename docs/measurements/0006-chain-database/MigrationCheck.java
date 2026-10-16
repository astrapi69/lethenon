import io.github.astrapi69.lethenon.BlockBody;
import io.github.astrapi69.lethenon.Blocks;
import io.github.astrapi69.lethenon.CanonicalEncoding;
import io.github.astrapi69.lethenon.ChainFile;
import io.github.astrapi69.lethenon.Replay;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Migrates a copy of a chain file of 0.5.0 or earlier with this build's ChainFile and compares the
 * result with the old file block by block, independently of the comparison the migration makes
 * itself (#202). Arguments: the old chain file (left untouched), a directory for the copy.
 */
public class MigrationCheck {
    public static void main(String[] args) throws Exception {
        Path original = Path.of(args[0]);
        byte[] legacy = Files.readAllBytes(original);
        Path copy = Path.of(args[1]).resolve(original.getFileName());
        Files.write(copy, legacy);
        Path pending = original.resolveSibling(original.getFileName() + ".pending");
        if (Files.exists(pending)) {
            Files.copy(pending, copy.resolveSibling(copy.getFileName() + ".pending"));
        }
        List<BlockBody> expected = CanonicalEncoding.readChain(legacy);
        long start = System.nanoTime();
        List<BlockBody> migrated = new ChainFile(copy).read();
        double seconds = (System.nanoTime() - start) / 1e9;
        if (migrated.size() != expected.size()) {
            throw new IllegalStateException(migrated.size() + " blocks migrated, " + expected.size() + " in the file");
        }
        for (int height = 0; height < expected.size(); height++) {
            if (!Arrays.equals(CanonicalEncoding.encodeChain(List.of(expected.get(height))),
                CanonicalEncoding.encodeChain(List.of(migrated.get(height))))) {
                throw new IllegalStateException("block " + height + " differs");
            }
        }
        Replay replay = Replay.verify(new ChainFile(copy).read());
        System.out.printf("%d blocks, %d bytes, compared block by block: identical; migration and verified read took %.2f s%n",
            expected.size(), legacy.length, seconds);
        System.out.println("tip " + Blocks.hashOf(migrated.getLast()) + " at height " + migrated.getLast().height());
        System.out.println(replay.describe());
        System.out.println("old file kept: " + Arrays.equals(legacy, Files.readAllBytes(copy.resolveSibling(copy.getFileName() + ".legacy"))));
        System.out.println("database: " + Files.size(copy) + " bytes");
    }
}
