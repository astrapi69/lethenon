import java.sql.*;
public class SqliteProbe {
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + args[0])) {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
                s.execute("CREATE TABLE IF NOT EXISTS block(height INTEGER PRIMARY KEY, body BLOB NOT NULL)");
            }
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO block VALUES(?,?)")) {
                p.setLong(1, 0); p.setBytes(2, new byte[2205]); p.executeUpdate();
            }
            c.commit();
        }
        System.out.printf("open+commit %.1f ms%n", (System.nanoTime() - start) / 1e6);
    }
}
