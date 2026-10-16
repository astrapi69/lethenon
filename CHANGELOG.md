## Change log
----------------------

Version 0.6.0 (unreleased)
-------------

CHANGED:

- `docs/testnet.md` points at the published 0.5.0: its download and check, section 1, ran word for
  word against the GitHub release `RELEASE-0.5.0`, logged out, in an empty directory and keyring, and
  the page says so. To build it yourself, it names the release tag to check out. It also says that gpg
  answers in the system's language, "Korrekte Signatur" on a German system, and that the fingerprints
  are what count
- `docs/testnet.md` has a section 6, a test chain of your own: started by `mine --testnet` one block
  at a time, or by `node --mine --no-default-peers` on an empty file, which mines its own genesis
  block instead of taking the test network's from a seed node. Both ran word for word, and a second
  node joined such a chain with `--peer` and `--no-default-peers`

Version 0.5.0
-------------

**0.5.0 is the release somebody else can run.** The command line comes as a download on the GitHub
release, signed by the signing subkey and with its checksums (#196). The test network's seed nodes are
in the code, so a node finds the network without being given an address (#197). `docs/testnet.md` takes
a newcomer from the download to a running node, with a section for the Raspberry Pi 4 and 5, and has
been run through once on ARM64 (#198). Three fixes in the network come with it:
- a configured peer is dialled again (#128);
- discovery keeps an address that only this node's own limit refused (#126);
- no node learns or passes on a loopback or private address (#194).

A running node prints a status line (#149). The main chain still starts only with 1.0.0 (ADR 0005).

FORMAT:

- **No format changes.** The chain encoding, the wallet file, `HELLO` and the peer list are the code of
  0.4.0, and both speak protocol version 3. What 0.5.0 writes, 0.4.0 reads, and the other way round.
- **Measured on the release, in both directions, on chains carrying real transfers.**
  - The command line of 0.4.0 was built from its tag, with its lethenon jar replaced by the one on
    Maven Central and checked against Central's `.sha1` (`7e5a6139c349...`). This one was built from
    the release commit with the full gate (`./gradlew clean build`: 82 classes, 808 tests, 0
    failures).
  - Each version wrote a test chain of four blocks: an Ed25519 transfer of 12.5 LETH, 20 LETH to a
    published address and its sweep, and 100 LETH to the holder's ML-DSA-65 account with 5 LETH from
    it. Both versions compute the recipient's 17.5 LETH and the address owner's 20 LETH on both chains,
    each with the other version's wallets.
  - Reading leaves the files unchanged. Each version then signed a transfer from the other version's
    wallet onto a copy of the other's chain and mined block 4, and the writer replayed the result: 5
    blocks, 6 transfers, the new 7 LETH where they were sent.
  - 0.4.0 replays the test network's chain, which 0.5.0 development builds wrote since 2026-10-15:
    424 blocks, the same result as 0.5.0.
  - In a network namespace of their own (`unshare -rn`, loopback only), `sync` from a node of the
    other version takes the whole chain into a file that is byte for byte the served one, in both
    directions. A miner of one version with a node of the other as its peer: the peer took every
    block, 216 blocks from a 0.5.0 miner into 0.4.0, 211 from a 0.4.0 miner into 0.5.0.
- Commands: `bash docs/launch/compatibility-0.5.0/build.sh <release commit>`, then
  `bash docs/launch/compatibility-0.5.0/measure.sh [<copy of a lethenon-test-2 chain>]`, which writes
  one line per command to `results.tsv`.
- **A behaviour that changed:** `node --mine` on an empty chain file with peers, given or default, takes
  the genesis block from a peer instead of mining a new one (#197). A test chain of one's own is
  started with `--no-default-peers --mine`, or with `mine --testnet`.

CHANGED:

- `docs/launch/infrastructure.md`, section 6: after every change to the SSH configuration, the values
  in effect are read with `sshd -T`, not only checked with `sshd -t` (#191). On the first seed node
  a hardening drop-in was valid and reloaded and still not read, because the provider's image has no
  `Include` line for `sshd_config.d`

- `docs/launch/infrastructure.md` says where the seed nodes stand (#188): three at three providers in
  three countries under three legal orders - 1984 Hosting in Iceland, the maintainer's favourite,
  Exoscale in Switzerland and TransIP in the Netherlands - plus the maintainer's node at home,
  reachable only through Tor. A table of providers in Iceland, Switzerland, the Netherlands, Finland
  and Germany gives for each the legal order, what its own terms say about a node that mines nothing,
  about mining and about Tor, and the price of the smallest plan that fits the measured minimum, each
  quoted from the provider's pages of 2026-10-13. Left out, by name and with the quote, are providers
  that advertise ignoring or evading lawful orders, anonymous customers without an identity check,
  "offshore" as protection from the law, or immunity to takedown requests; data minimisation alone
  is no reason. 1984 Hosting's support, asked in writing before an order, answered that a VPS has no
  restriction beyond spam, its terms and Icelandic law, that an onion service is "fine by us", that a
  billing address outside Iceland pays no VAT, that IPv4 is included and that it can be paid monthly

- the documents say what lethenon hides and what it does not, wherever they speak of privacy or
  surveillance (#167). The README's opening, the specification's first section and the messaging
  guide now name what lethenon refuses - no balance asked of anybody, a hidden recipient for a
  payment to a published address, Tor on the test network - next to what it shows: the sender and
  the amount of every transfer, the recipient of a transfer to an account and of a block's reward.
  Sentences that implied more were rephrased: "the chain is private", "the opposite of a permanent
  record", "unlinkability" as a milestone without saying whose, and "harvest now, decrypt later",
  which a signature does not answer. The number 1984 is explained: George Orwell's novel
  *Nineteen Eighty-Four*, why the supply is 1,984 times a million, why the reward is a millionth of
  the pool, and why the derivation path `m/1984'` never changes (specification, section 2). The
  messaging guide also says how lethenon and its maintainer's books may refer to each other

ADDED:

- `docs/testnet.md`, "Join the testnet" (#198), linked from the README: requirements and Java 25,
  downloading the distribution and checking its signature and checksum, the first start with an empty
  chain file, which takes the chain from the seed nodes in the code (#197), optionally Tor and mining
  with a test wallet, a systemd service as on seed node 1, and a section for the Raspberry Pi 4 and 5
  with a 64-bit operating system and an SSD instead of the SD card for a node that runs all the time.
  Run through once on a GitHub ARM64 runner in Debian 13 with the page's commands, every step green;
  the page says what that covered and that it was not run on a Raspberry Pi itself
- the test network's seed nodes are in the code (#197). A node started without `--peer` dials them:
  seed node 1's IP address, or with `--proxy` its onion address, so that a node behind Tor stays
  inside Tor. `TestNetworkSeeds` names them with the genesis hash of the chain they serve, and a node
  dials them only for an empty chain file or for that chain; a node on a test chain of its own does
  not. `--no-default-peers` turns them off. With peers, given or default, `node --mine` on an empty
  chain file takes the genesis from a peer instead of mining a new one, so that a newcomer joins the
  test network rather than starting a chain the seed would refuse; a test chain of one's own is
  started with `--no-default-peers --mine`. The start line says where the peers came from. Measured
  in `TestNetworkSeedsTest`, `DefaultPeersTest` and `TestNetworkSeedsOnTheCommandLineTest`, which
  reach a seed only through the test SOCKS5 server; a watcher on `ss` saw no connection to the real
  seed during the whole suite
- the command line distribution is a release artifact (#196). `./gradlew releaseDistribution` builds
  what `installDist` builds as `lethenon-X.Y.Z.zip` and, compressed now, `lethenon-X.Y.Z.tar.gz`. Next
  to each it writes a SHA-256 and a SHA-512 file in the format `sha256sum -c` reads, the family's
  convention, and an ASCII-armoured signature by the key that signs the Maven publication, which in CI
  is the signing subkey. A release version without a signing key is refused before anything is built;
  a plain `./gradlew build` signs nothing. The new workflow `distribution.yml` runs it on a
  `RELEASE-*` tag. It verifies every signature against the key fetched by its fingerprint from a
  keyserver, and every checksum, and attaches the eight files to the tag's GitHub release, created as
  a draft when there is none yet. Run by hand, it is the rehearsal and keeps the files as a workflow
  artifact. Measured locally without a key: the four checksum files pass `sha256sum -c` and
  `sha512sum -c`, and `-PprojectVersion=0.5.0` ends with "Refusing to build the release distribution
  of 0.5.0 unsigned"
- a running node prints a status line, once a minute by default (#149): its height, the hash of its
  tip, the connected peers with the outgoing and incoming connections it holds, the anonymity
  zone's peers where there is a zone, the waiting transfers, the blocks it mined, and how many
  connections were refused since the last line. A seed node runs for months without stopping, and
  until now said how it stood only in its stop line. The refusals are counted, not listed: a remote
  address is personal data, and a line repeated every minute would leave the journal's retention to
  decide how long it is kept; the stop report still names them, once. `--status-every <seconds>`
  sets the interval, 0 turns the line off. `Node.outgoingConnections()` and
  `Node.incomingConnections()` give the two counts to a library caller. Measured in
  `NodeStatusLineTest`, with a timeout, because a wait that comes out as 0 milliseconds would let a
  node's `--for` run forever

FIXED:

- a node neither learns nor passes on loopback and private addresses, whatever its configuration
  (#194), after Monero's `sanitize_peerlist` and `is_host_allowed`. A seed node whose onion service
  forwards to its ordinary port sees every connection through Tor arrive from 127.0.0.1; it confirmed
  `127.0.0.1:<port>` as the peer's address and passed it on in answer to `GET_PEERS`. The node behind
  Tor dialled its own address back through Tor, and every other node that asked would have dialled a
  port on its own machine. The address book now refuses, whether heard of or confirmed, IP literals
  in the loopback, unspecified, private (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fc00::/7) and
  link-local ranges, and the name `localhost`. A host name is never looked up, because the lookup
  would leave the node outside Tor. A peer given with `--peer` is still dialled. Unlike Monero's
  `--allow-local-ip` there is no option: only the tests, which run several nodes on 127.0.0.1, keep
  local addresses, through a package-private switch. Measured in `LocalAddressesTest` and
  `PeerExchangeTest`; with the filter switched off, the 21 local cases and the two node tests fail
  and the 16 reachable ones pass
- the README called proof of pun "CPU-friendly on purpose" and gave it Monero's reasoning for RandomX,
  "designed to make the use of mining-specific hardware unfeasible" (#186). Proof of pun is SHA-256
  proof of work with the pun and a counter in place of a nonce; SHA-256 is not memory-hard and resists
  neither GPUs nor mining-specific hardware, which RandomX does by design. The comparison is gone, and
  the row says what the work function is. The same sentence said the "memo hash" had to carry a prefix;
  it is the hash of the whole block, with a number of leading zero bits, as the specification says
- a node keeps the most recent 100 refusals instead of every one (#183). The list grew with every
  refused connection, without a bound, and kept every refused remote address - personal data - for
  the node's whole run; a seed node runs for months. A running count is kept beside it,
  `Node.refusalCount()`, which the status line of #149 now counts with, so that it does not stop
  counting at the hundredth refusal. `Node.refusals()` returns the kept ones, oldest first, and the
  stop report adds how many earlier ones were not kept. Measured in `RefusalsTest`
- the library no longer names a command line option in its refusals (#135). An onion address
  reached without a proxy was refused with "give its SOCKS proxy with --proxy", which a caller of
  the library - mystic-crypt-ui's plugin, through `Sync.once`, `Handover.send` or
  `Bootstrap.genesisFrom` - has no option for. The library now says that the address is reached
  only through Tor's SOCKS proxy and the connection has none; `sync` and `node` refuse an onion
  `--peer` without a proxy themselves, before anything is dialled, and name their options:
  `--proxy`, and for `node` also `--tx-proxy`. A node used to start and then fail to dial such a
  peer; it now refuses to start. Measured in `ProxyTest` and `ProxyOnTheCommandLineTest`
- a configured peer is dialled again (#128). A peer given with `--peer` that could not be reached,
  or whose connection ended, used to be gone for the rest of the run; with `--no-discovery`, or in
  the anonymity zone before an onion address was learnt, that could be every peer a node had. It is
  now dialled again while it is not connected, clearnet and zone alike, as Monero keeps
  reconnecting to its configured peers: after 5 s, then after a pause that doubles with every
  failed attempt, up to 5 minutes. The first failure is recorded among the refusals, once, not on
  every attempt; a connection that ends after its handshake is dialled again after 5 s. The first
  round still dials in the order given, so a full limit refuses the same peers as before
- a node stopped with Ctrl-C or SIGTERM prints its stop line and its refusals, as a stop through
  `--for` does (#129). Both end the JVM through its shutdown sequence, which skipped the report: the
  stop line, which peers were reached and what waits, and why a peer was not connected. A shutdown
  hook now ends the node's wait and holds the JVM's exit, at most 5 s, until the report is printed;
  it is printed once, whichever stop comes first. Measured in `NodeShutdownTest`: a node in a JVM of
  its own, `Process.destroy()` (SIGTERM), then the stop line on standard output and the refusal of
  its unreachable `--peer` on standard error
- peer exchange keeps an address that this node did not dial only because it was full (#126). When
  its own limit was reached between the check for room and the dial - 12 outgoing connections, or
  the anonymity zone's maximum - the refusal was taken for a failure of the address, which was
  forgotten. The limit refusals now have a type of their own, `NoRoom`, a kind of `IOException`
  with the same message, and peer exchange keeps such an address in its book to dial when there is
  room; it records the refusal. An address that cannot be reached is still forgotten. Measured in
  `DiscoveryTest` (the address kept for both limits, forgotten when unreachable), `LimitsTest` and
  `AnonymityZoneTest` (each limit throws `NoRoom`)
- a configured peer keeps its pause when only this node was full (#177). An attempt this node
  refused for its own limit counted as a failed attempt of the peer, so its pause doubled, up to 5
  minutes, and after a slot freed up the next attempt could be minutes away. A `NoRoom` now leaves
  the pause as it was, and the peer is dialled again on the next round of the redial loop; the
  refusal is recorded once, saying "its pause unchanged". Real unreachability doubles the pause as
  before. Measured in `RedialTest`, where the pauses before each next attempt are read from the
  redial loop's own view: unreachable three times gives 100, 200 and 400 ms; unreachable twice,
  then full, then unreachable gives 100, 200, the next round, and 400 ms
- the wipes of a wallet's secret are asserted (#43). `WalletFile` overwrites the entropy and the
  content it holds while writing, and the content it decrypts while reading, but no test noticed
  when those wipes went: with the three `SecretBuffers.wipe` calls commented out, the whole suite
  stayed green. Four tests now hold the very buffers, through a package-visible seam, and assert
  that each held a secret when it was handed out and is zero-filled after the call returns: for
  writing, for reading both file layouts, and for content refused as not a wallet. The same
  counter-run now fails exactly those four


KNOWN AND NOT FIXED:

- `lethenon --version` prints "lethenon 0.1" (#193); the version is in the name of the archive and its
  `lib/lethenon-X.Y.Z.jar`
- the chain file is written anew, whole, after every block, without `fsync` (#202). A killed process
  leaves the old file or the new one, never half of one: in #202, 300 runs killed with SIGKILL or
  SIGTERM while writing all left a readable file. A power cut without `fsync` is not covered. The
  writes grow with the chain; `docs/testnet.md` advises an SSD for a node that runs all the time
- `node --mine` computes on one thread (#201)

Version 0.4.0
-------------

**0.4.0 cannot start a main chain.** It carries the main chain's rules - the identifier
`lethenon-2`, the declining reward with its tail, the burn account for every genesis reward - but
no genesis block for it: `mine` refuses to start one, and the replay refuses every chain under
`lethenon-2`, however its genesis block was mined (#161). The main chain starts with lethenon 1.0.0
(ADR 0005). Until then everything runs on the test chain `lethenon-test-2`, and every chain an
earlier version wrote is refused.

FORMAT:

- **One consensus break**: the emission with its tail (#133), the new chain identifiers (#137) and
  the burn account for every genesis reward (#148) are collected on the integration branch
  `consensus/lethenon-2` (#138) and released together.
- The emission (#133): every chain replays under a declining reward, so a chain of 0.3.0 would come
  out with other balances; it is refused by its identifier instead (below).
- Chain identifiers (#137): the main chain is `lethenon-2` and the test chain `lethenon-test-2`, the
  identifiers of the rules from 0.4.0 on. A chain under `lethenon-1` or `lethenon-test-1` - every
  chain an earlier version wrote - is refused by the replay, by mining and by a node, with the
  reason that it was started under the rules before 0.4.0 and the identifier that took its place:
  "block 0: chain 'lethenon-1' was started under the rules before lethenon 0.4.0; under the rules
  from 0.4.0 on the main chain is 'lethenon-2'". #137 decided "before the rules of 0.3.0", when the
  break was to carry only the identifiers; with the tail emission (#133) in the same break, a chain
  that 0.3.0 started is under old rules as well, so the reason says 0.4.0.
- **Compatibility with 0.1.0, 0.2.0 and 0.3.0, measured in both directions on chains with real
  transfers, on the release.** The command lines of 0.1.0, 0.2.0 and 0.3.0 were built from their
  release tags, each with its lethenon jar replaced by the one on Maven Central and checked against
  Central's `.sha1` (`b5037a632fc0...`, `64a6bfb94fae...`, `09f2b96a6884...`); this one from the
  release commit with the full gate (`./gradlew clean build`: 74 classes, 712 tests, 0 failures).
  Every chain a version writes with its own command line carries an Ed25519 transfer of 12.5 LETH, a
  payment of 20 LETH to a published address and its sweep, 100 LETH to the holder's ML-DSA-65
  account and a transfer of 5 LETH from it, in four blocks; the 0.4.0 test chain also a faucet
  payment of 1,000 LETH in a fifth. Every version computes the same balances on its own chains: the
  recipient 17.5 LETH, the address owner 20 LETH after the sweep, in all six chains.
  - The earlier versions on the chains of 0.4.0, a `lethenon-test-2` chain written with `mine
    --testnet` and a `lethenon-2` chain whose block 0 was mined with `Genesis.candidate`, as the start
    day will (block 0 only: 0.4.0 does not extend it either, below): `balance`, `mine` and `send`
    exit 1 in all 18 runs, and neither file changes. 0.1.0
    says "block 0 belongs to chain 'lethenon-2'" (or 'lethenon-test-2'); 0.2.0 and 0.3.0 add "which
    is neither 'lethenon-1' nor 'lethenon-test-1'". No earlier version computes a balance on a chain
    of 0.4.0, or appends to one.
  - 0.4.0 on the chains of the earlier versions, main chains of 0.1.0, 0.2.0 and 0.3.0 and test
    chains of 0.2.0 and 0.3.0 (0.1.0 has no test chain): `balance`, `mine` and `send` exit 1 in all
    15 runs, "block 0: chain 'lethenon-1' was started under the rules before lethenon 0.4.0; under
    the rules from 0.4.0 on the main chain is 'lethenon-2'" and the same for `lethenon-test-1`, and
    no file changes.
  - 0.4.0 on the `lethenon-2` chain from `Genesis.candidate`: `balance`, `mine` and `send` exit 1,
    "block 0: the main chain 'lethenon-2' starts only from the genesis block fixed in the code, and
    this build has none" (#161), and the file does not change.
  - `mine` without `--testnet` on a new file exits 1 with the message below and writes nothing.

  Commands: `bash docs/launch/compatibility-0.4.0/build.sh <release commit>`, then
  `bash docs/launch/compatibility-0.4.0/measure.sh`, which writes one line per command to
  `results.tsv`. The same measurement on the integration branch at `c4546d0`, before #161, had the
  same results, except that 0.4.0 wrote four blocks onto the `lethenon-2` chain.
- Wallets: unchanged. 0.4.0 opens the wallets of 0.1.0 (`MCRYPT`), 0.2.0 and 0.3.0 (`LETHWF`);
  0.2.0 and 0.3.0 open those of 0.4.0, and 0.1.0 does not, as since 0.2.0.
- Network: the protocol version stays 3, and nodes of 0.3.0 and 0.4.0 refuse each other by their
  chain. `sync` of 0.3.0 against a node of 0.4.0 exits 1, "the peer at ... is on chain
  'lethenon-test-2', and a node runs only on 'lethenon-test-1'", and `sync` of 0.4.0 against a node
  of 0.3.0 the same the other way round; neither writes a file. (Wallets and network measured with
  a script of their own, in a network namespace, on the same command lines.)
- Every genesis block pays its reward to the burn account `Genesis.NOBODY`, on both chains (#148,
  ADR 0005): a chain whose block 0 pays anybody else is refused, and so is every transfer from that
  account. The burned 1,984 LETH count in every balance sum. Block 1 pays the first wallet that
  mines, so nobody holds anything before then.
- **No main chain yet, by any path** (#161): the replay refuses a chain under `lethenon-2` while the
  rules carry no anchor for it, with the reason below, so a main chain whose genesis block was mined
  through the library or by another program is neither replayed nor mined on. Measured before the
  fix on the release candidate: such a chain replayed, and `mine` added block 2 to it.
- 0.4.0 carries the anchor mechanism of #104 but no anchor for `lethenon-2`:
  `mine` without `--testnet` on a new chain exits 1, "the main chain 'lethenon-2' starts only from
  the genesis block fixed in the code, and this build has none: the main chain starts with lethenon
  1.0.0 (ADR 0005)". The main chain's genesis block is mined on its start day from a headline of
  that day and released as 1.0.0 (`docs/launch/launch-checklist.md`, "The start day").

CHANGED:

- the block reward declines, and never falls below a tail of 66 LETH (#133). A block pays a
  millionth of the mining pool, so the genesis block pays exactly 1,984 LETH; half the pool is paid
  out after about 2.6 years. Where the pool's share falls below 66 LETH, from block 3,403,214, after
  about 12.9 years of two-minute blocks, the difference is minted. 66 LETH a block is 0.8748 % of the
  genesis supply a year (365.25-day years), against Monero's 0.8702 % at the start of its tail by its
  own formula; the calculation and its script are in #133. The supply is the genesis supply of
  1,984,000,000 LETH plus what was minted, and the invariant after every block says exactly that
- fees still go into the pool, and now reach the miners a share at a time. The flat reward of 0.3.0
  paid nothing once the pool held less than one reward, until fees had filled it up again, and then
  everything at once; that lottery is gone
- the command line's help names the test chain through the constant: `genesis --testnet`,
  `mine --testnet` and `node` say `lethenon-test-2` (#137)
- `mine --testnet` mines block 0 for the burn account and says so; the next `mine` mines block 1
  for the wallet
- the faucet pays from the wallet that mined block 1, and hands out 1,000 LETH instead of 1,984:
  block 1 pays 1,983.998016 LETH under the declining reward, so 1,984 would need two mined blocks
- `lethenon genesis` takes `--headline`, a headline of the day the block is mined, and no longer
  `--wallet`: the reward goes to the burn account. It mines the main chain's candidate too, and
  writes nothing
- **API**: `Genesis.start(chainIdentifier, pun, now)` loses its holder parameter and refuses the
  main chain without an anchor; `Genesis.candidate` mines a genesis block regardless
- **API**: `Emission.TOTAL_SUPPLY` is `GENESIS_SUPPLY`; `BLOCK_REWARD` and `BLOCKS_WITH_A_REWARD`
  are gone, replaced by `FIRST_REWARD`, `TAIL_REWARD`, `EMISSION_DIVISOR` and `rewardFor`.
  `ConsensusRules` carries an `EmissionSchedule`; `ChainState` has `minted()` and `supply()`

ADDED:

- `EmissionSchedule` and `BlockReward`: the reward as a share of the pool plus the minted part
- `Genesis.NOBODY`, the burn account every genesis block pays, and `NobodyIsNoKeyTest`, which
  checks for every signature suite that the bytes decode as no key and no signature counts for
  them; a suite added later has to pass it (ADR 0005)
- ADR 0005, the genesis block of the main chain, accepted (#148)

FIXED:

- a mining node slowed down with every transfer it carried, until it found no block at all (#151).
  `Blocks.mine` computed the block's Merkle root again for every pun it tried, although only the
  pun changes, so a block of 40 transfers mined at 12,004 attempts a second against 351,952 for an
  empty one (lethenon 0.3.0 on 4 hardware threads). On a test network where transfers arrive every
  two minutes, every slower block left more transfers waiting for the next; in a three-hour run of
  the seed node measurement (#150) the miner found no block for its last 79 minutes. The root is
  now computed once per candidate: 578,054 attempts a second empty and 723,775 with 40 transfers,
  measured the same way. The block hash is unchanged, so nothing about the chain changes

KNOWN AND NOT FIXED:

- a configured peer is dialled once: a node whose one dial fails - through Tor that happens - stays
  without that peer until it is started again, and with `--no-discovery` that may be all its peers
  (#128)
- a node stopped with Ctrl-C or SIGTERM prints neither its stop line nor its refusals; with `--for`
  it does (#129)
- discovery forgets an address when only the node's own connection limit refused the dial (#126)

Version 0.3.0
-------------

FORMAT:

- Chains (#111): lethenon 0.3.0 reads the chain files of 0.1.0 and 0.2.0 - the encoding is the
  same, and every block replays - but under the new genesis rule, so with different balances.
  Measured on chains each version wrote with its own command line - two blocks mined, then an
  Ed25519 transfer, a payment to a published address, a transfer from an ML-DSA-65 account, a sweep
  of the one-time payment, and two more blocks - on the main chain and on the test chain:
  - a chain on which the genesis holder spent no more than its block rewards keeps opening, and
    0.3.0 mines on it; the genesis holder holds 9,808 LETH where 0.1.0 and 0.2.0 compute
    992,007,824 LETH, and every other account holds the same in all three versions (the recipient
    15 LETH);
  - a chain with a transfer of 1,000,000 LETH from the genesis holder is refused as a whole, its
    balances and mining alike: "a transfer of 1000000.00000000 from an account holding
    3856.00000000". Start a new chain with `mine`.
- **In the other direction, 0.1.0 and 0.2.0 read a chain 0.3.0 wrote without an error, and compute
  the genesis holder's balance the old way** - 992,007,824 LETH where 0.3.0 says 9,808. An older
  version can therefore sign and mine a transfer from the genesis holder that 0.3.0 refuses. Do not
  use 0.1.0 or 0.2.0 on a chain of 0.3.0.
- Test chains: 0.1.0 refuses them at the genesis block, as before ("block 0 belongs to chain
  'lethenon-test-1'"); between 0.2.0 and 0.3.0 they behave as the main chain above.
- Main chains with a block larger than 300,000 bytes, which 0.2.0 could mine, are refused by
  0.3.0 (#109, below).
- Waiting transfers: a transfer `send` left waiting next to a chain is mined by the other version -
  measured from 0.2.0 to 0.3.0, from 0.3.0 to 0.2.0 and from 0.1.0 to 0.3.0.
- Wallets: unchanged since 0.2.0. 0.3.0 opens the wallets of 0.1.0 (`MCRYPT`) and 0.2.0
  (`LETHWF`), 0.2.0 opens those of 0.3.0, and 0.1.0 opens neither 0.2.0's nor 0.3.0's.
- The network is new in 0.3.0; 0.1.0 and 0.2.0 have no node.

CHANGED:

- the genesis block allocates nothing outside the mining pool (#111). The whole supply,
  1,984,000,000 LETH, goes into the pool, and the genesis block is paid the ordinary block reward
  of 1,984 LETH out of it, like every other block. Before, half the supply went to the genesis
  block's beneficiary and the genesis block paid no reward. The same rule holds on `lethenon-1`
  and `lethenon-test-1`, so the test chain tests what the main chain does. The pool now lasts
  exactly 1,000,000 rewarded blocks, the genesis block the first of them, about 3.8 years at
  two-minute blocks (`python3 -c "print(1_984_000_000 // 1_984, 1_000_000 * 2 / (365.25 * 24 * 60))"`).
  This is a consensus change, and **every existing chain, test chain or main chain, is invalid
  under it**: the blocks still replay, but the genesis holder's balance is 1,984 LETH instead of
  992,000,000, so the first transfer that spends more than that from the genesis holder is
  refused, and the balances of every chain differ from what lethenon 0.2.0 computed. Start a new
  chain with `mine`. The faucet still hands out 1,984 LETH from the genesis holder's account, so it
  can pay once after the genesis block and then as often as that account mines
- the main chain, `lethenon-1`, runs the same block limits as the test chain, from height 0 (#109):
  a block's timestamp at most two hours after the verifying node's clock, a block at most 300,000
  bytes. This is a consensus change. The timestamp bound rejects no chain that verified before,
  since the clock only moves on. The size limit rejects a main chain with a block over 300,000
  bytes, which lethenon 0.2.0 could mine: 56 or more ML-DSA-65 transfers, or 1,515 or more
  Ed25519 transfers, in one block paying an Ed25519 miner. Mining on the main chain now carries the
  longest prefix of the waiting transfers that fits, and the rest keeps waiting
- for a caller of the library, `CanonicalEncoding.readChain`, `readSignedTransaction` and
  `readTransaction` refuse bytes that end early with an `IllegalArgumentException` that gives the
  reason ("the bytes end before the structure they encode does", "a field announces 182 bytes and
  96 are left"), where 0.2.0 let a `BufferUnderflowException` out of the buffer, without a message.
  A caller that caught `BufferUnderflowException` catches `IllegalArgumentException` now. The
  consumer check of this release found one: mystic-crypt-ui's lethenon plugin (#80,
  mystic-crypt-ui#532)
- ADR 0001, three rules for what the privacy block may take in: code under a licence compatible
  with MIT, Apache-2.0 included when a NOTICE file carries its notices; copyleft code only as a
  separate program across a process boundary; code without a licence never; and no scheme with a
  known patent valid in the EU, or an unclear patent position, before the maintainer has clarified
  it (#58, #65)
- ADR 0002, how a scheme written in C is integrated: every scheme in pure Java, the authors' C code
  only in the tests, through the Foreign Function and Memory API, as the reference for difference
  tests, beside forgery tests and PIT on the verification logic; parameters only from the paper or
  the reference code (#65)
- `docs/research/pq-privacy-literature.md`, the phase A literature on hiding sender and amount, now
  also checked against the full texts of six general results on Fiat-Shamir in the quantum random
  oracle model. No candidate's proof-model level changes: none of them reaches a quantum random
  oracle proof through a general result (#54, #70)
- ADR 0001 rule 8: ML-KEM and ML-DSA only with the parameters of their standard, from the JDK or
  Bouncy Castle, on the basis of NIST's licence summary; modified parameters and predecessor
  versions such as Kyber round 3 are excluded, and Jintai Ding's 2022 statement on US 9,246,675 is
  recorded as a known, unresolved residual risk (#72)
- a post-quantum hidden recipient is postponed: the one-time destinations stay Ed25519 with an
  X25519 view key. Maram and Xagawa's QROM anonymity result is for Kyber round 3, not ML-KEM, and
  gives no one-time ML-DSA key only the recipient can spend; SPIRIT, which does, is proven in the
  classical random oracle model only, changes Dilithium's parameters and has code without a
  licence. Recorded in the README and in section 15 of the literature (#72)
- hidden amounts are postponed and the account model stays: no published amount scheme meets ADR 0001
  and fits an account model, and a combination of our own over LNP22/LaZer is not built without a
  cryptographer's security argument. README, ADR 0002 and section 16 of the literature now say what
  lethenon is: post-quantum in its authenticity, classical in its hidden recipient, open about
  sender and amount (#74)

FIXED:

- through Tor, a node gave up on an onion peer after five seconds: every connection had the time a
  direct one gets, and for an onion service Tor answers the CONNECT only after it has built a
  rendezvous circuit. In the first run against a real Tor, node B never reached node A's zone
  (`Connect timed out`), and of eight first connections from a fresh Tor client to that onion service,
  four took between 5.5 and 6.8 s. A connection through a SOCKS proxy now has 45 s to open, Monero's
  `P2P_DEFAULT_SOCKS_CONNECT_TIMEOUT`, and a direct one keeps its five; the HELLO keeps five seconds
  either way. This applies to `node`, `sync`, `send --node` and the genesis bootstrap (#127)
- a node left an outgoing slot empty when a connection to a learnt address ended late: it filled
  slots only when a PEERS answer arrived, so a handshake that timed out after the last answer
  left the node below its twelve outgoing connections for good. It showed as a red CI run of
  `PeerExchangeTest` on a slow runner. Now a slot is filled again when an outgoing connection
  ends or a learnt address cannot be dialled, and an address that opened a connection but never
  completed a handshake is forgotten, as one that could not be dialled is (#121)
- two nodes could hold two connections to each other: a node did not notice that a peer was a
  node it was already connected to, so two nodes that dialled each other kept both connections,
  relayed everything twice and used two of their slots for one neighbour. It showed as a red CI
  run of `PeerExchangeTest` on a slow runner. Now a second connection to a node identity that is
  already a peer is closed, and both ends close the same one: the connection kept is the one
  dialled by the node with the smaller identity, decided from each connection's own direction.
  Behind it was a gap in peer exchange as well: an address left the list of addresses being
  dialled once its socket was open, before its handshake ended, and could be dialled again in
  between. It now counts as busy until the handshake ends (#117)
- a connection that ended because a frame could not be written, or because a peer let its queue of
  1,024 frames fill up, ended without a reason on either side. The node's refusals now name the
  peer and the cause (#101)
- a block the node's own miner built could be too large to fetch: the miner capped blocks at 500
  transfers by count, and 500 ML-DSA-65 transfers make 2,692,122 bytes, so two such blocks did not
  fit in a 4 MiB BLOCKS frame and a node behind never caught up. Blocks are now bounded in bytes,
  and a node answers GET_BLOCKS with as many blocks as fit in one frame (#98)
- admitting a transfer to the pool re-applied every waiting transfer of the same sender, signature
  check included, so filling the pool with 5,000 transfers from one sender did not finish within
  60 s. Admission now keeps per sender what is waiting and checks each signature once (#92)
- a node that was fetching from a peer asked it again for its chain whenever that peer relayed a
  block the node could not place yet, the second answer overtook the first, and the node
  disconnected an honest peer (`BLOCKS with 20 blocks for a request of 4`). Now one
  synchronisation runs per peer, an unknown parent during it asks once more when it ends, and a
  CHAIN nobody asked for disconnects (#85)
- decoding a block, chain or transfer no longer trusts a length prefix: a negative length, or one
  larger than the bytes left, is refused before anything is allocated, and bytes that end early are
  refused as such instead of escaping as BufferUnderflowException. Before, one crafted field asked
  for up to 2 GiB - which matters once the bytes come from a peer (#80)

ADDED:

- `docs/tor.md`, the runbook for the first run of the Tor transport against a real Tor: one Tor
  daemon, two onion services per node (the zone's, and one for the chain, since the zone carries
  none), an onion node that mines and a second node in its zone, an own transfer that travels only
  through the zone, and what to record. It ran against Tor 0.4.9.11 on 2026-10-08, recorded in
  #112, and found #127 on the way (#124, #112)
- Tor, step 3 of ADR 0004 (#120): `node --anonymous-inbound <onion>:<port>,127.0.0.1:<port>[,max]`
  listens on loopback for the node's onion service, where Tor's `HiddenServicePort` forwards, and
  every peer accepted there belongs to the anonymity zone; it needs `--tx-proxy`. HELLO protocol
  version 3 carries the onion address, only inside the zone; version-2 nodes are refused, and a
  clearnet HELLO that carries an onion address is refused. Zone peers pass onion addresses on
  among themselves, and nothing crosses between the zone's lists and the clearnet's. A zone slot
  that frees up is filled again, as on the clearnet. In the library: `Node.listenAnonymously`,
  `Node.anonymouslyListeningOn` and `Hello.onionHost`
- Tor, step 2 of ADR 0004 (#116): `node --tx-proxy tor,host:port[,max]` turns on an anonymity zone.
  Onion peers given with `--peer` are reached through Tor and carry transfers only, and a transfer
  that originates on the node - submitted on it, or handed over by a command such as
  `send --node` - goes only to them, or waits for one, and is never sent in the clear. The zone has
  its own node identity, announces no listening port and names only the genesis block, carries no
  chain sync, and passes over blocks a zone peer relays. Transfers from other nodes are relayed as
  before. In the library: `Node.anonymityZone(Outbound, int)` and `Node.anonymousPeers()`. Known
  limits, in the ADR: a node that mines can reveal its own transfers in its own block, and every
  zone peer sees all own transfers
- Tor, step 1 of ADR 0004 (#114): `node --proxy host:port` and `sync --proxy host:port` send every
  outgoing connection through a SOCKS5 proxy such as a Tor daemon, with the peer's host handed over
  unresolved, so nothing about a peer reaches the local DNS. Peers learnt by peer exchange go the
  same way, and so does the genesis bootstrap. Nothing is dialled around a proxy that is given. A
  node with a proxy listens on 127.0.0.1 unless `--bind` says otherwise. v3 onion addresses (56
  base32 characters and `.onion`) are recognised, other names ending in `.onion` are refused, and
  an onion address without a proxy is refused before anything touches the network. In the library:
  `Outbound`, `Node.dialingThrough`, `Node.listen(InetAddress, int)` and overloads of `Sync.once`,
  `Handover.send` and `Bootstrap.genesisFrom` that take the route. Tested against a SOCKS5 server in
  the test sources; a run against a real Tor is ahead (#112)
- a sync that stops (#107): `Sync.once` and `lethenon sync --chain FILE --peer HOST:PORT` bring a
  chain file up to one node's tip, verify every block with the replay a node uses, write the file
  once and only when the chain grew, and stop. A failed sync - an unreachable peer, a refused
  handshake, a peer that breaks off, the time running out - writes nothing and names the peer, the
  height it announced and the height reached. It reads no wallet and asks for nothing but the
  chain. For wallets, scripts and the desktop plugin (astrapi69/mystic-crypt-ui#530)
- a genesis block fixed in the code, the mechanism without the block, the fourth prerequisite for a
  main chain (#104): `ConsensusRules` carries per chain at most one anchor, the block's canonical
  bytes, checked when the table is built; `Replay` rejects a chain that starts elsewhere;
  `Genesis.start` gives the anchor instead of mining one, and `mine` and `node` start chains
  through it; `lethenon genesis` prints a candidate's hash and bytes and writes nothing. No chain
  has an anchor yet: the main chain's block, and the allocation it carries, is the maintainer's
  decision
- peer exchange, the third prerequisite for a main chain (#102): HELLO carries the listening port
  and a random node identity (protocol version 2), after Monero's `my_port` and `peer_id`. A node
  asks listening peers with GET_PEERS, passes on at most 250 addresses of nodes it has itself been
  connected to, keeps Monero's confirmed and heard-of lists (1,000 and 5,000), and dials learnt
  addresses while it has room for outgoing connections. A caller that does not listen is never
  passed on; a connection to the node itself is recognised and that address never dialled again.
  `node --no-discovery` stays with the given peers
- a consensus limit on block size, the second prerequisite for a main chain (#99): on
  `lethenon-test-1` a block larger than 300,000 bytes does not verify, Monero's full reward zone
  taken as a hard limit; `CanonicalEncoding.blockSize` counts it. Mining carries the longest
  prefix of the waiting transfers that fits, and `mine` keeps the rest waiting instead of dropping
  it. The main chain has the same limit since #109
- a consensus bound on timestamps in the future, the first prerequisite for a main chain (#96):
  on `lethenon-test-1` a block whose timestamp lies more than two hours after the verifying
  node's clock does not verify, Monero's `CRYPTONOTE_BLOCK_FUTURE_TIME_LIMIT`. The limit sits in
  `ConsensusRules` as the chain's `BlockLimits`; the main chain has the same bound since #109
- a network for the test chain, seventh building block (#76, #94, ADR 0003): `lethenon node
  --chain <file> --listen <port> [--peer host:port ...] [--mine --wallet <file>] [--for <seconds>]`.
  It serves the chain file, relays and synchronises; with `--mine` it mines on its pool, at most
  500 transfers per block, and starts a test chain on an empty file; without it, an empty file
  takes the genesis block from the first peer that answers, checked against that peer's HELLO, and
  then synchronises block by block. The README shows two nodes, a send through one and a balance
  from the other's copy
- a network for the test chain, sixth building block (#76, #92, ADR 0003): limits. A node opens at
  most 12 connections and accepts at most 16, refusing above that with the reason recorded; a peer
  that does not answer a GET_CHAIN or GET_BLOCKS within 120 s is disconnected, while one with
  nothing asked of it may stay quiet; the pool holds at most 5,000 transfers. ADR 0003 called the
  120 s an idle timeout, which with no keepalive message would have disconnected every honest peer
  on a quiet network; it is the answer timeout Monero's value is
- a network for the test chain, fifth building block (#76, #90, ADR 0003): the shared pool. A node
  serving a chain file writes every chain it adopts and every change of its pool to `<chain>` and
  `<chain>.pending`, and at start offers each waiting transfer again, so a double spend in the file
  is dropped and the file rewritten. `send` and `faucet` take `--node host:port`: the transfer is
  handed to the node over a handshake and one TRANSFER frame, and the command fails unless the
  node's pool file then carries it
- a network for the test chain, fourth building block (#76, #88, ADR 0003): a node follows a fork.
  A peer's chain that leaves the node's below its tip is fetched from the fork point; the node
  switches when the candidate's cumulative work is strictly greater and it verifies whole, and keeps
  its chain on equal work. The transfers of the dropped blocks go back into the pool ahead of the
  waiting ones if they still fit. A fork is followed only if it overtakes within one CHAIN answer,
  500 blocks after the fork point; a deeper one is recorded and not followed, which ADR 0003 now
  states as a reorganisation depth limit
- a network for the test chain, third building block (#76, #84, ADR 0003): a node whose peer
  announces more cumulative work in its HELLO asks for that peer's chain right after the handshake,
  instead of waiting for the next block, and fetches it like any other, each extended chain checked
  whole. A peer with equal or less work is not asked; one that claims more work than it has costs a
  fetch and changes nothing
- a network for the test chain, second building block (#76, #82, ADR 0003): `TransactionPool`
  admits a transfer only if the next block could carry it - chain and schemes admitted, signature,
  nonce exactly the sender's next counting its waiting transfers, balance after them - refuses a
  second transfer with the same sender and nonce as a double spend, keeps the first one seen, and
  re-checks itself on every new tip. Nodes relay blocks and transfers they accept to every peer but
  the sender; a block with an unknown parent makes a node ask for the peer's chain with a locator
  built as Monero's and fetch the missing blocks in batches of at most 20, each extended chain
  checked whole with `Replay.verify`. A peer sending bytes that do not decode, a message out of
  place or a block that does not verify is disconnected. Frames to a peer go through a queue of at
  most 1,024 with a writer thread of its own. A fork below the tip is recorded and not yet followed
- a network for the test chain, first building block (#76, #78, ADR 0003): the package
  `lethenon.transport` speaks TCP in frames of a 4-byte length, a type byte and a payload, at most
  4 MiB, checked before anything is allocated. Nodes connect to a fixed list of peers and shake
  hands with HELLO (magic, protocol version, chain identifier, genesis hash, tip, cumulative work),
  and refuse within 5 seconds anything that is not this protocol version on `lethenon-test-1` with
  the same genesis block. A node does not run on the main chain. `ChainWork` sums 2^difficulty
  over a chain
- the privacy block's architecture (phase B, ADR 0002): a transfer has three building blocks a
  scheme can replace - authorization, recipient, amount - and every scheme carries a permanent,
  versioned identifier. A consensus rule admits each scheme per chain from a block height on, and
  optionally until a later one, so a broken scheme can be switched off without a new chain. Today's
  rule admits exactly what both chains accepted before: ed25519, ml-dsa-65, direct, stealth-v2 and
  plain amounts from height 0; no behaviour changes. No scheme that hides sender or amount exists
  yet (#60)

KNOWN AND NOT FIXED:

- a configured peer is dialled once: a node whose one dial fails - through Tor that happens, the
  first run measured one first circuit in eight that Tor gave up on - stays without that peer until
  it is started again, and with `--no-discovery` that may be all its peers (#128)
- a node stopped with Ctrl-C or SIGTERM prints neither its stop line nor its refusals; with `--for`
  it does (#129)
- discovery forgets an address when only the node's own connection limit refused the dial (#126)

Version 0.2.0
-------------

FORMAT:

- Wallets: a wallet file created or restored with 0.2.0 is sealed in the envelope `LETHWF`, and 0.1.0
  cannot open it - it refuses the file as "not a wallet file: it is not sealed with a password the
  way a wallet file is". A wallet file written by 0.1.0 keeps opening and is never rewritten:
  lethenon writes a wallet file only when one is created or restored, and never over an existing
  one (#45)
- Chains: a chain started with `mine --testnet` carries the identifier `lethenon-test-1`, which 0.1.0
  does not know. 0.1.0 refuses such a chain at its genesis block - `balance` and `mine` end with
  "block 0 belongs to chain 'lethenon-test-1'" and exit 1 - and leaves the chain file as it was.
  Main chains (`lethenon-1`) are unchanged in both directions: 0.1.0 replays a main chain 0.2.0
  wrote and mines on it, 0.2.0 replays the result, and a chain written by 0.1.0 is a main chain
  (#50)

ADDED:

- a test chain, `lethenon-test-1`, beside the main chain `lethenon-1`: `mine --testnet` writes its
  genesis block, and from then on the genesis block decides - every block, transfer and sweep
  carries its identifier, the replay refuses a block or a transfer of the other chain and a genesis
  block naming neither, and `--testnet` on a main chain is an error. `Mining.nextBlock` takes the
  identifier for a new chain; without one it follows the genesis block. Chains written by 0.1.0 are
  main chains and replay unchanged (#50)

CHANGED:

- the rule "this project does not write new cryptography" is replaced, not removed, by ADR 0001:
  a new cryptographic construction only after a peer-reviewed publication with its proof, checked
  against the authors' test vectors or a reference implementation, on the test chain first and on
  the main chain only after an external cryptographic review, and no invented primitives. Sender
  ambiguity and amount confidentiality are described as not built rather than ruled out. Nothing
  in the code changes (#49). A fifth rule: every scheme names the model its proof holds in (#55)
- the wallet file is sealed with mystic-crypt's PassphraseEnvelope under the marker LETHWF - the
  envelope of mystic-crypt-ui's vault, byte for byte - instead of PassphraseCryptor's MCRYPT layout,
  which is still read. A wallet file 0.1.0 wrote is a fixed test vector and opens, its bytes on disk
  unchanged afterwards. mystic-crypt 13.3 to 13.5: 13.5's envelope declares
  GeneralSecurityException, so the calls into it are plain calls again (#45, #47)
- the commands that read a password - wallet create, wallet restore, and every command that opens a
  wallet - overwrite it once the file is written or opened, on success and on failure, and that is
  now asserted on the array's content rather than assumed (#45)

Version 0.1.0
-------------

The first release of the library. The CHAIN is not published and never will be: nobody can buy the
coin, there is no pool, no listing, no promotion (#2). What is on Maven Central is the library the
desktop application and the command line are built from.

ADDED:

- the chain: an account model with a nonce per account, transfers with a signed memo, a canonical
  encoding with a version byte in every persisted structure, and proof of pun - a block is mined
  when its hash carries the required leading zero bits. `Replay.verify` checks every signature,
  every state transition, every block hash and the supply invariant from the genesis block on, and
  says what it checked (#2, milestone 1)
- two signature suites in one chain, the identifier inside the signed bytes: Ed25519 by default and
  ML-DSA-65 (FIPS 204) per transaction, with known-answer tests from RFC 8032 and the ML-DSA
  vectors. An unknown suite identifier is refused rather than ignored (milestone 2)
- a fixed supply of 1,984,000,000 LETH in eight decimals, the block reward of 1,984 LETH paid from
  a pre-minted pool of 992,000,000 - exactly 500,000 blocks, then fees only. Integer arithmetic
  throughout, and the sum of all balances equals the supply after every block, as an assertion
- the difficulty as a rule rather than a block's own claim: minimum 8 bits, a 120 second target,
  adjusted every 30 blocks by at most 2 bits, and a timestamp that must lie past the median of the
  last 11 (#24)
- one-time destinations: the sender derives `P = S + H(s)*B` from the recipient's published
  address, so two payments to the same address land on keys with nothing visibly in common, the
  recipient recognises both with its view key alone, and only the holder of the spend key can move
  them. The additive construction comes from mystic-crypt's `Ed25519KeyBlinding`; this project
  writes no curve arithmetic (#21)
- a balance that is computed, never requested: `WalletScan` replays the chain locally, and
  `NoBalanceQueryTest` keeps the chain package unable to name a networking type at all, so the
  property holds for every run rather than for one captured one
- a wallet that can be recovered: BIP-39 words, SLIP-0010 hierarchical derivation through
  mystic-crypt's `SeedDerivation`, Shamir shares of the seed through its `SecretSharing`, and a
  wallet file with its own password sealed the way the desktop application's vault is (#34)
- a picocli command line: `wallet create`/`restore`, `mine`, `faucet`, `send` - to an account key
  or to a published address - `sweep` for what arrived at one-time destinations, and `balance`
  (#32, #37)

Pre-history: the versions this file named before this entry belonged to the 2021 java-library
template this repository started from. That template did not build against a current JDK and was
replaced wholesale; nothing of it is in this release, so its entries are not kept here.

Notable links:
[keep a changelog](http://keepachangelog.com/en/1.0.0/) Don’t let your friends dump git logs into changelogs
