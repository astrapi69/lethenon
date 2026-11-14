Not legal advice. Last reviewed 2026-10-08. Check with a lawyer before a public launch.

# Lethenon: launch checklist

What has to be true before the main chain starts in public. Part 1 lists what EU law (MiCA)
requires an authorisation for and what it does not, each line with the provision it rests on; the
reasoning, the AML rules and the non-EU regulators are in
[regulatory-overview.md](regulatory-overview.md). Part 2 is what the code and the project still need.
Part 3 is the tax advisor. The list is re-checked before the start (#144).

## 1. What needs an authorisation, and what does not

As the provisions read; where a line combines provisions into a reading of its own, it says so.
"Authorisation" means authorisation as a crypto-asset service provider (CASP) under Article 59 of
Regulation (EU) 2023/1114 (MiCA).

| Activity | Under MiCA | Provision | What changes it |
|---|---|---|---|
| Publishing the source code and the library, under the MIT licence | not one of the activities MiCA applies to: issuance, offer to the public, admission to trading, crypto-asset services | Article 2(1), Article 3(1)(16) | - |
| Running the open test network, its faucet included | the same; nothing is sold or asked for the test chain's coins, and nothing signed for the test chain is accepted on the main chain ([ADR 0003](../adr/0003-test-network.md), `ChainIdentifierTest`) | Article 2(1) | a faucet that asks for personal data, see the last row |
| Mining LETH on the main chain, and miners passing on what they mined | Title II (legal person, white paper, notification) does not apply to an offer of a crypto-asset "automatically created as a reward for the maintenance of the distributed ledger or the validation of transactions" | Article 4(3)(b), recital 26 | the offeror, or someone on its behalf, makes known an intention to seek admission to trading (Article 4(4)); the crypto-asset is admitted to trading (recital 26); a white paper is drawn up voluntarily (Article 4(8)) |
| Running seed nodes, relaying blocks and transfers | a transfer service "should not include the validators, nodes or miners that might be part of confirming a transaction and updating the state of the underlying distributed ledger" | recital 93, Article 3(1)(26) | - |
| Publishing the wallet, whose keys are made and kept on the user's own machine (`WalletFile`, `SeedPhrase`) | custody is "the safekeeping or controlling, on behalf of clients" of crypto-assets or of the means of access to them; the wallet holds nothing on anybody's behalf. This is a reading of the definition, to be confirmed with a lawyer | Article 3(1)(16)(a), Article 3(1)(17) | the project starts to hold keys, seeds or LETH for users |
| Custody of LETH for others, or transferring it on their behalf | crypto-asset services, but the authorisation is not required for crypto-assets whose offers are exempt under Article 4(3), unless another offer of the same crypto-asset is not exempt or it is admitted to a trading platform | Article 3(1)(16)(a) and (j), Article 4(5) | admission to a trading platform, which a platform's operator can also bring about on its own initiative (Article 5(2)), outside the project's control. lethenon offers neither service; anti-money-laundering rules are in [regulatory-overview.md](regulatory-overview.md) |
| Exchanging LETH for money or for other crypto-assets as a business; operating a trading platform; executing, receiving or transmitting orders; placing; advice; portfolio management | crypto-asset services; authorisation required, which needs a legal person with its registered office in a Member State, its place of effective management in the Union and at least one director resident in the Union | Article 3(1)(15) and (16)(b) to (i), Article 59(1) and (2) | Article 4(5) does not cover these services |
| Seeking admission of LETH to trading, or announcing the intention to | admission needs a legal person and a white paper; announcing the intention ends the exemption of the mining row | Article 5(1), Article 4(4) | - |
| Publishing a "white paper" about lethenon | the whole of Title II then applies, even to an exempt offer; the technical description is therefore [specification.md](specification.md) | Article 4(8) | - |
| Offering any of the services above to people in the EU through a company outside the EU | the authorisation covers services "within the Union"; the one exception, a service at the client's "own exclusive initiative", is lost by any solicitation in the Union "regardless of the means of communication", and a disclaimer does not restore it | Article 59(1), Article 61(1) | see [regulatory-overview.md](regulatory-overview.md) |
| Handing out LETH in exchange for an e-mail address, a sign-up or any other personal data | not "offered for free" in the sense of the free-offer exemption; the data protection rules apply to the data | Article 4(3), second subparagraph | - |

The README's [What it is not](../../README.md#what-it-is-not) - no sale, no listing, no promotion -
is the state the rows above describe as needing no authorisation. A public main chain changes its
first sentence ("Lethenon stays private"); the rest of the section is what the conditions in the
right-hand column are about.

## 2. Before the main chain starts

### One consensus break, one release (#137)

Three changes ship together, in one release, and none of them alone. They are collected on the
integration branch `consensus/lethenon-2` (#138), which carries develop's required checks.

- [ ] **New chain identifiers** `lethenon-2` and `lethenon-test-2`. A chain under `lethenon-1` or
  `lethenon-test-1` is refused, with a message that it was started under the rules before 0.4.0
  (#137; built in #140, on the integration branch; the message moved from 0.3.0 to 0.4.0 once the
  tail joined the same break).
- [ ] **The emission curve** of #133, variant (c): the reward is the pool divided by 1,000,000,
  never less than a tail of 66 LETH per block. The tail starts at block 3,403,214, about 12.94
  years after the genesis block (365.25-day years, 262,980 blocks), and from then on pays 17,356,680
  LETH a year, 0.8748 % of the genesis supply. Of that, only the part the pool's share no longer
  covers is minted: about 2.1 million LETH in the tail's first year, approaching the full amount as
  the pool empties (the figures and the script that computed them are in #133).
- [ ] **The burn account** of [ADR 0005](../adr/0005-main-chain-genesis.md), decided in #148: every
  genesis block pays its reward to `Genesis.NOBODY`, a transfer from it is refused, and the bytes are
  no key of any signature suite. 0.4.0 carries the anchor mechanism of #104 but **no anchor for the
  main chain**: without one, `mine` refuses to start `lethenon-2`. The anchor is the start day's,
  below.
- [ ] **Into develop**, right before the release: one pull request from the integration branch,
  with the full gate and the compatibility measurement against 0.1.0, 0.2.0 and 0.3.0 (#137).

### The start day (1.0.0)

The main chain starts in its own release, after 0.4.0, and only when everything above and below is
ticked ([ADR 0005](../adr/0005-main-chain-genesis.md)).

- [ ] **The headline.** On the start day the maintainer chooses a headline of that day. It goes
  into the genesis block as its words and proves the block was not mined before.
- [ ] **The block, mined with the release build.** From a checkout of the commit to be released as
  1.0.0, built with `./gradlew clean build`, run `lethenon genesis --headline "<the headline>"`. It
  prints the chain, the hash, the words, the burn account and the anchor, and writes nothing.
- [ ] **The anchor, in its own pull request.** The printed anchor is filed as the `GenesisAnchor` of
  `lethenon-2` in `ConsensusRules.LETHENON`, with a test that a chain on it replays, that `mine` on a
  new main chain writes exactly that block, and that its beneficiary is `Genesis.NOBODY`. ADR 0005
  records the hash, the headline and the day. Nothing else goes into that pull request.
- [ ] **Release 1.0.0** from the merge of that pull request. A pushed tag is never moved; replacing
  the anchor after the release is a new chain identifier.

### The network

- [ ] **A node on the main chain.** Today a node runs only on the test chain: "The main chain
  gets a network only by a later decision" ([ADR 0003](../adr/0003-test-network.md), "Only the test
  chain"). The same ADR puts Tor before it; Tor as a transport is built
  ([ADR 0004](../adr/0004-tor-transport.md), [docs/tor.md](../tor.md)).
- [ ] **The depth of a reorganisation.** A node follows a heavier fork only within one `CHAIN`
  answer, 500 blocks; ADR 0003 calls that a choice on a test chain and "not one for a chain with
  value" ("Consequences", #88). Decided - lifted or kept, with the reason - before the start.
- [ ] **Open network bugs that touch seed nodes**: #126 (P3). Fixed: #128 (a configured peer was
  dialled once; P2), dialled again now while it is not connected, and #129 (no stop line after
  SIGTERM; P3), a node stopped by hand reports its run.
- [ ] **Seed nodes** as [infrastructure.md](infrastructure.md) describes them: at least two, at
  different providers, sized by measurement, with an onion service each.

### The wallets that send on the main chain

- [ ] **A send signed with a wallet file is confirmed on the main network.** The Lethenon plugin of
  mystic-crypt-ui confirms a send on the main network only for the wallet kept in the vault; a send
  signed with a wallet file asks nothing, because a wallet file does not record its network
  (astrapi69/mystic-crypt-ui#978). Without a main chain this has no effect, which is why it waits
  for this list (the maintainer's decision of 2026-11-14). Before the fix, the design question is
  answered where a wallet file learns its network from; the fix ships in a release of
  mystic-crypt-ui before the main chain starts.

### The release key

- [ ] **The release key `D8C403518C49CA75` prepared** as [release-key.md](release-key.md) describes:
  - a revocation certificate, stored offline;
  - a subkey that only signs, valid for two years, in the CI secrets of all six repositories that
    sign with the key (#154 and its counterparts in the libraries);
  - the primary key on offline media only;
  - the fingerprint in the README, on the website and on keyserver.ubuntu.com and keys.openpgp.org.

  A node of the main chain trusts the chain through the code it runs, and the code through this
  signature.

### The documents

- [ ] The five documents of `docs/launch/` re-checked against their sources, each with a new
  "Last reviewed" date (#144).
- [ ] A lawyer has read them, above all this checklist and
  [regulatory-overview.md](regulatory-overview.md).
- [ ] Every public statement follows [messaging-guide.md](messaging-guide.md).

## 3. A tax advisor before the start

- [ ] A tax advisor in the maintainer's country of residence has been asked, before the start and
  not after the first block, about at least:
  - any LETH the maintainer mines (the genesis block's reward is burned and belongs to nobody,
    [ADR 0005](../adr/0005-main-chain-genesis.md)): when, if at all, it counts as income, and at what value while
    it has no market;
  - giving LETH away, and receiving donations for the project;
  - the costs of the seed nodes and the website ([infrastructure.md](infrastructure.md)): whether
    they can be set against anything;
  - whether any of it is better done by a company, and in which country.

This list asks questions. It answers none of them.

## Sources

Retrieved 2026-10-08.

- Regulation (EU) 2023/1114 on markets in crypto-assets (MiCA): Article 2(1); Article 3(1)(15),
  (16), (17) and (26); Article 4(3), (4), (5) and (8); Article 5(1) and (2); Article 59(1) and (2);
  Article 61(1); recitals 26 and 93. <https://eur-lex.europa.eu/eli/reg/2023/1114/oj>
- In the repository: [release-key.md](release-key.md), [ADR 0003](../adr/0003-test-network.md),
  [ADR 0004](../adr/0004-tor-transport.md), the README, and the issues
  [#88](https://github.com/astrapi69/lethenon/issues/88),
  [#104](https://github.com/astrapi69/lethenon/issues/104),
  [#126](https://github.com/astrapi69/lethenon/issues/126),
  [#128](https://github.com/astrapi69/lethenon/issues/128),
  [#129](https://github.com/astrapi69/lethenon/issues/129),
  [#133](https://github.com/astrapi69/lethenon/issues/133),
  [#137](https://github.com/astrapi69/lethenon/issues/137),
  [#138](https://github.com/astrapi69/lethenon/issues/138),
  [#140](https://github.com/astrapi69/lethenon/pull/140),
  [#144](https://github.com/astrapi69/lethenon/issues/144).
