# Security Policy

`lethenon` is a chain, a wallet and a node: a library on Maven Central, a command line built from
it, and a test network that can run over Tor. The coin has no price and is not sold (see the
README, "What it is not"), so a defect here does not cost anybody money. What it costs is the
project's point. Lethenon exists to demonstrate what a currency can refuse - an observer, a balance
query, a link from a payment to its recipient - and a demonstration that does not hold is worse
than none. So the reporting path below is deliberately not the public issue tracker.

## Supported Versions

lethenon is before 1.0, and only the latest release line receives security fixes. This is a
single-maintainer project, and supporting several lines at once is a promise it could not keep.

The table names **lines, not releases**: the top row is the supported one. It is revised when a
new line is released, not when a patch version moves.

| Version | Supported          | Required JDK | Built on mystic-crypt / crypt-data / crypt-api |
|---------|--------------------|--------------|------------------------------------------------|
| 0.6.x   | :white_check_mark: | 25 and above | 13.5 / 13.0 / 10.1                             |
| < 0.6   | :x:                | 25 and above |                                                |

Before 1.0 a fix may change a format. Where it does, the CHANGELOG says so, and older chain
files and wallets keep opening or are migrated with a check, never rewritten silently. If you are
on an older line, the fix for a reported vulnerability will be an upgrade to the current one
rather than a backport.

There is no main chain yet. The test network (`lethenon-test-2`) is the only network running.

## Reporting a Vulnerability

**Do not open a public issue.** Use GitHub's private vulnerability reporting:

