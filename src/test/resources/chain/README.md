# The chain fixtures

`chain.lethenon` is a three-block chain of the test chain, `lethenon-test-2`: a genesis block that pays
its reward to the burn account, as every genesis block does (#148, ADR 0005), block 1, which pays its
holder, and block 2, which names its miner and carries one signed transfer of 42 LETH with a fee of
100 lethe. All three blocks were mined at difficulty 8. The chain replays from this
file alone - who was paid is inside the blocks (lethenon#23). `holder.key` and `miner.key` are the two
public keys the blocks name, as raw X.509 bytes, so that a test can say whose balance it expects;
there are no private keys here and none are needed, because verifying needs none.

`chain-corrupted.lethenon` is the same file with one bit of its last byte flipped, which is inside
the signature of the transfer. It exists so that the verifier has something it must refuse: a
verifier that has never rejected anything is untested.

Written by `./gradlew writeChainFixtures` (`ChainFixtureWriter`, fresh keys every run) with block
encoding version 2; the chain file is 911 bytes (`wc -c src/test/resources/chain/chain.lethenon`). They
are regenerated only when the encoding or a consensus rule they rest on changes; the last time was when
the genesis reward went to the burn account (#148), so the holder is paid by block 1 instead. They stay
on the test chain, which never gets an anchored genesis block, while the main chain gets one on its
start day (ADR 0005).

`chain-lethenon-1.lethenon` is the chain file as an earlier build wrote it, 698 bytes, under
`lethenon-1` (commit 2e1076c, 2026-10-01). It is not regenerated: it is the evidence that a chain
started under the rules before 0.4.0 is refused, and with that reason, by this build (#137).

`lethenon-test-2-genesis.lethenon` is the genesis block of the test network that runs since
2026-10-15, 220 bytes, the first block of the maintainer's chain at home. Its hash,
`0093b8db1a0cf9be2d5203659d80b42f105d2e322a1850f13da26579df411d6e`, is the one seed node 1 announced in
its HELLO on the same day, read with a probe that sends a HELLO and decodes the answer. It is not
regenerated: it is the chain `TestNetworkSeeds` names (#197), and when the test network starts anew,
it is replaced together with that class.
