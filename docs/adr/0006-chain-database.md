# ADR 0006: The chain is stored in an embedded database

- Status: accepted
- Date: 2026-10-16
- Asked for by the maintainer in #202 on 2026-10-16: zero tolerance for a damaged chain on disk, an
  embedded database with atomic transactions after Monero's example instead of a file format of our
  own, a new dependency explicitly allowed, and the choice between LMDB (through lmdbjava) and
  SQLite (through sqlite-jdbc) made by the criteria below. The choice and everything that follows
  from it were made in that frame, by the measurements recorded here

## Context

Until 0.5.0 a chain on disk is one file holding the canonical encoding of every block, rewritten
whole for every block through a temporary file and an atomic rename (#202). #202 measured it: a
process killed while writing never left a damaged file (200 of 200 readable), but nothing was ever
synced to the disk, so a power cut can lose the rename or leave a file of zeros, and the bytes
written per block grow with the chain (664 KB per block at 301 blocks).

A node that is the only holder of a chain must not lose it to a power cut, and a node that is one of
many must at least never start from a damaged one. Monero keeps its chain in LMDB for exactly that:
every block is one transaction, and the database, not the program, guarantees that a transaction is
either on the disk whole or not at all.

## The comparison

Both candidates were resolved from Maven Central on 2026-10-16 in their latest versions,
`org.xerial:sqlite-jdbc:3.53.4.0` and `org.lmdbjava:lmdbjava:0.9.3`, and measured on this machine
(Linux x86_64, OpenJDK 25) with the two probes in `docs/measurements/0006-chain-database/`: each
opens a database, writes one block of 2,205 bytes in one transaction, commits with full sync and
closes.

### 1. Crash safety

| | SQLite | LMDB |
|---|---|---|
| process killed | every committed transaction survives, whatever the sync setting: the data is in the operating system's buffers, which a dead process does not take with it | the same |
| power cut, durable setting | `journal_mode=WAL` with `synchronous=FULL`: the write-ahead log is synced at every commit (`src/wal.c`, `walFrames`: "Padding and syncing only occur if this set of frames complete a transaction and if PRAGMA synchronous=FULL") | the default: "LMDB always flushes the OS buffers upon commit as well, unless the environment was opened with MDB_NOSYNC or in part MDB_NOMETASYNC" (`lmdb.h`, `mdb_env_sync`) |
| power cut, weaker settings | `synchronous=NORMAL` in WAL mode keeps the database consistent but "a transaction committed in WAL mode with synchronous=NORMAL might roll back following a power loss" (pragma documentation); `OFF` gives no promise | `MDB_NOMETASYNC` keeps integrity and may undo the last transaction; `MDB_NOSYNC` "means a system crash can corrupt the database" unless the file system preserves write order (`lmdb.h`, `mdb_env_open`) |
| recovery | the log carries checksums; a torn or partial last commit is ignored on the next open | copy-on-write with two meta pages; "eliminates the need of any special recovery procedures after a system crash" (`lmdb.h`) |
| macOS | `fsync` does not flush the drive's cache there; `PRAGMA fullfsync=ON` makes SQLite use `F_FULLFSYNC` | not configurable from lmdbjava |

Both give what #202 asks for, an all-or-nothing block across a power cut, when configured for it.
SQLite needs three settings to get there (`journal_mode=WAL`, `synchronous=FULL`, `fullfsync=ON`),
LMDB none. That is a point for LMDB, and the reason the settings are fixed in one place in the code
and checked by a test that reads them back from an open database.

### 2. How proven, how deeply tested

- **SQLite** is tested to 100 % branch coverage in its deployed configuration by its own harness,
  with crash and power-loss simulation, I/O error injection and fuzzing, and a test suite several
  hundred times the size of the library ("How SQLite Is Tested", sqlite.org/testing.html). The JDBC
  binding has 111 versions on Central since 3.5.7 (`maven-metadata.xml`) and released 3.53.4.0 on
  2026-08-26.
- **LMDB** carries OpenLDAP and Monero, which is as much field proof as a storage engine gets. The
  Java binding is a smaller project: 19 versions on Central since 0.0.1, the latest, 0.9.3, on
  2026-02-06, bundling LMDB 0.9.33 (`org.lmdbjava:native:0.9.33-5`).

The engines are both beyond doubt. The bindings are not equal, and the binding is what runs in our
process.

### 3. Licences (ADR 0001, rule 6), native libraries included

| what runs in the process | licence | read from |
|---|---|---|
| sqlite-jdbc, Java and JNI glue | Apache-2.0 | `pom.xml` and `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE` in the jar |
| parts of the driver from Zentus | BSD-2-Clause style, copyright David Crawshaw | `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus` in the jar |
| SQLite itself, compiled into the bundled native libraries | public domain | sqlite.org/copyright.html |
| lmdbjava | Apache-2.0 | `lmdbjava-0.9.3.pom` |
| LMDB native libraries | OpenLDAP Public License 2.8 | `native-0.9.33-5.pom` |
| jnr-ffi, jnr-constants, jnr-a64asm | Apache-2.0 | their poms |
| jffi and its native stubs | Apache-2.0 or LGPL-3.0, at the user's choice | `jffi-1.3.14.pom` |
| jnr-x86asm | MIT | its pom |
| ASM | BSD-3-Clause | `asm-9.7.1.pom` |

Both are admissible: every piece is permissive or dual-licensed with a permissive choice. SQLite
brings three licences in one artifact, LMDB nine artifacts in six licences. Apache-2.0 makes a
`NOTICE` file at the root of this repository a condition (rule 6); it carries the chosen
dependency's notices.

### 4. Platforms, with nothing for the user to install

Native libraries bundled in the jars, read with `unzip -l`:

| platform | sqlite-jdbc 3.53.4.0 | lmdbjava 0.9.3 (LMDB) | jffi 1.3.14 (needed by lmdbjava) |
|---|---|---|---|
| Linux x86_64 (glibc, musl) | yes, yes | yes, yes | yes |
| Linux aarch64, Raspberry Pi with a 64-bit OS | yes | yes | yes |
| Linux armv7 / armv6, Raspberry Pi with a 32-bit OS | yes, yes | **no** | arm only |
| macOS x86_64, aarch64 | yes, yes | yes, yes | yes (one universal library) |
| Windows x86_64 | yes | yes | yes |
| Windows aarch64 | yes | **no** | yes |

**On the JDK this project builds with, lmdbjava 0.9.3 does not start without extra flags.** The
probe fails in `ByteBufferProxy.<clinit>` with "Unable to make field long java.nio.Buffer.address
accessible: module java.base does not "opens java.nio" to unnamed module". With `--add-opens
java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED` it runs, and warns that
it calls `sun.misc.Unsafe::objectFieldOffset`, which "will be removed in a future release". A
storage layer that depends on opened JDK internals and on a method marked for removal is a storage
layer that one JDK update can take away. sqlite-jdbc needs no flag; it warns about
`System::load` until `--enable-native-access=ALL-UNNAMED` is given, which is the documented switch
for exactly this and which the start scripts pass.

### 5. Size, start time, memory on a small device

Measured with `python3 docs/measurements/0006-chain-database/measure.py`, five runs each, heap
limited to 64 MB (`-Xmx64m`), on Linux x86_64:

| | sqlite-jdbc | lmdbjava (with the flags above) |
|---|---|---|
| jars added to the distribution | 1 jar, 12,002,370 bytes, natives for every platform | 12 jars, 5,239,734 bytes |
| open, one transaction, close, inside the JVM | 208 to 256 ms | 305 to 392 ms |
| whole process, JVM start included | 0.27 to 0.31 s | 0.37 to 0.48 s |
| peak resident memory of the process | 62,840 to 64,356 KB | 74,740 to 74,992 KB |

Both fit a device with 1 GB of memory by a wide margin; the JVM dominates either way. LMDB reserves
address space for its map up front (1 GB in the probe), which costs no memory but has to be chosen
and grown by the program; SQLite grows its file by itself. SQLite is twice the download, because one
jar carries every platform.

## Decision

**The chain is stored in SQLite, through sqlite-jdbc.** LMDB is Monero's choice and an engine at
least as good, but the choice here is between bindings on JDK 25, and there the comparison is not
close: lmdbjava does not start on this JDK without opening its internals, leans on a method marked
for removal, and ships no LMDB for 32-bit Raspberry Pi systems or Windows on ARM. sqlite-jdbc runs
everywhere the criteria name, with the documented native-access switch, and SQLite's own testing
of crash and power-loss behaviour is the deepest of any embedded database. The cost, SQLite's three
settings for durability and a larger jar, is paid once in code.

How it is used:

- **The path stays the path.** `ChainFile` keeps its API; the file at the path the user gives is now
  an SQLite database with a table of blocks (height, hash, canonical encoding of the block) and a
  table of the transfers waiting for the next block. Every block keeps the one canonical encoding,
  so nothing about the chain's bytes changes, only where they are kept.
- **Settings:** `journal_mode=WAL`, `synchronous=FULL`, `fullfsync=ON` and
  `checkpoint_fullfsync=ON`, and a busy timeout so that a second program waits for a writer instead
  of failing; a write takes the write lock up front (`BEGIN IMMEDIATE`). A test reads the settings
  back from an open database. Every operation opens its connection and closes it again, so no
  connection outlives a change.
- **One transaction per change.** Writing a chain finds the highest block the database already holds
  with the same hash, deletes everything above it, and inserts what is new, in one transaction: a
  new block is one transaction, a rollback over a fork of any depth is one transaction, and the
  waiting transfers are written in the same one when the caller writes both.
- **Second line of defence.** Reading a chain checks the database's own integrity, that the heights
  run from zero without a gap, that every stored hash is the hash of its block and that every block
  names the hash of the one before. A node then replays the chain under the consensus rules, as
  before. A database that fails is not used and not repaired: the program stops with what failed
  and where, and leaves the file as it is.
- **Migration.** A chain file of an earlier version is copied into a new database next to it in one
  transaction, read back and compared block by block and transfer by transfer, and only then put in
  its place; the old file is kept as `<chain>.legacy` (and `<chain>.pending.legacy`). A crash at any
  point of this leaves either the old file in place, which migrates again, or a compared database,
  which is finished on the next start.
- **Licences:** `NOTICE` at the root names sqlite-jdbc (Apache-2.0), the Zentus notice and SQLite's
  public-domain dedication.

## What the tests showed

The acceptance is two tests that start a writer in a JVM of its own and kill it with SIGKILL at a
random moment, 25 times each in every build and a thousand times before a release:

- **`ChainCrashTest`**, `kill -9` while blocks are written one at a time, and while the chain is
  switched between two forks, which rolls back six or seven blocks each time. After every kill the database
  has to verify and hold the state of the last finished step or of the one begun, whole: the blocks
  and the waiting transfers together.
- **`PowerCutTest`**, the same with `src/test/native/iolog.c` preloaded, which records every write,
  truncation, sync and deletion. From the record the test builds five disks per run as a power cut
  could have left them: everything a file was synced after is there, everything after its last sync
  is there whole, torn at a sector boundary, or missing, each at random. A kill alone cannot show
  whether anything was synced, which is why this second test exists.

Both were written first. Against the file logic of 0.5.0, which wrote the chain and the waiting
transfers one after the other, `ChainCrashTest` was red in 2 of 10 and 3 of 10 runs. The counter-runs
against this implementation, each reverted afterwards:

| changed on purpose | `ChainCrashTest` | `PowerCutTest` |
|---|---|---|
| `synchronous=OFF` | green: a dead process loses nothing from the system's buffers | red: 24 of 50 and 7 of 50 disks broken, several "database disk image is malformed" |
| no `BEGIN`/`COMMIT` | red: 3 of 60 and 12 of 60 runs | red: 10 of 50 and 20 of 50 disks |
| `synchronous=NORMAL` | green | green |

`synchronous=NORMAL` passes because every operation closes its connection, and the last connection
to close a database checkpoints the log into it, with a sync. `FULL` stays: it is what makes a commit
durable by itself, without depending on how long a connection lives, and a later change that keeps a
connection open for a node's lifetime would otherwise lose the last commits to a power cut without
any test noticing.

The write load fell from 99,623,517 bytes and no sync to 7,411,060 bytes and 1,207 syncs for the same
300 blocks, about 25 KB and four syncs per block at every height
(`docs/measurements/0006-chain-database/README.md`).

What could not be run where this was written, and is handed on: the crash tests on ARM64, and the
migration of a chain synced from seed node 1 (`migrate-seed-chain.sh`), whose port is not reachable
from there.

## Consequences

- A chain written by this version cannot be read by 0.5.0 or earlier; the `.legacy` copy can, as it
  was at the moment of migration. Since #224, `lethenon export` writes the chain as it is now as a
  chain file of 0.5.0, with its waiting transfers, byte for byte what 0.5.0 writes; reading that file
  with this version migrates it again, to the same database row for row.
- Copying a chain means copying the database while no program has it open, or with SQLite's backup;
  copying the file alone while a node runs can miss the last transactions, which sit in
  `<chain>-wal`.
- `lethenon` depends on sqlite-jdbc at runtime, so does every program that uses `ChainFile`, the
  desktop plugin among them.
- The acceptance of this decision is the crash tests in #202, not this record: a process killed at
  random points of a block write and of a rollback, many times over, leaves a chain that holds the
  change whole or not at all, and the same tests fail without the transaction and without the sync.