[Report a vulnerability](https://github.com/astrapi69/lethenon/security/advisories/new)

That keeps the report between you and the maintainer until a fix exists, and it creates the draft
advisory a CVE can later be issued from.

Please include, as far as you can:

- the version or commit you found it in, and whether the current release is affected
- whether it is reachable through the library API, the command line, a node on the network, or
  only with access to a chain or wallet file
- what an attacker gains (see the list below)
- a way to reproduce it: a failing test is ideal, since this project fixes bugs test-first and
  yours would become the regression guard

### What counts

Each of these is a vulnerability here, even though no money is at stake:

- **Consensus and replay.** A chain that the replay accepts although a rule is broken: a forged or
  missing signature, a transfer that spends more than its sender holds, a block reward other than
  the emission schedule allows, or a supply that is not the genesis supply plus what the tail
  minted. A valid chain that the replay refuses counts too.
- **Signatures.** A transfer that verifies under a suite other than the one named in its signed
  bytes, or a way to make the Ed25519 and ML-DSA-65 suites stand in for one another.
- **One-time destinations.** A way to link a payment to the published address it was made to,
  other than holding that address's view key, or to spend from a one-time destination without its
  spend key.
- **The wallet file.** Reading the wallet's entropy, or any key derived from it, without its
  password, or a wallet that opens with a password other than its own.
- **The node.** A message from a peer that crashes a node, makes it accept a chain it should refuse,
  or makes it use memory or disk beyond its limits.
- **Tor.** A connection that bypasses the configured SOCKS proxy, or anything that reveals a node's
  own address while it runs as an onion service only.
- **Data minimisation.** Anything that asks a third party for a balance, or sends information about
  the user anywhere. The chain package is not supposed to name a networking type at all
  (`NoBalanceQueryTest`); a path around that test is in scope.

### What happens next

This is a spare-time project, so no response time is promised that could not be met. What is
promised instead:

- a reply acknowledging the report, and whether it is reproducible
- if it is: a fix on the current line, a release, and an advisory crediting you unless you prefer
  otherwise
- if it is not, or it turns out to be intended behaviour: an explanation of why, rather than
  silence

### Defects found by the maintainer

A security defect found from the inside - during development, a review, or an audit of this
project's own code - is tracked in a public issue like any other bug, because that is where its
fix, its regression test and its discussion already live. The rule above is about a report from
outside, which must not be public before a fix exists; it is not a reason to hide the maintainer's
own findings from the people running the code.

Both paths end in the same place: once the fixed release is out, the defect gets a published
advisory naming the affected versions and the fixed one. The advisory follows the release, never
precedes it.

## What is public by design

These are documented properties, not vulnerabilities. A report about them is welcome only if it
shows that the documentation understates them:

- **Sender and amount.** Both are public on the chain for every transfer. No proven post-quantum way
  to hide them exists yet; the README's section "Privacy, and its honest label" explains why, and
  [ADR 0002](docs/adr/0002-privacy-building-blocks.md) records the decision.
- **The memo.** Every transfer carries a memo that is signed with it and public on the chain.
- **Ed25519 is the default suite, and it is not post-quantum.** ML-DSA-65 can be chosen per
  transfer. A chain keeps its public keys for good, so a transfer signed with Ed25519 is exposed to
  a future quantum computer, and that exposure is the user's choice to make.

## Two things specific to this project

### The command line distribution bundles its dependencies

Each release on GitHub carries the command line as `lethenon-X.Y.Z.zip` and
`lethenon-X.Y.Z.tar.gz`, signed and with checksums (README, "Verifying the release signatures").
Its `lib` directory holds every runtime dependency: built with `./gradlew installDist` on develop on
2026-10-18, that is 49 jars, among them `bcprov-jdk18on-1.85.2.jar` and
`sqlite-jdbc-3.53.4.0.jar`, which carries native code. Counted with
`ls build/install/lethenon/lib | wc -l`, so the number can be checked rather than believed.

So an advisory against **Bouncy Castle** or **sqlite-jdbc** affects that archive directly, and a
user of the archive cannot swap the affected version out the way a Maven or Gradle consumer of the
library jar can. If you run the archive, track their advisories as if they were ours, and expect
the fix to be a new lethenon release.

### Passwords never go into the process list

A wallet's password is never a command line argument. Every command that opens a wallet reads it
as the first line of standard input. A report that a password leaked from the command line is
expected to show how it got there.

## What belongs elsewhere

lethenon never implements cryptographic primitives itself. Signatures, hashes, key derivation and
the wallet's encryption come from the JDK, from Bouncy Castle, or from this project's family of
libraries, and the code here only orchestrates them. A flaw in one of them belongs to whoever
implements it:

- `mystic-crypt`, `crypt-data` and `crypt-api`, the libraries underneath this one: their own
  reporting paths in their repositories, for example the
  [mystic-crypt security policy](https://github.com/astrapi69/mystic-crypt/security/policy)
- Bouncy Castle: <https://github.com/bcgit/bc-java/security/policy>
- the JDK: <https://openjdk.org/groups/vulnerability/report>
- Tor itself: the Tor Project, through the channels named on <https://www.torproject.org/>

A flaw in *how lethenon uses* one of them - a suite name that is not inside the signed bytes, a key
derived for the wrong purpose, a wallet sealed with a parameter the caller did not ask for, a
connection opened outside the proxy - is squarely in scope here.

A new cryptographic construction, such as a lattice-based way to hide an amount or a sender, is not
added on the main chain before the external review that
[ADR 0001](docs/adr/0001-new-cryptographic-constructions.md) requires. Work on one lives on the
test network until then, and a weakness in it is in scope here.

## What this project already does

So that a report can start from what is known rather than from zero:

- Every pull request runs the full build with its tests, and the supply invariant is checked after
  every block in them: the sum of all balances is the genesis supply plus what the tail minted.
- Bugs are fixed test-first, and each fix keeps its reproduction test as a regression guard.
- Releases are signed with the key named in the README, whose primary key is kept offline
  ([docs/launch/release-key.md](docs/launch/release-key.md)).
- Test key material is generated at test runtime and never committed, so nothing in this
  repository is a real key.

Not in place yet: CodeQL and Dependabot security updates are not enabled on this repository.
