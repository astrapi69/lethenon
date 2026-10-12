Not legal advice. Last reviewed 2026-10-08. Check with a lawyer before a public launch.

# Lethenon: messaging guide

What may be said about lethenon in public, what may not, and the one rule behind both: a technical
statement points to the test or the decision record that shows it. The allowed statements below are
true of `develop` as of 2026-10-10; each names its evidence, so whoever repeats one can check it and
see when it stops being true. Why some statements are off limits is in
[regulatory-overview.md](regulatory-overview.md).

## The rule

**Every technical statement links to a test or an ADR.** A statement that has neither is not made
until one exists. The link goes in the text, not in a footnote nobody follows. A statement whose
test is deleted, or whose ADR is superseded, is withdrawn in the same change.

## Allowed, with their evidence

| Statement | Evidence | Say with it |
|---|---|---|
| Transfers can be signed with ML-DSA-65 (FIPS 204), a signature scheme standardised to resist quantum computers | [`KnownAnswerTest`](../../src/test/java/io/github/astrapi69/lethenon/KnownAnswerTest.java) (FIPS 204 key and signature sizes), [`PostQuantumSuiteTest`](../../src/test/java/io/github/astrapi69/lethenon/PostQuantumSuiteTest.java) | ML-DSA-65 is chosen per transfer, and the default is Ed25519, which is not post-quantum. Post-quantum in its authenticity only: the hidden recipient uses classical X25519 and Ed25519, and sender and amount are public ([README](../../README.md), "Privacy, and its honest label") |
| No pre-allocation: the genesis block is paid one block reward, like every other block, and nobody holds anything before the first block is mined | [`GenesisAllocationTest`](../../src/test/java/io/github/astrapi69/lethenon/GenesisAllocationTest.java), #111 | - |
| The test network is open: anybody can run a node, sync a chain and verify it | [ADR 0003](../adr/0003-test-network.md), [`SyncTest`](../../src/test/java/io/github/astrapi69/lethenon/transport/SyncTest.java), [`ReplayTest`](../../src/test/java/io/github/astrapi69/lethenon/ReplayTest.java) | It is a test network: nothing is sold or asked for its coins, and nothing signed for it is accepted on the main chain ([`ChainIdentifierTest`](../../src/test/java/io/github/astrapi69/lethenon/ChainIdentifierTest.java)) |
| The code is MIT-licensed | [LICENSE](../../LICENSE) | - |
| A chain is verified from its bytes alone, without asking anybody, and the chain package cannot reach a network | [`ChainFixtureTest`](../../src/test/java/io/github/astrapi69/lethenon/ChainFixtureTest.java), [`NoBalanceQueryTest`](../../src/test/java/io/github/astrapi69/lethenon/NoBalanceQueryTest.java) | - |
| Payments to a published address land on one-time destinations that only the recipient can find and spend | [`OneTimeAddressesTest`](../../src/test/java/io/github/astrapi69/lethenon/OneTimeAddressesTest.java), [`StealthFundsCanBeSpentTest`](../../src/test/java/io/github/astrapi69/lethenon/StealthFundsCanBeSpentTest.java) | What it does not hide: the sender and the amount |
| New cryptography reaches the main chain only after a peer-reviewed publication, the test chain and an external review | [ADR 0001](../adr/0001-new-cryptographic-constructions.md) | - |
| A wallet never asks anybody for a balance: it computes its own from a chain file, and the network has no message that asks for one | [`NoBalanceQueryTest`](../../src/test/java/io/github/astrapi69/lethenon/NoBalanceQueryTest.java), [`MessageType`](../../src/main/java/io/github/astrapi69/lethenon/transport/MessageType.java) | What a wallet does not ask, any reader of the chain can still work out: the sender, the amount and the balance of every account are public |
| The test network can run over Tor: a SOCKS5 proxy for outgoing connections, an anonymity zone for a node's own transfers, an onion service for its zone peers | [ADR 0004](../adr/0004-tor-transport.md), [docs/tor.md](../tor.md), #112 | Tor hides where a node is, not what the chain says; there is no main-chain network yet |
| No sign-up, no identity check, no account with anybody: a wallet is a file and its key | [`WalletFileTest`](../../src/test/java/io/github/astrapi69/lethenon/WalletFileTest.java), [`RecoveryAcceptanceTest`](../../src/test/java/io/github/astrapi69/lethenon/RecoveryAcceptanceTest.java) | - |
| 1984 refers to George Orwell's novel *Nineteen Eighty-Four*, about total surveillance | [specification.md](specification.md), section 2 | The title and the author only, no quotation from the novel |

