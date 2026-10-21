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
could not run where this was written, because the seed node's port is not reachable from there.

It ran on 2026-10-16 at 13:59 (local time, UTC+2) on Linux x86_64. The chain was synced by the signed
0.5.0 from the GitHub release; the migration and the comparison were made with develop `c0c65c2`:

```sh
docs/measurements/0006-chain-database/migrate-seed-chain.sh \
    <lethenon-0.5.0>/bin/lethenon build/install/lethenon/lib <work dir>
```

```
chain lethenon-test-2 from the node at 185.112.144.29:18480: took 625 block(s), now at height 624
625 blocks, 132914 bytes, compared block by block: identical; migration and verified read took 0,20 s
tip 00000008ff34bd9cd6038acf4e00c73077826642263e75613fe51916ce75ea2d at height 624
replayed 625 blocks, applied 0 transfers, checked 0 signatures; ...
old file kept: true
database: 184320 bytes
```

Three checks beside it:
- The `.legacy` copy is byte for byte the synced 0.5.0 file (`cmp`), and 0.5.0 still reads it: its
  genesis hash is the network's, `0093b8db...1d6e`.
- The database starts with `SQLite format 3`.
- 0.5.0 refuses the database and leaves it unchanged (sha256 before and after `91d7f555...`), with
  `this build reads block encoding version 2 and the bytes carry version 83`. 83 is the `S` of the
  SQLite header. That is all a user of 0.5.0 learns about a migrated chain, so the release notes of
  0.6.0 have to say it.

The seed node's chain carries no transfers. The transfers are covered by the 301-block chain above,
with 2,990 transfers.

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

On ARM64, 2026-10-16 12:25 to 12:47 UTC, on c0c65c2 (develop after #210), a GitHub-hosted
`ubuntu-24.04-arm` runner: aarch64, 4 cores (`CPU part 0xd49`), 15.9 GB, Ubuntu 24.04.5, Temurin
25.0.4.1. It ran from a throwaway branch with a workflow that ran once and was deleted. 4 tests, 0
failures, 21 min 58 s:

| test | runs | killed inside a write | broken |
|---|---|---|---|
| kill -9 while blocks are written | 1000 | 977 | 0 |
| kill -9 while a fork is rolled back | 1000 | 992 | 0 |
| power cut while blocks are written | 5000 disks from 1000 runs | 4910 | 0 |
| power cut while a fork is rolled back | 5000 disks from 1000 runs | 4955 | 0 |

The counts come from the tests' own output in the XML of that step. `PowerCutTest` was not
skipped: the runner's C compiler built `src/test/native/iolog.c` for aarch64, and the writer ran with
it preloaded (0 skipped, 5000 disks per test). sqlite-jdbc took its own `Linux/aarch64/libsqlitejdbc.so`.
The whole suite ran on the same machine afterwards: 84 classes, 829 tests, 0 failures. A first run of
the same workflow passed as well (22 min 26 s); its counts were lost, because the whole suite
overwrote that step's XML.

**Against the published 0.6.0, on ARM64**, 2026-10-16 16:03 to 16:27 UTC, after the release:
- The machine: the same kind of runner (aarch64, `CPU part 0xd49`).
- The test classes were compiled at `RELEASE-0.6.0`.
- The classes under test came from the `lib/` of `lethenon-0.6.0.tar.gz` on the GitHub release:
  - signature by the subkey checked (`VALIDSIG 6D67...BD8D`), and `sha256sum -c` passed;
  - its jar byte for byte the one on Maven Central (`cmp`, sha1 `1851d54e...`);
  - a probe showed `ChainDatabase` and `ChainFile` loaded from that jar.
- The tests and the writer process they kill ran with that class path, through the JUnit console launcher.

4 tests, 0 failures, 0 skipped, 22 min 42 s:

| test | runs | killed inside a write | broken |
|---|---|---|---|
| kill -9 while blocks are written | 1000 | 973 | 0 |
| kill -9 while a fork is rolled back | 1000 | 989 | 0 |
| power cut while blocks are written | 5000 disks from 1000 runs | 4880 | 0 |
| power cut while a fork is rolled back | 5000 disks from 1000 runs | 4980 | 0 |

A first attempt started the launcher with `-jar` and `--class-path`. That gave every writer process
only the launcher's jar, so each writer ended before it could be killed: 0 killed inside a write in
4 x 1000 runs. The tests refused that themselves ("a test that rarely kills inside a write proves
little"). It said nothing about 0.6.0, and it was run again with the class path in
`java.class.path`.

**Against the published 0.6.1, on ARM64**, 2026-10-21 12:36 to 12:59 UTC, after the release, with the
same workflow pointed at `RELEASE-0.6.1` (run 39600825517, from a throwaway branch, deleted
afterwards):
- The machine: the same kind of runner (aarch64, 4 cores, `CPU part 0xd49`, Temurin 25.0.4.1).
- The test classes were compiled at `RELEASE-0.6.1`.
- The classes under test came from the `lib/` of `lethenon-0.6.1.tar.gz` on the GitHub release:
  - signature by the subkey checked (`VALIDSIG 6D67...BD8D`), and `sha256sum -c` passed;
  - its jar byte for byte the one on Maven Central (`cmp`, sha1 `b0b42146...`);
  - a probe showed `ChainDatabase` and `ChainFile` loaded from that jar.

4 tests, 0 failures, 0 skipped, 23 min 3 s:

| test | runs | killed inside a write | broken |
|---|---|---|---|
| kill -9 while blocks are written | 1000 | 965 | 0 |
| kill -9 while a fork is rolled back | 1000 | 990 | 0 |
| power cut while blocks are written | 5000 disks from 1000 runs | 4910 | 0 |
| power cut while a fork is rolled back | 5000 disks from 1000 runs | 4925 | 0 |

What this does not cover: a Raspberry Pi itself, an SD card, and a real power cut. `PowerCutTest`
builds the disks a cut could leave from the recorded writes and syncs, after the model of SQLite's
own crash tests; no disk lost its power.
