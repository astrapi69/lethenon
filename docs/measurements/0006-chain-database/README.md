# Measurements for ADR 0006, the chain database (#202)

Every number in ADR 0006, in the CHANGELOG entry of 0.6.0 and in the pull request of #202 was
measured with the files in this directory, on 2026-10-16, on Linux x86_64 (ext4) with OpenJDK 25.

## The two candidates: start time and memory

`SqliteProbe.java` and `LmdbProbe.java` open a database, write one block of 2,205 bytes in one
transaction with full sync, and close it. `measure.py` runs a command and reports its wall time and
the peak resident memory of the process.

```sh
javac -cp "<sqlite jars>/*" SqliteProbe.java
javac -cp "<lmdb jars>/*" LmdbProbe.java
python3 measure.py sqlite1 java -Xmx64m -cp "<sqlite jars>/*:." SqliteProbe s1.db
python3 measure.py lmdb1 java -Xmx64m --add-opens java.base/java.nio=ALL-UNNAMED \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED -cp "<lmdb jars>/*:." LmdbProbe l1
```

The jars were resolved from Maven Central: `org.xerial:sqlite-jdbc:3.53.4.0` and
`org.lmdbjava:lmdbjava:0.9.3` with its dependencies. Without the two `--add-opens`, lmdbjava fails
in `ByteBufferProxy.<clinit>`.

## Write load, before and after

`measure-write-load.sh` writes the same chain, 301 blocks with ten transfers each (663,768 bytes in
the encoding of 0.5.0), one block at a time, first through the `ChainFile` of 0.5.0 and then
through this build's, with `src/test/native/iolog.c` recording every write and every sync.
`iolog-sum.py` adds the record up.

```sh
./gradlew installDist
docs/measurements/0006-chain-database/measure-write-load.sh <chain> <lib of 0.5.0> \
    build/install/lethenon/lib <work dir>
```

| | 0.5.0, the file rewritten whole | 0.6.0, the database |
|---|---|---|
| bytes written for 300 blocks | 99,623,517 | 7,411,060 |
| writes | 12,314 | 5,412 |
| syncs to the disk | 0 | 1,207 |
| per block | the whole chain again, 664 KB at height 300 | about 25 KB, the same at every height |
| time for 300 blocks | 2.9 s | 1.2 s |
| file at the end | 663,768 bytes | 1,236,992 bytes |

The database is larger on disk because SQLite stores in pages of 4 KB; a block of 2.2 KB fills about
half of one. A chain of 366 blocks without transfers is 53,743 bytes as a chain file of 0.5.0 and
90,112 bytes as a database (`ChainSize.java`).

## Migration

`MigrationCheck.java` migrates a copy of a chain file of 0.5.0 with this build and compares the
result block by block, independently of the comparison the migration makes itself, then replays it.
On the 301-block chain above:

```
301 blocks, 663768 bytes, compared block by block: identical; migration and verified read took 0.48 s
replayed 301 blocks, applied 2990 transfers, checked 2990 signatures; ...
old file kept: true
database: 1236992 bytes
```

`migrate-seed-chain.sh` does the same with a chain synced from seed node 1 by a build of 0.5.0. It
could not run where this was written: the seed node's port is not reachable from there.

## The crash tests

`ChainCrashTest` and `PowerCutTest` in `src/test/java`, 25 runs each in every build. The gate before
a release runs them a thousand times:

```sh
./gradlew test --tests '*ChainCrashTest' --tests '*PowerCutTest' -PcrashRuns=1000
```

On db0b4bf, 2026-10-16 08:55 to 09:19 UTC, Linux x86_64: 4 tests, 0 failures, 23 min 37 s.

| test | runs | killed inside a write | broken |
|---|---|---|---|
| kill -9 while blocks are written | 1000 | 971 | 0 |
| kill -9 while a fork is rolled back | 1000 | 985 | 0 |
| power cut while blocks are written | 5000 disks from 1000 runs | 4900 | 0 |
| power cut while a fork is rolled back | 5000 disks from 1000 runs | 4915 | 0 |

Not yet run: the same on ARM64.