**Said only with the release it is true for.** "1,984,000,000 LETH, fixed" and "no inflation" were
true of 0.3.0 and are not true of 0.4.0, which adds a tail emission of 66 LETH per block (#133). From
0.4.0 on, the statement is the curve and the tail ([specification.md](specification.md), section 2),
not a fixed supply.

## Not allowed

| Do not say | Why |
|---|---|
| anything about returns, yield, profit or income from holding LETH | an investment promise; lethenon is not an investment ([README](../../README.md), "What it is not") |
| anything about the price, a rise in value, scarcity as a reason to buy, "early" holders | the same |
| "get in now", "don't miss out", any call to acquire LETH | a call to buy where nothing is for sale |
| that LETH will be listed, traded or exchangeable, or that a listing is sought | announcing an intention to seek admission to trading ends the exemption of mined crypto-assets (MiCA, Article 4(4)) |
| "airdrop" or a free distribution in exchange for an e-mail address, a sign-up or any personal data | under MiCA a crypto-asset given in exchange for personal data is not offered for free (Article 4(3), second subparagraph) |
| "white paper", for this or any document about lethenon | a voluntary white paper brings MiCA's Title II with it (Article 4(8)); the technical description is [specification.md](specification.md) |
| that lethenon protects against surveillance, keeps payments private or hides transactions, without saying in the same place that the sender and the amount are public | only the recipient of a payment to a published address is hidden ([README](../../README.md), "Privacy, and its honest label"); a protest that implies more than the software does is the kind of statement this guide exists to stop |
| "privacy coin", "anonymous", "untraceable" | untrue - the sender and the amount are public - and the words describe the "anonymity-enhancing coins" that the EU's AMLR keeps away from crypto-asset service providers from July 2027 (Articles 2(1)(25) and 79) and that Dubai's regulators prohibit; the details are in [regulatory-overview.md](regulatory-overview.md) |
| "quantum-proof", "unbreakable", "secure forever" | not what a test can show; the post-quantum part is the signature scheme, and the hidden recipient is classical |

## Lethenon and the author's books

How lethenon and the books of its maintainer, Asterios Raptis, may refer to each other. Decided by
the maintainer on 2026-10-10.

**Allowed:**

- an article about how lethenon came about, with a pointer to books on related subjects;
- a section "About the author" in the README and on a website.

**Excluded:**

- coins for books, or books for coins: no LETH given with a book or for buying one, and no book sold
  for LETH;
- a book title in a genesis block: the main chain's genesis block carries a headline of its start day
  ([ADR 0005](../adr/0005-main-chain-genesis.md));
- wording such as "joke coin that may become real": it sounds like a rise in value, which "Not
  allowed" above rules out.

## Where statements go

The README, the specification and the release notes. A statement made elsewhere - a post, a talk,
a website - carries the same link it would carry in the README.

## Sources

Retrieved 2026-10-08.

- Regulation (EU) 2023/1114 on markets in crypto-assets (MiCA), Article 4(3), 4(4) and 4(8):
  <https://eur-lex.europa.eu/eli/reg/2023/1114/oj>
- Regulation (EU) 2024/1624 on the prevention of the use of the financial system for the purposes of
  money laundering or terrorist financing (AMLR), Articles 2(1)(25), 79 and 90:
  <https://eur-lex.europa.eu/eli/reg/2024/1624/oj>
