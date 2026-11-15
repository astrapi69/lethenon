# ADR 0003: A network for the test chain

- Status: accepted
- Date: 2026-10-07
- Released by the maintainer (#76); every choice below was made within that release, by best
  practice and the recommendations recorded here, without a further question to the maintainer

## Context

Until now a chain lives in one file on one machine: `mine` appends a block, `send` and `faucet`
append to `<chain>.pending`, and nobody else learns of either. For a chain to be a chain, nodes
have to relay blocks and transfers, a new node has to catch up with every block checked, two nodes
that mined at the same height have to agree on one chain, and a transfer must not be spendable
twice while it waits.

### What the repository already has

Inventory of develop at 6f637a3:

- **Encoding.** `CanonicalEncoding` encodes a signed transfer (`encode(SignedTransaction)`,
  `readSignedTransaction`) and a chain (`encodeChain`, `readChain`), each with its version byte. A
  single block travels as a chain of one, so the network needs no new block format.
- **Verification.** `Replay.verify(chain)` checks every hash, signature, nonce, balance, difficulty,
  timestamp rule, chain identifier and the supply after every block. It is the only verifier, and
  the network uses nothing else.
- **Difficulty.** `DifficultyRule` gives a block's difficulty in leading zero bits, from 8 to 256,
  so a block's work is 2^difficulty.
- **Persistence.** `ChainFile` writes the chain and `<chain>.pending` atomically (write and rename).
- **Isolation.** `NoBalanceQueryTest` keeps the chain package unable to name a networking type, and
  already names `lethenon.transport` as the place a transport belongs.

### Monero as the blueprint

Read from the Monero sources, commit f6a591c (2026-10-04), `git clone --depth 1 --sparse`:

- **Handshake.** It carries a `network_id` (`src/p2p/p2p_protocol_defs.h:151`) and the peer's
  `current_height`, `cumulative_difficulty` and `top_id`
  (`src/cryptonote_protocol/cryptonote_protocol_defs.h:254-257`).
- **Messages.**
  - Relaying: `NOTIFY_NEW_BLOCK`, `NOTIFY_NEW_TRANSACTIONS`.
  - Chain sync: `NOTIFY_REQUEST_CHAIN`, `NOTIFY_RESPONSE_CHAIN_ENTRY`.
  - Fetching blocks: `NOTIFY_REQUEST_GET_OBJECTS`, `NOTIFY_RESPONSE_GET_OBJECTS`.
  - All in `cryptonote_protocol_defs.h:174-291`.
- **Locator for the chain request.** "IDs of the first 10 blocks are sequential, next goes with
  pow(2,n) offset ... and the last one is always genesis block" (`cryptonote_protocol_defs.h:280`).
  The answer carries `start_height`, `total_height` and the block ids (`:297-301`).
- **Limits.**
  - `P2P_DEFAULT_CONNECTIONS_COUNT 12` (`src/cryptonote_config.h:141`).
  - `P2P_DEFAULT_PACKET_MAX_SIZE 50000000` (`:143`).
  - `P2P_DEFAULT_HANDSHAKE_INVOKE_TIMEOUT 5000` ms (`:150`).
  - `P2P_DEFAULT_INVOKE_TIMEOUT` two minutes (`:149`).
  - `BLOCKS_SYNCHRONIZING_DEFAULT_COUNT 20` blocks per request (`:98`).

## Decision

### Where the code lives

- **Package.** The network is the package `io.github.astrapi69.lethenon.transport`. The chain package
  does not name it, and `NoBalanceQueryTest` keeps it that way.
- **What stays in the chain package.** The logic that decides about blocks and transfers - the
  pool of waiting transfers, fork choice, cumulative work - stays there, with no networking types,
  so it is testable without a socket.
- **Dependencies.** None new. Sockets from `java.net`, one virtual thread per connection, and a
  single lock around a node's chain and pool.

### Only the test chain

A node runs only on `lethenon-test-2`.
- It refuses a chain file whose genesis block names `lethenon-2`.
- It refuses a chain file under `lethenon-test-1` or `lethenon-1` with the reason that the chain was
  started under the rules before 0.4.0 (#137).
- It creates no main chain.
- Its handshake refuses any peer that names another chain or another genesis block.

The main chain gets a network only by a later decision. This ADR binds nothing on the main chain
to an external review, because it puts nothing there.

### Frames and messages

Each message is one frame on the stream: a 4-byte length, a 1-byte message type, the payload. The
length counts the type and the payload and is checked before anything is allocated.

| Message | Payload | Monero counterpart |
|---|---|---|
| `HELLO` | magic "LETHENON", protocol version, chain identifier, genesis hash, best height, best hash, cumulative work, listening port (0 for none), node identity | handshake with `network_id`, `my_port`, `peer_id` and core sync data |
| `BLOCK` | one block, encoded as a chain of one | `NOTIFY_NEW_BLOCK` |
| `TRANSFER` | one signed transfer | `NOTIFY_NEW_TRANSACTIONS` |
| `GET_CHAIN` | a locator of block hashes, built as Monero's | `NOTIFY_REQUEST_CHAIN` |
| `CHAIN` | the height of the first hash the peer shares, then the hashes after it | `NOTIFY_RESPONSE_CHAIN_ENTRY` |
| `GET_BLOCKS` | first height, count | `NOTIFY_REQUEST_GET_OBJECTS` |
| `BLOCKS` | the blocks, encoded as a chain | `NOTIFY_RESPONSE_GET_OBJECTS` |
| `GET_PEERS` | nothing | the peer list in Monero's handshake answer |
| `PEERS` | at most 250 addresses, host and port | `local_peerlist_new` |

There is deliberately no message that asks for a balance, an account or an address. A node serves
blocks and transfers; a wallet computes its balance from the chain, as before.

### Handshake

Both sides send `HELLO` first and read the other's within the handshake timeout. The connection is
closed, with the reason logged, when any of these hold:
- the magic is wrong;
- the protocol version is not this build's;
- the chain identifier is not `lethenon-test-2`;
- the genesis hash differs;
- the node identity is this node's own: the connection leads back to itself.

A peer with more cumulative work is asked for its chain at once.

### Peer exchange (#102)

Protocol version 2 adds two fields to `HELLO`, as Monero's handshake carries them
(`basic_node_data`, `src/p2p/p2p_protocol_defs.h:152-155`):
- the port the node listens on, 0 for a caller that does not listen;
- a random identity the node draws at start, so that a connection to itself is recognised.

A version-1 peer is refused like any other version.

How addresses travel:
- After the handshake, a node asks a listening peer with `GET_PEERS`. The answer `PEERS` carries at
  most 250 addresses (`P2P_DEFAULT_PEERS_IN_HANDSHAKE 250`, `src/cryptonote_config.h:144`). A
  `PEERS` that was not asked for disconnects.
- A node keeps Monero's two lists: addresses it has itself been connected to, at most 1,000
  (`P2P_LOCAL_WHITE_PEERLIST_LIMIT`, `:138`), and addresses only heard of, at most 5,000
  (`P2P_LOCAL_GRAY_PEERLIST_LIMIT`, `:139`).
- Only the first list is passed on, the newest 250, and of it only addresses that are not waiting.
  An address heard of becomes confirmed when a connection to it succeeds. A failed attempt, a dial
  refused or a handshake that never completes, does not forget the address (#296): it waits before
  it is tried again, 60 seconds after its first counted failure and twice as long after each
  further one, at most an hour, and it is dropped only after 3 attempts without ever a success, or
  after 10 counted failures with no success for 24 hours. The first failure counts; a further one
  counts only once a connection to any address has succeeded since the last counted one, so an
  outage on the node's own side counts once against each address instead of draining the book.
  A success clears the count. A waiting address is neither dialled nor passed on; a dropped one is
  gone at once and comes back only when a peer passes it on again, and hearing a known address
  again leaves its count as it is. The values are Bitcoin Core's address manager
  (`ADDRMAN_RETRIES` 3, `ADDRMAN_MAX_FAILURES` 10, `src/addrman.cpp`, v29.0) with its 7-day
  `ADDRMAN_MIN_FAIL` scaled to 24 hours, because the book lives in memory and test nodes run for
  days, and with Monero's hour as the longest wait (`P2P_FAILED_ADDR_FORGET_SECONDS`). An address
  that leads back to the node itself is never dialled again.
- While a node has room for outgoing connections, at most 12, it dials addresses from the lists.
  `--no-discovery` (`Node.discoverPeers(false)`) keeps it with the peers it was given, and it
  still answers `GET_PEERS`.

What is never passed on:
- A caller that does not listen announces port 0 and is never named to anybody: `send --node`,
  a node taking its genesis block. A node does not ask such a caller for addresses either.
- An accepted peer is passed on as its host and the port it announced, never as the ephemeral
  port of the connection.

This makes a listening node's address known to every node it meets, which is what a network
needs and what a protest chain should say plainly: running a node that listens publishes its
address. Tor is the later step that changes that.

### Relaying and missing blocks

- **A new block.** A node that mines or accepts a block sends it as `BLOCK` to every peer except the
  one it came from.
- **A new transfer.** A node that admits a transfer to its pool sends it as `TRANSFER` the same way.
- **An unknown parent.** A received block whose parent the node does not know is not a fault: the
  node sends `GET_CHAIN` to that peer and synchronises.

Frames to a peer are written by a thread of that peer's own, from a bounded queue. A node handles a
frame on the thread that read it, and that thread may have to send to other peers; writing to their
sockets directly could leave two nodes that relay to each other waiting for each other to read
(#82).

Full blocks are relayed, not Monero's compact "fluffy" blocks. Blocks here are small, and that
optimisation can come later.

### Synchronisation

1. `GET_CHAIN` carries the locator.
2. The peer answers `CHAIN` with the height of the newest locator hash it shares and up to 500
   following hashes.
3. The node then fetches the blocks in `GET_BLOCKS` batches of at most 20 (Monero's default).
4. It builds the candidate chain: its own blocks up to the shared height, then the fetched ones.
5. It checks the whole candidate with `Replay.verify`.

A candidate that does not verify is dropped and the peer disconnected. Replaying the whole chain
for each batch is linear in its length, which is fine for a test chain; an incremental replay is
a later optimisation.

### Forks: the most work wins

- **Work.** The cumulative work of a chain is the sum of 2^difficulty over its blocks, as a
  BigInteger.
- **Switching.** A node switches to a verified candidate only if its work is strictly greater than
  that of its own chain. On equal work it keeps the chain it had, the first seen.
- **Rolling back.** Switching drops the node's blocks above the fork point. Their transfers go back
  into the pool if they are still valid on the new chain; the rest are dropped.
- **Depth.** The blocks of a fork are held until the fork carries more work. A fork is followed
  only if it overtakes within one `CHAIN` answer, 500 blocks after the fork point; a deeper one is
  recorded and not followed. That bounds what a peer can make a node hold, and it is in effect a
  limit on how deep a reorganisation can be. Lifting it needs a fetch across several `CHAIN`
  answers that keeps the held blocks bounded some other way (#88).

### The pool of waiting transfers

`TransactionPool`, in the chain package, admits a transfer only if:
- its chain identifier and schemes are admitted (`ConsensusRules`);
- its signature verifies;
- its nonce is exactly the sender's next one, counting the transfers of that sender already in the
  pool;
- the sender's balance covers it after them.

A second transfer from the same sender with the same nonce is a double spend. It is refused, and
the first one seen stays: there is no replacement by fee. After each new tip the pool drops what
the block carried and re-checks the rest.

Admission keeps, per sender, how many transfers wait and what they spend, and checks a new one
against that; each signature is checked once, when its transfer is offered. Re-applying a sender's
waiting transfers on every offer took about 12.5 million signature checks to fill the pool from one
sender (#92).

Transfers to a sender that are still waiting do not count towards its balance, the same as
`Transfers.prepare`: then a sender's waiting transfers are valid in any block that carries them in
their order, whatever else the block carries (#82).

A node keeps its chain in `<chain>` and its pool in `<chain>.pending`, in the existing formats, so
`balance` and the other readers keep working on a node's files. At start it offers every waiting
transfer to the pool again, so a double spend or a transfer that no longer fits is dropped there
and the file rewritten (#90).

`send` and `faucet` hand a transfer to a running node with `--node host:port` instead of writing
the file (#90):
- The transfer is prepared against the chain and pool in the file named by `--chain`, which has to
  be the file the node serves.
- The handover is a handshake, one `TRANSFER` frame, then the command closes its direction and reads
  until the node closes the other. A node handles frames in order, so it has then admitted or
  refused the transfer.
- The command then reads the node's pool file. If the transfer is not there, it fails with a
  message. A node on another machine or another file keeps a pool the command cannot see, and that
  case fails closed rather than reporting a success it could not check.

A file a node serves belongs to the node while it runs. `mine` and `sweep` on it would be
overwritten by the node's next write; mining on a node is `node --mine`.

### Limits

| Limit | Value | Basis |
|---|---|---|
| frame size | 4 MiB | below Monero's 50,000,000 bytes; the largest message here is `BLOCKS` |
| outgoing connections | the configured peers, at most 12 | Monero's default of 12 |
| incoming connections | at most 16 | own choice; refused above it |
| handshake timeout | 5 s | Monero's 5000 ms |
| answer timeout | 120 s | Monero's two-minute invoke timeout (`P2P_DEFAULT_INVOKE_TIMEOUT`, `src/cryptonote_config.h:149`): a peer that does not answer a `GET_CHAIN` or `GET_BLOCKS` in time is disconnected; one with nothing asked of it may stay quiet (#92) |
| blocks per `GET_BLOCKS` | at most 20, and an answer carries only as many as fit in one frame | Monero's default; the asker takes fewer and asks for the rest (#98) |
| hashes per `CHAIN` | at most 500 | own choice |
| pool size | at most 5,000 transfers | own choice; refused above it |
| frames waiting for one peer | at most 1,024 | own choice; a peer that does not read them is disconnected (#82) |
| block size | at most 300,000 bytes, a consensus rule on the test chain | Monero's `CRYPTONOTE_BLOCK_GRANTED_FULL_REWARD_ZONE_V5`, `src/cryptonote_config.h:60`, taken as a hard limit (#99). Measured: an Ed25519 transfer is 199 bytes, an ML-DSA-65 transfer 5,376, so a block holds about 55 ML-DSA-65 transfers. The former cap of 500 transfers per mined block made a block of 500 ML-DSA-65 transfers 2,692,122 bytes, and two of them no longer fit in a frame (#98) |

A peer that sends a frame above the limit, an unknown message type, bytes that do not decode, or a
block that does not verify is disconnected.

### A genesis block fixed in the code (#104)

The mechanism a main chain needs, built and tested on the test chain; the main chain's block itself
is the maintainer's decision, above all the allocation it carries.

- `ConsensusRules` carries an anchor table: per chain at most one genesis block, as its canonical
  bytes (`GenesisAnchor`). Monero fixes its genesis the same way, `GENESIS_TX` and `GENESIS_NONCE`
  (`src/cryptonote_config.h:239-240`).
- An anchor is checked when the table is built: exactly one block, at height 0, of the chain it is
  filed under, verifying on its own. A table that fails this is not built.
- `Replay` rejects a chain whose genesis block is not its chain's anchor, naming both hashes.
- `Genesis.start` gives the anchor for an anchored chain and mines a new genesis block otherwise;
  `mine` and `node` start chains through it. A node with an empty file on an anchored chain starts
  from the anchor, with no trust in a peer for its first block.
- `lethenon genesis [--testnet] --wallet <file>` mines a candidate, prints its hash and canonical
  bytes, and writes nothing. Since #148 it takes `--headline` instead of `--wallet`, and a build
  without an anchor for the main chain refuses to start one (ADR 0005).
- `ConsensusRules.LETHENON` carries no anchor for either chain.


### The node command

`lethenon node --chain <file> --listen <port> [--peer host:port ...] [--mine --wallet <file>] [--for <seconds>]`
- It serves the chain file and relays.
- With `--mine` it mines on its pool and pays the wallet. If the chain file is empty, it starts a
  test chain, which is the same as `mine --testnet`. A block carries the longest prefix of the
  waiting transfers that fits the block size limit (#99). Mining runs in rounds of 100,000
  attempts; between rounds the node looks at its tip, so a block from a peer stops work on a tip
  that is no longer the tip (#94).
- Without `--mine`, a node with an empty chain file takes the genesis block from the first
  configured peer that answers, then synchronises.
  - It reads the peer's HELLO, shakes hands on the genesis hash announced there, asks for block 0,
    and checks that the block hashes to that announcement, is a test-chain genesis block, and
    verifies.
  - This is trust on first use. The peers are a fixed list the operator chose, and it is a test
    chain. Everything after the genesis block is verified as on any node. A node that should not
    trust its peers for the genesis block starts from a chain file that has it (#94).
- `--for` stops it after that many seconds; without it, it runs until interrupted.

### A sync that stops (#107)

`lethenon sync --chain <file> --peer host:port [--within <seconds>]`, and `Sync.once` in the
library, bring a chain file up to one node's tip and stop. Between copying a file and running a
node, this is what a wallet, a script or the desktop plugin needs.

- It runs a node in memory that neither listens nor discovers peers, connects it to the one peer,
  and synchronises as above. Every block is verified by the same replay.
- It stops when its chain holds the tip the peer announced in its HELLO, or carries at least the
  work the peer announced. A file ahead of the peer is left as it is.
- The chain file is written once, at the end, and only when the chain grew. A sync that fails
  writes nothing: the peer cannot be reached, refuses the handshake, breaks off, or has not handed
  over its tip within the time (default 300 s). The pending file is not touched.
- An empty file starts as a node's does, from the anchor fixed in the code or, without one, from
  the peer's genesis block (trust on first use).
- It reads no wallet and asks for nothing but the chain. A balance is still computed from the file.

## Toward a main chain

The prerequisites are built and tested on the test chain. For the main chain they wait on
decisions, not on code:

| Prerequisite | State on `lethenon-test-2` | What the main chain needs |
|---|---|---|
| bound on future timestamps | two hours, from height 0 (#96) | done: two hours on the main chain too, from height 0 (#109) |
| block size limit | 300,000 bytes, from height 0 (#99) | done: 300,000 bytes on the main chain too, from height 0 (#109) |
| peer exchange | protocol version 2 (#102) | nothing chain-specific; a node still runs only on the test chain |
| genesis block in the code | the mechanism, no anchor (#104) | the mechanism in 0.4.0, every genesis reward burned to `Genesis.NOBODY` (#148); the anchor of `lethenon-2` is mined on the start day and released as 1.0.0 (ADR 0005) |
| a chain identifier of the rules from 0.4.0 on | `lethenon-test-2`; `lethenon-test-1` refused as started under the rules before 0.4.0 (#137) | `lethenon-2`; `lethenon-1` refused the same way, so no version computes another chain's balances without a word (#137) |
| an address book that failed dials do not drain | a failed attempt waits and counts, an address is dropped only after repeated failures over time; the book lives in memory (#296, from 0.10.0) | a book that survives a restart, and Bitcoin Core's buckets by source group with its terrible-keeping, so that whoever answers fastest or makes dials fail for a while cannot shape what a node knows; option 3 of #296, a prerequisite before a node runs on the main chain |

The limits apply to the main chain from height 0 since #109. For main chains mined before, the
timestamp bound changes nothing, because the clock only moves on. The size limit rejects a main
chain that carries a block over 300,000 bytes: lethenon 0.2.0 mined without a byte cap, so that is
a block with 56 or more ML-DSA-65 transfers, or 1,515 or more Ed25519 transfers, paying an Ed25519
miner (5,375 and 198 bytes per transfer, 201 bytes for the block around them with the mining
suffix, measured for #109).
Existing main-chain files are not kept: since #137 the main chain is `lethenon-2`, a chain under
`lethenon-1` is refused, naming its successor, and from 1.0.0 on a main chain starts only from its
anchored genesis block (ADR 0005). The identifiers in this record were `lethenon-1` and
`lethenon-test-1` when it was accepted. A node on the main chain is a further decision, and
Tor comes before it.

## Consequences

- The chain package stays unable to reach a network. Everything that talks lives in `transport`.
- A wallet asks no node for its balance. There is no message to do so.
- **Block size is bounded on the test chain (#99).** A block larger than 300,000 bytes, as
  `CanonicalEncoding.blockSize` counts it, does not verify. Monero has no fixed maximum; it uses a
  block weight median and a reward penalty above its full reward zone of 300,000 bytes. lethenon
  pays its reward from a pre-minted pool, with a tail emission after it (#133), and has no penalty
  for a dynamic scheme to act on, so
  Monero's baseline is taken as a hard limit. Mining carries the longest prefix of the waiting
  transfers that fits, and `mine` keeps the rest waiting. The main chain has the same limit since
  #109.
- **Future timestamps are bounded on the test chain (#96).** A block whose timestamp lies more than
  two hours after the verifying node's clock does not verify. This is Monero's
  `CRYPTONOTE_BLOCK_FUTURE_TIME_LIMIT 60*60*2` (`src/cryptonote_config.h:47`), checked against the
  local clock as Monero does in `Blockchain::check_block_timestamp`
  (`src/cryptonote_core/blockchain.cpp:3813`), at the same two-minute target block time. The
  rule lives in `ConsensusRules` as the chain's `BlockLimits` and is checked by `Replay`. The
  clock only moves on, so a chain that verified once keeps verifying. The main chain has the same
  bound since #109.
- **A reorganisation is at most one `CHAIN` answer deep.** A heavier fork whose advantage only
  shows after more than 500 blocks is not followed, and two nodes on such forks stay apart. On a
  test chain that is a choice; it is not one for a chain with value (#88).
- **Peer exchange without Tor.** Addresses travel in the clear and name the hosts of listening
  nodes (#102). Tor comes later as a transport.
