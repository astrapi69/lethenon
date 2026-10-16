# Join the testnet

This page takes you from nothing to a node on the lethenon test network, `lethenon-test-2`, without
building anything. The test network is where lethenon is tried out in the open. Its coins have no
value, its chain may be started anew, and mining on it is open to everybody. The main chain does not
exist yet; it starts with 1.0.0 ([ADR 0005](adr/0005-main-chain-genesis.md)).

A node keeps a copy of the chain in one file, checks every block it receives, and passes blocks and
transfers on to its peers. It asks nobody for a balance and tells nobody one.

## What you need

- **A 64-bit Linux machine.** Tested on ARM64 (see [How this page was tested](#how-this-page-was-tested));
  x86-64 is what the seed node runs on. The distribution also holds `bin\lethenon.bat` for Windows,
  which has not been tried.
- **Java 25**, a runtime is enough. On Debian 13 and on systems built on it:

  ```sh
  sudo apt install openjdk-25-jre-headless
  java -version          # must say 25
  ```

  Elsewhere, any Java 25 runtime will do, for example Eclipse Temurin. An older Java stops at the
  first start with `UnsupportedClassVersionError`.
- **gpg** and **sha256sum**, to check the download (`sudo apt install gnupg`; `sha256sum` is part of
  coreutils).
- **A little memory and disk.** A node that does not mine runs with `-Xmx64m`: seed node 1 does, and
  measured between 62 and 99 MB. A mining node has run with `-Xmx128m`. With 0.5.0 the chain file
  was 77 776 bytes at height 365, about 213 bytes per block without transfers. Since 0.6.0 it is a
  database, about 1.7 times that: 366 blocks without transfers measured 53 743 bytes as a 0.5.0 chain
  file and 90 112 bytes as a database (`docs/measurements/0006-chain-database/ChainSize.java`).
- **Outgoing TCP to port 18480**, or Tor (section 3). Incoming connections are optional.

## 1. Download and check

The command line comes as `lethenon-X.Y.Z.tar.gz` and `lethenon-X.Y.Z.zip` on the
[GitHub release](https://github.com/astrapi69/lethenon/releases) of every version from 0.5.0 on, each
with a signature (`.asc`) and checksums (`.sha256`, `.sha512`):

```sh
VERSION=0.6.0
BASE=https://github.com/astrapi69/lethenon/releases/download/RELEASE-$VERSION
curl -LO "$BASE/lethenon-$VERSION.tar.gz"
curl -LO "$BASE/lethenon-$VERSION.tar.gz.asc"
curl -LO "$BASE/lethenon-$VERSION.tar.gz.sha256"

sha256sum -c lethenon-$VERSION.tar.gz.sha256
gpg --keyserver hkps://keys.openpgp.org --recv-keys 5B2FA6B11E298BC09287F423D8C403518C49CA75
gpg --verify lethenon-$VERSION.tar.gz.asc lethenon-$VERSION.tar.gz
```

`sha256sum` answers `OK`, and `gpg` answers `Good signature`. gpg also prints a `Primary key
fingerprint` and a `Subkey fingerprint`; both must be the ones in the README's section
[Verifying the release signatures](../README.md#verifying-the-release-signatures):

```text
Primary key fingerprint: 5B2F A6B1 1E29 8BC0 9287  F423 D8C4 0351 8C49 CA75
     Subkey fingerprint: 6D67 F844 2A6F CC96 BD7C  1B5E 9FCF 7C97 10E2 BD8D
```

The warning that the key "is not certified with a trusted signature" only means that you have not
marked the key as trusted in your own keyring; the fingerprints are what to compare. gpg speaks the
language of your system, so the words may differ: on a German system it says `Korrekte Signatur`,
`Haupt-Fingerabdruck` and `Unter-Fingerabdruck`. The fingerprints are the same in every language. If a check
fails, do not run the file.

To build it yourself instead: `git clone https://github.com/astrapi69/lethenon`, `git checkout
RELEASE-$VERSION`, then `./gradlew installDist` with Java 25. The result in `build/install/lethenon` is
what the archive holds.

Unpack:

```sh
tar xzf lethenon-$VERSION.tar.gz
cd lethenon-$VERSION
bin/lethenon --help
```

## 2. First start

```sh
mkdir -p ~/lethenon
bin/lethenon node --chain ~/lethenon/testnet.lethenon --listen 18480
```

The chain file does not exist yet. The node then takes the genesis block from one of the test
network's seed nodes, which it knows without being told: given no `--peer`, it dials them by itself.
Then it fetches the rest of the chain and checks every block. Its first lines say so:

```text
took the genesis block from the peer at 185.112.144.29:18480
node on lethenon-test-2, listening on 0.0.0.0 port 18480, 1 default seed node(s) of lethenon-test-2, connecting direct
```

Once a minute it prints a status line with its height, the hash of its tip, its peers, the waiting
transfers and how many connections it refused. The height should reach the seed node's height
within a minute or two (on the trial run below it had the whole chain, 365 blocks, at its first status
line after 15 seconds), and from then on grow by about one block every two minutes, as long as somebody
mines. Ctrl-C stops the
node; it writes the chain file and prints a stop line. The next start continues from the file.

`--listen 18480` also accepts connections from other nodes. Nothing needs them: a node behind a home
router without a port forwarding works just the same, it only does not receive connections. With
`--listen 0` it takes any free port. Such a node usually counts one refusal: the seed node passes the
node's own public address back to it, the node tries it, and the stop line lists it as
`<your address>:<port>, learnt from a peer, was not connected: Connect timed out`. That is harmless.

To bring a chain file up to date once and stop, without running a node:

```sh
bin/lethenon sync --chain ~/lethenon/testnet.lethenon --peer 185.112.144.29:18480
```

`--no-default-peers` makes a node dial nobody but the peers given with `--peer`. It is what you use for
a test chain of your own (`--no-default-peers --mine` on an empty file). A node whose chain file holds
a chain of its own never dials the seed nodes anyway.

### Coming from 0.5.0

A chain file that 0.5.0 or earlier wrote is migrated the first time 0.6.0 uses it. 0.6.0 copies it into a
database, compares it block by block, and keeps the old file beside it as `testnet.lethenon.legacy`.
**After that, 0.5.0 cannot read the chain any more.** It says only "this build reads block encoding version 2
and the bytes carry version 83". To be able to go back, copy the chain file somewhere else before the first
start of 0.6.0. Once the new version runs and its height grows, the `.legacy` file can go. Both of the
network's own nodes made this step on 2026-10-16, and each migrated 733 blocks identically.

## 3. Optional: only through Tor

With Tor, the seed node and its peers do not see your IP address. Install Tor and give its SOCKS port
to the node:

```sh
sudo apt install tor
bin/lethenon node --chain ~/lethenon/testnet.lethenon --listen 0 --proxy 127.0.0.1:9050
```

Then every outgoing connection goes through Tor. The node dials the seed node's onion address instead
of its IP address, and listens on 127.0.0.1 only. The first connection through Tor can take a minute;
on the trial run below the node had the whole chain at its first status line, 30 seconds after the
start, with Tor bootstrapped 7 seconds before.
[docs/tor.md](tor.md) describes the rest of the Tor transport: the anonymity zone for your own
transfers and an onion service of your own.

## 4. Optional: mining with a test wallet

A wallet is a file that only its password opens. Its 24 words restore it; write them down if you want
to keep what the wallet mines, even though test coins are worth nothing. The password is never an
argument, it is the first line of standard input:

```sh
bin/lethenon wallet create --wallet ~/lethenon/test.wallet
```

Type the password and press Enter. The command prints the account and the 24 words. Then mine:

```sh
bin/lethenon node --chain ~/lethenon/testnet.lethenon --listen 0 --mine --wallet ~/lethenon/test.wallet
```

and type the password again. Mining takes one processor core, all of it, for as long as the node
runs. On a machine you also work on, start it with the lowest priority, for example with
`nice -n 19` in front. `balance` shows what the wallet holds, computed from the chain file alone:

```sh
bin/lethenon balance --chain ~/lethenon/testnet.lethenon --wallet ~/lethenon/test.wallet
```

On an empty chain file, a node with `--mine` first takes the genesis block from a seed node, so it
joins the test network instead of starting a chain of its own.

## 5. Keeping it running

For a node that runs all the time, a systemd service under a user of its own is enough. The seed node
runs like this, except that a seed node also gives `--no-default-peers`: it is one of the seed nodes
it would otherwise dial. Your node leaves that option out, so that it finds the network:

```ini
# /etc/systemd/system/lethenon-node.service
[Unit]
Description=lethenon node (test network)
After=network-online.target
Wants=network-online.target

[Service]
User=lethenon
Group=lethenon
Environment=LETHENON_OPTS=-Xmx64m
ExecStart=/opt/lethenon/bin/lethenon node --chain /var/lib/lethenon/chain.lethenon --listen 18480
Restart=on-failure
RestartSec=10
MemoryMax=256M
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/lethenon
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```sh
sudo useradd --system --home /var/lib/lethenon --shell /usr/sbin/nologin lethenon
sudo mkdir -p /var/lib/lethenon && sudo chown lethenon:lethenon /var/lib/lethenon
sudo tar xzf lethenon-$VERSION.tar.gz -C /opt && sudo ln -sfn /opt/lethenon-$VERSION /opt/lethenon
sudo systemctl daemon-reload && sudo systemctl enable --now lethenon-node
journalctl -u lethenon-node -f        # the status lines
```

Java ends with status 143 when systemd stops it. systemd then shows the service as `failed` until the
next start; that is expected.

## 6. Optional: a test chain of your own

A chain nobody else mines on, for trying things out without waiting two minutes for a block. It runs
under the test chain's rules and identifier, but it is a chain of its own: it never connects to the
test network, and nothing on it reaches the seed nodes.

With the command line alone, one block per command:

```sh
bin/lethenon mine --testnet --chain ~/lethenon/own.lethenon --wallet ~/lethenon/test.wallet
bin/lethenon mine --chain ~/lethenon/own.lethenon --wallet ~/lethenon/test.wallet
```

The first `mine` starts the chain with its genesis block, whose reward goes to the burn account
(`mined block 0: ... paying its reward to the burn account`); each further `mine` adds a block that
pays the wallet. `--testnet` is needed only for the first one, which chooses the chain.

As a node that mines on its own:

```sh
bin/lethenon node --chain ~/lethenon/own.lethenon --wallet ~/lethenon/test.wallet --mine \
    --no-default-peers --listen 18481
```

On an empty chain file it prints `mined the genesis block of lethenon-test-2` instead of taking the
genesis block from a seed node; without `--no-default-peers` it would join the test network. Run
with `--for 20` on Linux x86_64 on 2026-10-16, it mined 194 blocks in 20 seconds. A second machine
joins this chain with an empty chain file, `--peer <this machine>:18481` and `--no-default-peers`:
it takes the genesis block from that peer.

## Raspberry Pi 4 and 5

A Raspberry Pi 4 or 5 runs a node, and mines on it, with a **64-bit** operating system:

- **Operating system:** 64-bit Raspberry Pi OS, or any other 64-bit Debian. `uname -m` must print
  `aarch64`. On a 32-bit system (`armv7l`) this page does not apply.
- **Java:** `cat /etc/debian_version` shows which Debian the system is built on. On 13, Java 25 comes
  from the system, as in [What you need](#what-you-need). On 12 (bookworm) it does not; take a Java 25
  runtime for `aarch64` from elsewhere, for example Eclipse Temurin, or move to a Raspberry Pi OS
  built on Debian 13.
- **An SSD instead of the SD card, for a node that runs all the time.** Since 0.6.0 the node writes
  each block as one database transaction and syncs it to the disk, so that neither a crash nor a power
  cut can damage the chain ([ADR 0006](adr/0006-chain-database.md)). That is about 25 KB and four
  syncs per block of ten transfers, the same at every height (`docs/measurements/0006-chain-database/`),
  about every two minutes, day and night. SD cards are not made for that kind of constant writing and
  syncing and wear out under it. Put at least `~/lethenon` (or `/var/lib/lethenon`) on an SSD:
  a USB 3 SSD on a Pi 4 or 5, or an NVMe SSD through an M.2 HAT on a Pi 5. Booting from the SSD is
  better still. For trying it out for an afternoon, the SD card is fine.
- **Memory:** `-Xmx64m` is enough for a node that does not mine, so 1 GB of RAM is plenty. A mining
  node has run with `-Xmx128m`.
- **Crash safety:** the chain of 0.6.0 was killed mid-write and cut off mid-write in a simulation on ARM64, a
  thousand times per test, against the published 0.6.0, with nothing broken
  (`docs/measurements/0006-chain-database/README.md`). Not on a Pi itself, not on an SD card.
- **Mining** keeps one of the four cores at full load for as long as it runs. Give the Pi a heat sink
  or the official case fan. Without one, the Pi throttles its clock and mines more slowly.
- Everything else is as above: download and check, first start, Tor, mining, the systemd service.
  The distribution is pure Java and the same for every processor.

## How this page was tested

Once, on 2026-10-15, on a real ARM64 machine, with the commands of this page
([#198](https://github.com/astrapi69/lethenon/issues/198)):

- **The machine:** a GitHub-hosted `ubuntu-24.04-arm` runner, 4 cores (`CPU part 0xd49`), with the
  steps inside a `debian:trixie` container. `uname -m` printed `aarch64`, `/etc/debian_version`
  `13.7`.
- **What you need:** `apt install openjdk-25-jre-headless` gave `25.0.4.1+1-1~deb13u1` for `arm64`.
- **1. Download and check:** no release carried the archives yet, so the rehearsal of the
  distribution workflow stood in for the download (#196): `lethenon-0.5.0-SNAPSHOT.tar.gz` from
  develop. `sha256sum -c` printed `OK`. `gpg` printed `Good signature`, with the primary key and
  subkey fingerprints above. On 2026-10-16, after the release, section 1 ran word for word against
  the published `RELEASE-0.5.0`: logged out of GitHub, in an empty directory and an empty keyring,
  on x86-64. `sha256sum -c` printed `lethenon-0.5.0.tar.gz: OK`, gpg (in German) gave a good
  signature with both fingerprints above, and `bin/lethenon --help` answered. The same on the same
  day against `RELEASE-0.6.0`: `lethenon-0.6.0.tar.gz: OK`, `Good signature` with both fingerprints,
  and `bin/lethenon --version` printed `lethenon 0.6.0`.
- **2. First start:** with an empty chain file and no `--peer`, the node took the genesis block from
  seed node 1 (`took the genesis block from the peer at 185.112.144.29:18480`), had height 364 at
  its first status line, and followed the seed to 365. It stopped with one refusal, its own public
  address, as described in section 2.
- **3. Only through Tor:** Tor 0.4.9.14 from Debian, bootstrapped in 7 seconds. The node took the
  genesis block from the seed node's onion address and reached height 367.
- **4. Mining:** `wallet create`, then `node --mine` under `nice -n 19` for six minutes, which mined
  3 blocks (height 367 to 370). `balance` replayed 371 blocks and showed what the wallet held.

**Not run on a Raspberry Pi itself.** The runner is an Arm server core, not the Cortex-A72 of a Pi 4
or the Cortex-A76 of a Pi 5. It ran neither Raspberry Pi OS nor an SD card, and it does not throttle
when it gets hot. Section 5, the systemd service, was not run on the trial machine either; it is the
configuration of seed node 1. Windows was not tried.
