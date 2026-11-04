# Lethenon

A protest chain against the politics of surveillance: a joke currency whose point is a working
demonstration. Java 25, Gradle, JUnit 6. The library goes to Maven Central (#30), the chain does not:
no pool, no listing, nothing tradeable.

## The first rule

**Use this family's libraries - crypt-api, crypt-data, mystic-crypt, checksum-up, gen-tree - and
search them before writing anything.** The chain is their acceptance test. See
`.claude/rules/use-the-family-libraries.md`; it also says what stays this project's own, and what
to do when a library does not fit.

## What is decided

Issues [#1](https://github.com/astrapi69/lethenon/issues/1) (the reasoning) and
[#2](https://github.com/astrapi69/lethenon/issues/2) (the cut, the numbers, the milestones) carry
every decision. In short: account model, proof of pun, Ed25519 by default with ML-DSA-65 selectable
per transaction and the suite named inside the signed bytes, one canonical encoder, one-time
addresses in the format from the start, 1,984,000,000 LETH at genesis, all of it in a pool the
declining block reward is paid out of, and a tail emission of 66 LETH a block once the pool's share
falls below it (#111, #133).

**It stays private.** Nobody can buy it: no pool, no listing, no promotion. A tradeable joke is a
financial product with real losers, and in the EU it falls under MiCA.

## How the work is done here

- tests first, and a counter-run that proves the test bites: break the thing on purpose, watch the
  right test fail, revert
- integers only for money, `Math.addExact`, and the supply invariant after every block: the sum of
  every balance is the genesis supply plus what the tail has minted
- every number in a commit message or an issue carries the command that measured it
- data minimisation is a rule, not a setting: no account, no identity check, no telemetry, and a
  balance is computed from the chain rather than requested from anybody (`NoBalanceQueryTest`
  fails when the chain package can name a networking type at all)
- `./gradlew build` prints what ran, including "no test classes" while that is true
- no attribution to a non-human collaborator: no `Co-Authored-By` trailer for an AI tool or a
  bot, no commit authored or committed under an AI tool's identity, no session link
  (`Claude-Session:` trailer or a link into a session) and no "Generated with/by" line, in a commit
  or a pull request description. Enforced by `scripts/check-commit-provenance.sh` in CI, which also
  checks the description and checks it again when it is edited, and in `.githooks`
  (`git config core.hooksPath .githooks`); an exception carries `Co-Author-Exception: <reason>` in
  the same text (#10, #279)
