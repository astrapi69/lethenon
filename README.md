# Lethenon

<div align="center">

[![Java CI with Gradle](https://github.com/astrapi69/lethenon/actions/workflows/gradle.yml/badge.svg)](https://github.com/astrapi69/lethenon/actions/workflows/gradle.yml)
[![Open Issues](https://img.shields.io/github/issues/astrapi69/lethenon.svg?style=flat)](https://github.com/astrapi69/lethenon/issues)
[![MIT license](http://img.shields.io/badge/license-MIT-brightgreen.svg?style=flat)](http://opensource.org/licenses/MIT)

</div>

A joke currency with a serious point: **a protest against the politics of surveillance**, made of
the only argument software can actually make - a working demonstration of what a currency can
refuse. It never asks anybody for a balance, it hides the recipient of a payment to a published
address, and its test network can run over Tor. It does not hide who pays or how much: the sender and the
amount of every transfer are public on the chain, because no proven post-quantum way to hide them
exists yet ([Privacy, and its honest label](#privacy-and-its-honest-label)).

## Status

Milestones 1 to 3 of [#2](https://github.com/astrapi69/lethenon/issues/2) are built - canonical
encoding, Ed25519 and ML-DSA-65 signatures, proof of pun, a replay that verifies a chain from its
bytes alone, one-time destinations and a balance computed from the chain - and milestone 4, a
wallet that can be recovered, comes with a command line (below). Money paid to a one-time
destination can now be spent again as well
([#21](https://github.com/astrapi69/lethenon/issues/21)): the destination is the one-time public
key `P = S + H(s)*B`, so a transfer out of it is verified by the same rule as any other, and only
the holder of the spend key can sign it. Remaining gaps are open issues. The plan and the decisions behind it are
in the issues: [#1](https://github.com/astrapi69/lethenon/issues/1) is the brainstorm with the
reasoning, [#2](https://github.com/astrapi69/lethenon/issues/2) the cut into five milestones.

The **library** is on Maven Central; the **coin** is not sold and will not be - nobody can buy it,
there is no liquidity pool and no listing, and a tradeable joke is a financial product with real
losers.

```groovy
implementation "io.github.astrapi69:lethenon:0.4.0"
```

Milestone 5, the desktop plugin, lives in
[mystic-crypt-ui#450](https://github.com/astrapi69/mystic-crypt-ui/issues/450) and consumes exactly
this library.

### Command line

`./gradlew installDist` builds `build/install/lethenon/bin/lethenon`. Two kinds of file: the chain
file, which anybody can replay, and a wallet file, which only its password opens. A password is
never an argument; it is the first line of standard input.

```
lethenon wallet create  --wallet holder.wallet          # prints the account and the 24 words
lethenon wallet restore --wallet again.wallet           # stdin: the 24 words, then a new password
lethenon mine    --testnet --chain test.lethenon --wallet holder.wallet   # block 0 of a test chain, its reward burned
lethenon mine    --chain test.lethenon --wallet holder.wallet   # block 1, paying this wallet
lethenon faucet  --chain test.lethenon --wallet holder.wallet --to <account>   # 1,000 LETH from the wallet block 1 paid
lethenon send    --chain chain.lethenon --wallet w.wallet --to <account> --amount 12.5 --memo "..."
lethenon send    --chain chain.lethenon --wallet w.wallet --to-address <view:spend> --amount 12.5
lethenon sweep   --chain chain.lethenon --wallet w.wallet       # one-time payments onto the account
lethenon balance --chain chain.lethenon --wallet w.wallet       # replays the chain, asks nobody
```

`--to` names an account and the chain shows who was paid; `--to-address` names the published
address that `wallet create` and `balance` print, and the transfer then goes to a one-time
destination derived from it, so two payments to the same address land on destinations that have
nothing visibly in common. The sender and the amount are visible either way.
`sweep` is the other end of that: it moves what was paid to those destinations onto the account,
one transfer per destination, and says out loud what that costs - the chain then shows those
destinations and the account together. Receiving is unlinkable; spending is the moment that ends.

There are two chains, `lethenon-2` and the test chain `lethenon-test-2`, and the identifier is
inside every signed transfer and every block, so nothing signed for one is accepted on the other.
A chain under the identifiers before them, `lethenon-1` or `lethenon-test-1`, was started under the
rules before 0.4.0 and is refused with that reason: under today's rules its balances would come
out differently (#137).
`--testnet` chooses the test chain when `mine` writes the genesis block; from then on the genesis
block decides, every command follows it, and `--testnet` on a main chain is refused. Every genesis
block pays its reward to a burn account, `Genesis.NOBODY`, from which no transfer may spend, so
nobody holds anything before somebody mines block 1 ([ADR 0005](docs/adr/0005-main-chain-genesis.md)).
The main chain is never mined anew: its genesis block is fixed in the code on its start day and
released as 1.0.0, and until then `mine` without `--testnet` refuses to start one. On a test chain
block 1 pays the first wallet that mines on it, which is where the faucet pays from. A chain under `lethenon-1` or `lethenon-test-1`, the identifiers
before 0.4.0, is refused with a message that names its successor (#137). A new
cryptographic scheme runs on the test chain first ([ADR 0001](docs/adr/0001-new-cryptographic-constructions.md)).

`send`, `faucet` and `sweep` sign transfers that wait in `chain.lethenon.pending`; the next `mine` puts
every waiting transfer into a block, replays the result and only then writes it. Exit codes: 0
done, 1 refused with the reason on standard error, 2 a command line that was not understood.

### A test network

The test chain has a network ([ADR 0003](docs/adr/0003-test-network.md)): TCP, peers given on the
command line and the addresses they pass on (#102), and Tor as a transport
([ADR 0004](docs/adr/0004-tor-transport.md)): a SOCKS5 proxy for every outgoing connection, an
anonymity zone for a node's own transfers, and an onion service for its zone peers. The Tor
transport is tested against a SOCKS5 server in the test sources and has run against a real Tor,
0.4.9.11, by the runbook [docs/tor.md](docs/tor.md): two nodes reached each other only through
onion services, and an own transfer travelled only through the zone (recorded in #112). A node
runs only on `lethenon-test-2` and refuses a main
chain. There is no message that asks a node for a balance; a wallet still computes its own from
a chain file.

```
lethenon node --chain a.lethenon --listen 18431 --mine --wallet miner.wallet   # starts a test chain and mines
lethenon node --chain b.lethenon --listen 18432 --peer 127.0.0.1:18431         # takes the chain from a
lethenon send --chain a.lethenon --wallet w.wallet --to <account> --amount 7 --node 127.0.0.1:18431
lethenon balance --chain b.lethenon --wallet friend.wallet                      # from b's own copy
lethenon genesis --testnet --headline "..."             # a candidate genesis block, its hash and bytes
lethenon sync --chain c.lethenon --peer 127.0.0.1:18431  # brings c up to a's tip, verified, and stops
lethenon sync --chain c.lethenon --peer <56 characters>.onion:18431 --proxy 127.0.0.1:9050   # the same over Tor
lethenon node --chain d.lethenon --listen 18431 --tx-proxy tor,127.0.0.1:9050 \
    --anonymous-inbound <56 characters>.onion:18484,127.0.0.1:18484   # own transfers only over Tor, reachable as an onion service
```

A node keeps its chain file and `<chain>.pending` up to date and owns them while it runs.
`send --node` hands the transfer to the node that serves the file named by `--chain`. A node
with an empty file takes the genesis block from the first peer that answers; everything after
it is verified block by block. The longer chain by cumulative work wins, and a fork is followed
up to 500 blocks deep. While it runs, a node prints a status line once a minute (`--status-every`,
0 for none): height, tip, peers, waiting transfers and the number of refusals, without their
addresses.

The chain is called Lethenon, from
[Lethe](https://en.wikipedia.org/wiki/Lethe), the river of forgetting. The name is the aim, not a
description: the chain is a permanent record like any other, and it keeps the sender and the amount
of every transfer. The name was checked against GitHub, Maven Central, npm, PyPI, crates.io and
the coin listings before it was picked, because a name collision is cheaper to avoid than to fix.

## What it is not

**Lethenon is not traded. Nobody can buy it, there is no liquidity pool, no listing, no promotion,
no sale.**
The moment a joke currency becomes tradeable it is a financial product with real losers, and in the
European Union it falls under MiCA. Nothing about the point of this project depends on that, so it
does not happen. If you found your way here expecting an investment, there is nothing here for you,
and that is deliberate.

## What it demonstrates

- **Verification without an observer.** Anybody can replay the chain and check every signature and
  every state transition themselves. Nothing has to be taken on trust from a central party, and the
  check itself requires no identity.
- **Post-quantum signatures from the first block.** A chain keeps its public keys for good, and a
  quantum computer that can forge today's signatures could spend what is not its own. ML-DSA-65
  (FIPS 204) can sign any transfer from block 0; the default is Ed25519, which is not post-quantum.
  Measured with OpenSSL 3.5.5: an Ed25519 signature is 64 bytes, an ML-DSA-65 signature 3309 - the
  cost of the answer, stated rather than hidden. "Harvest now, decrypt later" - recording encrypted
  traffic to break it when the machine exists - is a different threat, and a signature does not
  answer it.
- **Recovery without custody.** A seed with hierarchical derivation, and Shamir splitting of that
  seed, so a backup can live across people or places without a company holding the keys.
- **The protest is inside the chain.** Every transaction carries a memo that is signed with it, so
  the text is part of what gets verified rather than metadata beside it.

## The coin

| | |
|---|---|
| Supply | **1,984,000,000 LETH at genesis**, plus a small tail emission after about 12.9 years ([#133](https://github.com/astrapi69/lethenon/issues/133)). 1984 is the protest in the amount itself: the year of George Orwell's novel *Nineteen Eighty-Four*, about total surveillance. Why the number is 1984 times a million, and where else it appears, is in the [specification](docs/launch/specification.md), section 2 |
| Base unit | the **lethe**; 10^8 lethe = 1 LETH |
| Why that magnitude | the genesis supply is 1.984 x 10^17 base units against a 64-bit signed integer's 9.22 x 10^18 - a factor of 46 of headroom, so integer arithmetic suffices and no `BigInteger` is needed. The tail adds at most 66 LETH a block, which leaves at least 5,200 years |
| Mining reward | **a millionth of the pool per block**, out of a pre-minted pool holding the whole genesis supply: the first block pays exactly 1,984 LETH, and the reward declines from there - half the pool is paid out after about 2.6 years. It never falls below the **tail reward of 66 LETH**, which a block reaches at height 3,403,214, after about 12.9 years. A fee goes into the pool and reaches the miners a share at a time |
| Genesis | **no pre-allocation.** The genesis block is paid one block reward out of the pool, like every other block, on both chains ([#111](https://github.com/astrapi69/lethenon/issues/111)). Nobody holds anything before the first block is mined |
| Inflation | none for the first 12.9 years, then a tail after Monero's: where the pool's share falls below 66 LETH, the difference is minted. 66 LETH a block is 0.8748 % of the genesis supply a year, against Monero's 0.8702 % at the start of its tail; the minted part starts near zero and approaches that as the pool empties. The sum of all balances equals the genesis supply plus what was minted after every block, and that is an assertion, not a promise. The calculation, with its script, is in [#133](https://github.com/astrapi69/lethenon/issues/133) |

## Privacy, and its honest label

Privacy is four different things here, and what is in and what is out is stated rather than implied.

**In:** one-time addresses derived by the sender from the published address, so two payments to the
same address land on unrelated destinations; a wallet that never asks a server for the balance of an
address, because that query is how most light wallets expose their users; transport over Tor; and no
sign-up, no identity check, no telemetry.

**Built of that so far:** the one-time destinations, spendable as well as receivable, and a balance
that is computed instead of requested. `WalletScan` replays the chain and recognises its own
payments with the view private key alone; `Wallet.oneTimeKey` blinds the spend key into the key
that moves such a payment, which is the additive construction Monero uses, built in mystic-crypt
rather than here (#21); and `NoBalanceQueryTest` keeps the whole chain package unable to name a
networking type, so the property holds for every run rather than for one captured one. The Tor
transport has run against a real Tor ([docs/tor.md](docs/tor.md), #112).

**Not built, and postponed:** amount confidentiality and sender ambiguity. The sender is a public
key in the clear and the amount a plain number in every transaction. Both need constructions that
come in only under [ADR 0001](docs/adr/0001-new-cryptographic-constructions.md): a peer-reviewed
publication with its proof, the authors' test vectors, the test chain first, and the main chain
only after an external cryptographic review. The phase C pre-check (#74) found no published scheme
for hidden amounts that meets those rules and fits an account model: the papers that hide amounts
are built for one-time coins, their code is missing, unlicensed or GPL, or their patent position is
unclear, and the one path no rule excludes would be a combination of our own over a lattice proof
system, with a classical proof at best. That is not built without a cryptographer's security
argument. Sender ambiguity waits for the same reason and, for MatRiCT+, for its patent position.
The details are in `docs/research/pq-privacy-literature.md`, sections 15 and 16.

**A post-quantum hidden recipient: looked at, and postponed.** The one-time destinations stay as
they are: Ed25519 one-time keys found with an X25519 view key, which is pre-quantum. The phase C
pre-check (#72) found no construction that replaces them under the project's rules. Maram and
Xagawa (PKC 2023) prove in the quantum random oracle model that a Kyber ciphertext does not reveal
its recipient, but for Kyber round 3, whose encapsulation FIPS 203 changed in exactly the steps the
proof models (FIPS 203, Appendix C.1), and the JDK offers only ML-KEM; nor does that result give a
one-time ML-DSA key that only the recipient can spend. The one published construction that does,
SPIRIT (CCS 2023), is proven in the classical random oracle model only, changes Dilithium's
parameters, and ships code without a licence, so ADR 0001 excludes it (rules 5, 6 and 8). The
details are in `docs/research/pq-privacy-literature.md`, section 15.

**The label, because somebody would otherwise have to find it out:** Monero's privacy rests entirely
on Ed25519 mathematics - ring signatures, stealth addresses and RingCT are all discrete-logarithm
constructions. ML-DSA gives none of that; it is a signature scheme, not a toolkit for rings or
commitments. So Lethenon is **post-quantum in its authenticity, classical in its hidden recipient,
and open about sender and amount**: transactions are signed with ML-DSA-65 or Ed25519; a payment to
a published address hides its recipient behind an Ed25519 one-time key found with an X25519 view
key, while a transfer to an account (`--to`) and the reward of a block name theirs in the clear; and
the sender and the amount are visible to anybody who reads the chain. The reason is the state of research, not a choice
against privacy: the literature survey records, for every candidate, which rule of ADR 0001 it does
not yet meet. The direction that would join the two halves is post-quantum proofs with a quantum
proof model, and that is a research programme rather than a milestone.

## The planned shape

Decided in [#1](https://github.com/astrapi69/lethenon/issues/1), with the reasoning there:

| Decision | Choice |
|---|---|
| Model | accounts with a nonce, not UTXO - one signature per transaction instead of one per input, which is what makes post-quantum signatures affordable |
| Consensus | **proof of pun**: SHA-256 proof of work with wordplay in place of a nonce. A block is valid when the SHA-256 hash of its canonical encoding has at least as many leading zero bits as its difficulty, and a miner varies the pun, with a counter appended, until it has. The difficulty starts at 8 bits and steps by at most 2 bits every 30 blocks towards two-minute blocks, timestamps later than the median of the 11 before ([#24](https://github.com/astrapi69/lethenon/issues/24), after Bitcoin's rules). Fork choice by most cumulative work on the test network ([ADR 0003](docs/adr/0003-test-network.md)). At the test network's difficulty a CPU is enough. The work function is not memory-hard: it resists neither GPUs nor mining-specific hardware ([#186](https://github.com/astrapi69/lethenon/issues/186)). That nobody builds such hardware for LETH rests on LETH not being traded, an economic reason, not a cryptographic one |
| Signatures | Ed25519 by default, ML-DSA-65 selectable per transaction, the suite identifier inside the signed bytes |
| Encoding | one canonical encoder, a round-trip property test, a format version in every persisted structure |
| Arithmetic | integers only, `Math.addExact` and `Math.subtractExact`, and the supply invariant checked after every block |
| Proof it works | a replay verifier: a fresh process checks a chain from genesis and prints what it verified, and a deliberately corrupted fixture it must reject |

Built on the libraries this family already publishes - [mystic-crypt](https://github.com/astrapi69/mystic-crypt),
crypt-data and crypt-api over Bouncy Castle. This project writes formats and rules, and invents no
primitives; when it uses a new construction, [ADR 0001](docs/adr/0001-new-cryptographic-constructions.md) says on what terms.

## Milestones

1. one signed transaction, one block, and a replay that proves it - with one-time addresses in the
   format from the start, because an address format is a hard fork to change later
2. the same chain, post-quantum: both suites in one chain, with their measured sizes
3. recipient unlinkability: one-time addresses end to end, no balance lookups, Tor transport
4. a wallet that can be recovered from its Shamir shares alone
5. an internal plugin for [mystic-crypt-ui](https://github.com/astrapi69/mystic-crypt-ui), once
   milestone 4 is green

## Verifying the release signatures

The releases on Maven Central are signed with the OpenPGP key

```text
5B2F A6B1 1E29 8BC0 9287  F423 D8C4 0351 8C49 CA75
```

Releases after 0.4.0 are signed by its signing subkey

```text
6D67 F844 2A6F CC96 BD7C  1B5E 9FCF 7C97 10E2 BD8D
```

valid until 2028-10-09; 0.4.0 and earlier were signed by the primary key itself. The key, with the subkey, is on keyserver.ubuntu.com and keys.openpgp.org. How the key is kept, with its primary key offline, is in [docs/launch/release-key.md](docs/launch/release-key.md).

To check a download, fetch the key once, then verify the signature that lies next to each file on
Maven Central:

```sh
gpg --keyserver hkps://keys.openpgp.org --recv-keys 5B2FA6B11E298BC09287F423D8C403518C49CA75
gpg --verify lethenon-0.4.0.jar.asc lethenon-0.4.0.jar
```

gpg answers `Good signature` and prints the `Primary key fingerprint`, for a release signed by the
subkey also the `Subkey fingerprint`. Both have to be the ones above. The warning that the key "is
not certified with a trusted signature" only says that you have not marked the key as trusted in your
own keyring; the fingerprints are what to compare.

## Releasing

What a release consists of, in the order it is run (0.2.0 was the first one run this way):

1. **One pull request** from `release/X.Y.Z` with `projectVersion` without `-SNAPSHOT`, the CHANGELOG
   heading without "(unreleased)", the README's dependency line, and a FORMAT section that says what the
   previous release does with what this one writes - measured against the published previous release,
   not assumed.
2. **The consumer, against the candidate**: mystic-crypt-ui built against the candidate laid into
   `~/.m2` by hand (jar and pom, nothing signed), with `make bump-check GRADLE_FLAGS=-PuseMavenLocal`.
   The candidate is deleted from `~/.m2` afterwards.
3. **Nothing else is merged into `develop`** until the tag exists. At 0.2.0 a feature pull request was
   merged 28 seconds before the release, so the merge carried code the release had not checked; the
   tag went on the release commit instead. Since then the freeze is enforced: the required check
   `release-freeze` is red on every other pull request into `develop` while a pull request from a
   `release/*` branch is open, and a change that belongs to the release carries the label
   `release-content` (astrapi69/mystic-crypt-ui#516).
4. **Tag the release commit** with an annotated `RELEASE-X.Y.Z` and push the tag. The publish workflow
   uploads to the Central Portal, where the maintainer releases it. A pushed tag is never moved or
   deleted; a failed run is fixed on the branch and re-run.
5. **Verify on Central**, with the artifacts downloaded inside the command that checks them: the jar,
   pom, `-sources.jar`, `-javadoc.jar`, `.module`, and `gpg --verify` on each `.asc`.
6. **GitHub release** from the CHANGELOG entry, without binaries: this is a library.
7. **`master` follows the release**: a fast-forward to the tagged commit
   (`git push origin "RELEASE-X.Y.Z^{}:master"`), otherwise a merge of the tag on `master`, never a
   force push. Afterwards `git diff RELEASE-X.Y.Z master` is empty. (`master` exists since 0.2.0; the
   2021 template branch `main` was deleted.)
8. **Open the next cycle**: `projectVersion` to the next `-SNAPSHOT`, and a CHANGELOG section
   "(unreleased)" for whatever `develop` already carries beyond the tag.
9. **The consumer follows**: mystic-crypt-ui's catalog moves to the release, with `make bump-check`.

## License

MIT.

## Want to help?

Issues and pull requests are welcome at the
[issues page](https://github.com/astrapi69/lethenon/issues). Pull requests go against `develop`,
with tests.

## Donations

If you like this project, a donation goes through
[PayPal](https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=B37J9DZF6G9ZC).

The cryptocurrency donation addresses that used to stand here are deliberately gone from this one
repository: on a project that is itself a coin, an address in the README reads like a way to buy
in, and there is nothing to buy.
