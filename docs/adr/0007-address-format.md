# ADR 0007: An address is written with its network and a checksum

- Status: accepted
- Date: 2026-11-12
- Asked for by the maintainer on 2026-11-12, after a review of the wallet on 2026-11-11 found that
  an address carries neither a checksum nor a network (#309), and accepted by the maintainer on the
  same day, with the format unchanged and the points of a review worked in below (#309)

## Context

The text form of an address is what a person copies, pastes, scans and reads aloud. Today it
checks nothing. Measured on `develop` (b2380ba) by reading the code, and with OpenJDK 25 for the
lengths (`KeyPairGenerator.getInstance(...).generateKeyPair().getPublic().getEncoded().length`):

| | text today | length |
|---|---|---|
| published address (`stealth-v2`) | `<view key>:<spend key>`, `PublishedAddress.toText()` | 177 characters: 88 + 1 + 88 |
| direct recipient, Ed25519 account | `<account key>`, `transfer --to` | 88 characters |
| direct recipient, ML-DSA-65 account | `<account key>` | 3,948 characters |

Every key is the lowercase hex of its X.509 encoding, 44 bytes for X25519 and Ed25519 and 1,974
for ML-DSA-65, written through crypt-data's `HexExtensions` (`Bytes`). `PublishedAddress.parse`
checks only the shape, two hexadecimal halves, as its Javadoc says.

What a mistake does today:

- **A changed character in the spend key** locks the payment to a key nobody holds.
- **A changed character in the view key** derives the one-time destination from a shared secret
  the recipient never computes, so the recipient's scan does not find it and nobody can spend it.
- **A truncated address** is refused only when what is left is no longer hex or, later, no longer
  a key. Which truncations get that far has not been measured.
- **The wrong network** cannot be noticed: an address names no chain, and a wallet has the same keys
  on both, because the derivation path `m/1984'/0'/...` has no network in it (`Wallet`).

The address is not on the chain. What a transfer carries is the destination (`Destination`), so the
text form of an address can change without a hard fork (`PublishedAddress` Javadoc, #2).

## What the format has to do

1. Refuse a mistyped, truncated or garbled address, and say so, instead of paying to it.
2. Name the network, and refuse an address of the other network for the chain a command works on.
3. Add no dependency and no hand-written encoding of key material (`use-the-family-libraries.md`):
   the hash is crypt-data's `HashExtensions.hash(byte[], HashAlgorithm)` with crypt-api's
   `HashAlgorithm.SHA_256`, the hex crypt-data's `HexExtensions`, both already used here
   (`Blocks`, `Bytes`). checksum-up, which the rule names for checksums of bytes, would be a new
   dependency for the same one call; if the maintainer prefers it, that changes nothing else here.
4. Be the same text in the command line and in the desktop plugin: what one prints, the other takes
   (#37). Ignore case, and ignore whitespace inside, so a display in groups can be pasted back.
5. Fit a QR code in alphanumeric mode, whose character set is the digits, the upper-case letters,
   the space and `$ % * + - . / :`.
6. Keep reading the old form.

## The options

Lengths for a published address on the main chain, computed for the 44-byte keys above:

| | A: hex, prefix, SHA-256 checksum | B: Bech32m | C: Base58Check | D: as today |
|---|---|---|---|---|
| length | 192 | 152 | 126 to 128 | 177 |
| what it detects | any change, except with probability 2^-32 (32 bits of SHA-256) | up to four wrong characters for sure, but only up to 89 characters: BIP-173 limits a string to 90 and says "other designs are preferable for lengths above 89 characters"; 152 is above | any change, except with probability 2^-32 (32 bits of double SHA-256) | nothing |
| code | SHA-256 and hex from crypt-data, as today | no encoder in the JDK nor in Bouncy Castle 1.86, which has Base32 only: own code or a new dependency | no Base58 in the JDK nor in Bouncy Castle 1.86: own code or a new dependency | none |
| QR code | alphanumeric mode, in upper case | alphanumeric mode, it was made for it | byte mode, because case matters | alphanumeric mode, in upper case |
| the keys inside | the same hex as today | re-encoded | re-encoded | the same hex |

B and C are shorter, but each needs an encoding this project would have to write or take on as a
dependency, and B gives up its guarantee at this length
([BIP-173](https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki), read 2026-11-12). A
costs 15 characters over today's form and nothing else.

## Decision

Option A.

```
address  = network version ":" key [":" key] ":" checksum
network  = "leth"   the main chain
         | "tleth"  the test chain
version  = "1"
key      = the lowercase hex of the key's X.509 encoding, as today
checksum = the first 4 bytes of SHA-256 over the ASCII bytes of everything before the last ":",
           as 8 lowercase hex characters
```

A published address has two keys, the view key and then the spend key; a direct recipient has one,
the account key. The number of parts tells them apart.

An example on the main chain, made for this record from keys whose private halves were discarded:

```
leth1:302a300506032b656e03210011d330df8c05da43f4eccfb466ee51d1805b3d7b305f7b7aa7d734b15f60bf58:302a300506032b6570032100dec5e421bd69658723cb35e7eeaf3a4678b6bc27a000027ee5b21878f63a7bcf:916000f6
```

The same keys on the test chain end in `:0fc3d2e1` and start with `tleth1:`.

### Reading

- Case is ignored, and spaces and line breaks inside are dropped before anything is checked.
- A checksum that does not match is refused with "this address is mistyped or incomplete: its
  checksum does not match". The reader does not guess which character is wrong.
- A network that is not the chain of the command is refused, naming both.
- The old forms, `<view key>:<spend key>` and a bare account key, are still read:
    - as a recipient on the test chain, with a notice that the address has no checksum;
    - as an address recorded in a wallet entry, on either chain. mystic-crypt-ui's plugin compares a
      recorded address with the wallet's by parsing it (`WalletVault.isTheAddressOf`), so an entry
      written before this change keeps matching.
- As a recipient on the main chain the old forms are refused: the main chain has not started, so
  no address was ever handed out for it without a checksum.

### Writing

Every command and the plugin print the new form. A display may group it in fours for comparing it
with its source; the groups are presentation, not format.

## What this does not solve

- **A valid address swapped by malware passes any checksum.** Only comparing it with its source
  helps, and choosing a known recipient instead of pasting one: the send confirmation of the plugin
  and an address book there, not this record.
- **A direct ML-DSA-65 recipient stays 3,963 characters.** A short handle for such an account would
  change what an account is, which is consensus, not text.
- **The keys stay in their X.509 encoding.** Writing the raw 32 bytes instead would make a published
  address 144 characters, but the text would no longer show the bytes a transfer names, and every
  reader would need each algorithm's header. That can be version 2 of the format.

## Consequences

- No hard fork, no change to a consensus rule, no new cryptographic construction: ADR 0001 does not
  apply, and the test chain need not go first.
- The library: `PublishedAddress.toText` and `parse`, a text form for a direct recipient beside it,
  and every command that prints or reads an address. Section 5 of `docs/launch/specification.md`
  and the change log say what changed.
- mystic-crypt-ui's plugin takes the format through the library and its QR code
  (astrapi69/mystic-crypt-ui#874) encodes it in upper case.
- The tests come first, in the pull request that builds it:
    - a round trip for both kinds of address on both chains;
    - every single substituted character of a fixed sample address is refused, every position
      against every other hex digit;
    - every truncation and every swap of two neighbouring characters is refused;
    - an address of the other network is refused, naming both;
    - upper case and an address grouped with spaces are read;
    - an old form is read as a test chain recipient and as a recorded address, and refused as a
      main chain recipient.
