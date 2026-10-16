import java.io.File;
import java.nio.ByteBuffer;
import org.lmdbjava.*;
public class LmdbProbe {
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        File dir = new File(args[0]); dir.mkdirs();
        try (Env<ByteBuffer> env = Env.create().setMapSize(1L << 30).setMaxDbs(2).open(dir)) {
            Dbi<ByteBuffer> db = env.openDbi("block", DbiFlags.MDB_CREATE);
            ByteBuffer key = ByteBuffer.allocateDirect(8).putLong(0, 0);
            ByteBuffer val = ByteBuffer.allocateDirect(2205);
            try (Txn<ByteBuffer> txn = env.txnWrite()) { db.put(txn, key, val); txn.commit(); }
        }
        System.out.printf("open+commit %.1f ms%n", (System.nanoTime() - start) / 1e6);
    }
}
