Not legal advice. Last reviewed 2026-10-08. Check with a lawyer before a public launch.

# Lethenon: technical specification

This document is called a specification, not a white paper, on purpose: under MiCA a crypto-asset
white paper that is drawn up voluntarily for an offer exempt from the obligation to publish one
brings the whole of Title II with it (Regulation (EU) 2023/1114, Article 4(8); see
[regulatory-overview.md](regulatory-overview.md)). It describes how the software works. It is not
an offer and says nothing about value.

It describes `develop` as of 2026-10-08 - lethenon 0.3.0 and the 0.4.0 cycle, with the consensus
break of 0.4.0 that the integration branch `consensus/lethenon-2` collects (#138) - and points to
the code, the tests and the decision records instead of copying them. Where this text and the code
differ, the code is right, and this text is the one to fix.

## 1. What lethenon is

A protest chain against the politics of surveillance: a joke currency whose point is a working
demonstration of verification without an observer, post-quantum signatures from the first block,
recovery without custody, and a memo signed into every transaction ([README](../../README.md),
"What it demonstrates"). It never asks anybody for a balance (section 10) and hides the recipient of
a payment to a published address (section 5); it does not hide the sender or the amount of a
transfer, which are public on the chain (sections 5 and 14). The library is published to Maven
Central; the coin is not traded, and nothing about it is sold ([README](../../README.md), "What it
is not"). The decisions behind it are
issues [#1](https://github.com/astrapi69/lethenon/issues/1) (reasoning) and
[#2](https://github.com/astrapi69/lethenon/issues/2) (milestones).

## 2. Units and supply

| | | where |
|---|---|---|
| unit | 1 LETH = 10^8 lethe; all arithmetic in integer lethe | `Amount` |
| genesis supply | 1,984,000,000 LETH, held at the start in the mining pool | `Emission.GENESIS_SUPPLY`, `Emission.MINING_POOL` |
| block reward | a millionth of the pool, for every block including the genesis block: 1,984 LETH first, then declining; half the pool is paid out after about 2.6 years | `Emission.EMISSION_DIVISOR`, `Emission.rewardFor`, `EmissionTest` |
| tail | never less than 66 LETH a block; where the pool's share is smaller, the difference is minted, from block 3,403,214, after about 12.9 years. 0.8748 % of the genesis supply a year, against Monero's 0.8702 % at the start of its tail (#133) | `Emission.TAIL_REWARD`, `EmissionSchedule`, `TailEmissionTest` |
| pre-allocation | none: nobody holds anything before the first block is mined (#111); every genesis reward is burned (section 8) | `GenesisAllocationTest`, `BurnedGenesisTest` |
| invariant | the balances add up to the genesis supply plus what was minted, after every block | `ChainState.supply`, `ChainState.minted`, `TailEmissionTest` |

**Why 1984.** The number refers to George Orwell's novel *Nineteen Eighty-Four*, about total
surveillance, and the supply carries it as the project's protest. The supply is 1,984 times a million
rather than 1,984 because its magnitude has to sit between two bounds: a supply of 1,984 LETH would
leave balances reading 0.000012, and in lethe the genesis supply, 1.984 x 10^17, stays a factor of 46
below the 9.22 x 10^18 of a 64-bit `long`, so integer arithmetic suffices (#2, `Amount`). The block
reward is a millionth of the pool so that the first block pays exactly 1,984 LETH (#133,
`EmissionTest`). And the wallets derive their keys under `m/1984'` for the same reason (`Wallet`,
#2). That path is a recovery format and never changes: a seed phrase written down today has to
restore the same keys under every later version.

The supply is not fixed: the tail adds at most 66 LETH a block, so a 64-bit integer of lethe lasts at
least 5,200 years (`Amount`). The calculation, with its script, is in #133. Years here are 365.25
days of two-minute blocks, 262,980 blocks.

## 3. Accounts and transfers

An account model with a nonce per account, not UTXO: one signature per transfer instead of one per
input, which is what makes post-quantum signatures affordable (README, "The planned shape"). A
transfer names its chain, its nonce, its sender, its recipient, its amount, its fee and a memo, and
the memo is signed with it (`TransactionBody`, `Transfers`). Fees go into the pool, so a fee reaches
the miners of the following blocks a share at a time, and in the tail lowers what is minted. Waiting
transfers are kept in a bounded pool of at most 5,000 (`TransactionPool.LIMIT`).

## 4. Signatures

Two suites: Ed25519 by default, ML-DSA-65 (FIPS 204) selectable per transfer, and the suite's
identifier is inside the signed bytes (`SignatureSuite`, `SigningPayload`). The primitives come from
the family's libraries over Bouncy Castle - crypt-api for the algorithm names, mystic-crypt for
signing and verifying - not from this project. Evidence: `KnownAnswerTest` (RFC 8032 vector 1, and
the FIPS 204 key and signature sizes), `PostQuantumSuiteTest` (a tampered ML-DSA-65 transfer is
refused; a signature of one suite does not verify as the other; one chain carries both),
`SigningPayloadTest`.

## 5. Recipients

Two destination schemes (`AddressScheme`): `direct`, an account key in the clear, and
`stealth-v2`, a one-time destination derived by the sender from the recipient's published address,
`P = S + H(s)*B`, found by the recipient with the X25519 view key and spent with the blinded spend
key (`OneTimeAddresses`, `PublishedAddress`, `WalletScan`, `Sweeps`, #21). Evidence:
`OneTimeAddressesTest`, `StealthFundsCanBeSpentTest`, `WalletScanTest`, `SweepsTest`.

Only `stealth-v2` hides a recipient. A `direct` transfer names its recipient in the clear, and so does
a block: its beneficiary is the miner's Ed25519 spend key (`BlockBody`, `MineCommand`). Under both
schemes the sender and the amount of every transfer are public.

What this hides and what it does not is stated in the README, "Privacy, and its honest label":
post-quantum in its authenticity, classical in its hidden recipient, open about sender and amount.

## 6. Blocks and proof of pun

A block carries its chain identifier, height, the previous block's hash, its beneficiary, its
transfers, a timestamp, its difficulty and a pun (`BlockBody`). Its hash is SHA-256 over its
canonical encoding with the Merkle root of its transfers in place of the transfers (`Blocks.hashOf`,
`Blocks.merkleRoot`). It is valid when that hash has at least `difficulty` leading zero bits
(`Blocks`); mining varies the pun rather than a nonce (`Mining`, README "The planned shape"):

| | | where |
|---|---|---|
| minimum difficulty | 8 bits | `DifficultyRule.MINIMUM` |
| target | two-minute blocks | `DifficultyRule.TARGET_BLOCK_MILLIS` |
| adjustment | at most 2 bits every 30 blocks | `DifficultyRule.INTERVAL`, `MAXIMUM_STEP` |
| timestamp | later than the median of the 11 before | `DifficultyRule.MEDIAN_SPAN`, #24 |
| fork choice | the most cumulative work, on the test network | `ChainWork`, ADR 0003 |

Evidence: `BlocksTest`, `MiningTest`, `DifficultyRuleTest`, `ChainWorkTest`.

## 7. Consensus rules

One table per chain (`ConsensusRules`): which scheme is admitted from which height (`SchemeActivation`,
ADR 0001 and [ADR 0002](../adr/0002-privacy-building-blocks.md)), the block limits - at most
300,000 bytes per block and timestamps at most two hours ahead of the verifying node's clock, on both
chains from height 0 (`BlockLimits`, #96, #99, #109) - at most one genesis block fixed in the code
per chain (`GenesisAnchor`, #104), filed for the main chain on its start day (section 8) - and the emission schedule,
the same on both chains
(`EmissionSchedule`, section 2). New cryptography enters only under the rules of
[ADR 0001](../adr/0001-new-cryptographic-constructions.md): a peer-reviewed publication with its
proof, the authors' test vectors, the test chain first, the main chain after an external review.
Evidence: `ConsensusRulesTest`, `BlockSizeTest`, `FutureTimestampTest`, `GenesisAnchorTest`.

## 8. Chain identity

Two chains, the main chain and the test chain, and the identifier is inside every block and every
signed transfer, so nothing signed for one is accepted on the other (`Chain`, `ChainIdentifierTest`,
#50). They are `lethenon-2` and `lethenon-test-2`, and a chain under the identifiers before them,
`lethenon-1` or `lethenon-test-1`, is refused with the reason that it was started under the rules
before 0.4.0 (#137).

Every genesis block, on both chains, pays its reward to `Genesis.NOBODY`, the burn account: a chain
whose block 0 pays anybody else is refused, a transfer from it is refused, and its bytes are no key
of any signature suite ([ADR 0005](../adr/0005-main-chain-genesis.md), `BurnedGenesisTest`,
`NobodyIsNoKeyTest`). The main chain starts from a genesis block fixed in the code, mined on its
start day from a headline of that day and released as 1.0.0; until then this build has no anchor
for it, `mine` refuses to start one, and the replay refuses every chain under the main chain's
identifier, whoever mined its genesis block (#161, `GenesisAnchorTest`). The test chain has no anchor, and a test chain is started
by whoever runs `mine --testnet`; its block 1 pays the first wallet that mines on it.

## 9. Encoding and files

One canonical encoder for blocks (version 2) and transfers (version 1), with a round-trip property
test (`CanonicalEncoding`, `CanonicalEncodingTest`). A decoder that is handed bytes from a file or a
peer checks every announced length against what is left before allocating (#80,
`UntrustedBytesTest`). A chain lives in one file and its waiting transfers next to it in
`<chain>.pending` (`ChainFile`, `ChainFileTest`). A fixture written by an earlier process, on the
test chain, is replayed from its bytes alone, and a corrupted one refused (`ChainFixtureTest`).

## 10. Replay

`Replay.verify` checks a chain from its genesis block: every block hash and difficulty, every
signature, every nonce and balance, the scheme activations and block limits, the anchor where one
exists - and for the main chain that one exists (#161) - and the supply invariant after every block. Nothing is taken from anybody: a balance is
computed from the chain, and the chain package cannot name a networking type at all
(`NoBalanceQueryTest`). Evidence: `ReplayTest`, `ChainFixtureTest`.

## 11. Wallet

A seed phrase (BIP-39 word list) with hierarchical derivation of the spend and view keys
(`SeedPhrase`, `DeterministicKeys`, `Wallet`), a wallet file sealed with a password in the envelope
of mystic-crypt (`WalletFile`, `LETHWF`), and Shamir splitting of the seed from mystic-crypt
(README, "What it demonstrates"). Evidence: `SeedPhraseTest`, `DeterministicKeysTest`,
`WalletFileTest`, `RecoveryAcceptanceTest`.

## 12. Network

The test network only ([ADR 0003](../adr/0003-test-network.md)): TCP, a `HELLO` with the magic
`LETHENON` and protocol version 3 (`Hello`), frames of at most 4 MiB (`Frames.MAXIMUM_FRAME`), at
most 12 outgoing and 16 incoming connections (`Node`), peer exchange (#102), relay of blocks and
transfers, fork choice by work, a one-shot sync (`Sync`, #107) and the handover of a transfer to a
node (`Handover`). Tor as a transport ([ADR 0004](../adr/0004-tor-transport.md)): a SOCKS5 proxy for
every outgoing connection, an anonymity zone for a node's own transfers, an onion service for its
zone peers; run against a real Tor by [docs/tor.md](../tor.md) (#112). The tests are in
`src/test/java/io/github/astrapi69/lethenon/transport`. There is no main-chain network yet.

## 13. Command line

`lethenon` with the subcommands `wallet`, `balance`, `send`, `sweep`, `faucet`, `mine`, `genesis`,
`node` and `sync` (`src/main/java/io/github/astrapi69/lethenon/cli`); the README, "Command line",
shows them in use.

## 14. Not built

Amount confidentiality and sender ambiguity, and a post-quantum hidden recipient: looked at and
postponed for want of a construction that meets ADR 0001 (README, "Privacy, and its honest label";
`docs/research/pq-privacy-literature.md`, sections 15 and 16). A network for the main chain.
