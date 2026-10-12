Not legal advice. Last reviewed 2026-10-08. Check with a lawyer before a public launch.

# Lethenon: infrastructure for a start

What a public start of the main chain needs in servers, operation and money, with the measurements
and the providers' terms it rests on. Today a node runs only on the test chain: "The main chain gets
a network only by a later decision" ([ADR 0003](../adr/0003-test-network.md), "Only the test chain"); everything here
prepares that decision and assumes nothing about it. Sources were retrieved on 2026-10-08 and are
listed at the end.

## 1. Seed nodes: at least two, at different providers

A seed node is a `lethenon node` that listens on a fixed address, mines nothing, and is named to a new
node as its first peer; peer exchange then passes it further addresses (ADR 0003, "Peer exchange").
The servers run nodes only: no miner, and no wallet, so no key that moves LETH lives on them.

- **Two to three**, each at a different provider, or at least in a different region, so that the
  network's entry points do not end with one provider's outage, its change of terms or its prices.
- **Oracle Cloud Always Free as one of them, and at least one at a provider that is paid for**
  (section 3). A free offer can change without the network having a say: Oracle halved the Ampere A1
  allowance of its Always Free accounts in 2026.
- Each with an onion service (section 5), so the network is reachable over Tor as well.

## 2. How big, measured

Measured, not estimated: one seed node next to one miner, on the published lethenon 0.3.0, for
three hours, with a transfer every two minutes.

### How it was measured

- **The software**: the lethenon 0.3.0 command line: `installDist` of the tag `RELEASE-0.3.0`, with
  its `lethenon-0.3.0.jar` replaced by the one published on Maven Central (SHA-1
  `09f2b96a6884f3d96209fb0e8470d6edbc8fb483`, the same as Central's `.sha1` file), run on JDK 25.
- **The network**: two nodes in a network namespace of their own (`unshare -rn`), so that the
  loopback counters of `/proc/net/dev` count nothing but their traffic. A miner (`node --mine`) stands
  in for the network's miners; the seed node (`node --peer`) takes its chain from it and mines nothing.
- **The load**: every two minutes the script hands the miner's node a transfer (`send --node`), which
  is relayed to the seed node and mined into a block.
- **The samples**: every 30 seconds, the seed node's CPU time (`/proc/<pid>/stat`), resident memory and
  threads (`/proc/<pid>/status`), both chain files and the traffic.
- **Two runs side by side**: one with the JVM's default heap, one with the seed node held to a 64 MB
  heap (`-Xmx64m -XX:+UseSerialGC`, collections logged). The first shows what a node takes when it is
  offered a lot, the second what it needs.
- **The machine**: a cloud container, x86-64, 4 hardware threads (Intel Xeon at 2.10 GHz), 16 GB of
  memory, Ubuntu 24.04. Both runs shared it. The figures are per process, not for the machine; an ARM
  server is measured again on the instance (section 3).

The script, the chain reader and the evaluation are in
[seed-node-measurement/](seed-node-measurement/), and both runs used the script exactly as it is
there. A first pair of runs on a desktop was not evaluated here, and a second pair in this container
stopped after 426 seconds when the container was restarted; the figures below are from a third pair,
started 2026-10-08 18:26 UTC.

```
unshare -rn bash measure-seed-node.sh <lethenon launcher> <output directory> 10800        # default heap
unshare -rn bash measure-seed-node.sh <lethenon launcher> <output directory> 10800 64m    # 64 MB heap
java -cp "<lethenon>/lib/*" ChainStats.java <output directory>/seed.lethenon > chain.csv
python3 -I analyse.py <output directory> chain.csv
```

### What it measured

Both runs: 10,800 seconds each, 355 samples, both nodes ended with exit status 0 (`pids.txt`). Figures
from `python3 -I analyse.py <run> <chain.csv>` after `ChainStats.java`, and from `gc.log`. CPU is the
seed node's share of one core over each 30-second sample.

| | default heap | 64 MB heap |
|---|---|---|
| CPU, whole run: mean / p95 / max | 0.07 / 0.23 / 1.90 % | 0.09 / 0.37 / 3.20 % |
| CPU, last hour: mean / p95 / max | 0.03 / 0.07 / 0.07 % (no new blocks, see below) | 0.06 / 0.27 / 0.60 % |
| resident memory, max / high-water mark | 245,224 / 245,124 kB | 122,356 / 125,388 kB |
| threads | 24 to 29 | 16 to 21 |
| traffic of the namespace, whole run | 1.5 MB a day (17.4 B/s) | 1.7 MB a day (20.1 B/s) |
| traffic, last hour | 1.1 MB a day | 1.5 MB a day |
| chain file growth, whole run | 0.303 MB a day | 0.477 MB a day |
| blocks, height | 300, 299 | 354, 353 |
| the chain's last hour | 29 blocks, 119.6 s apart | 24 blocks, 151.2 s apart |
| bytes per block: all / without a transfer / with one | 253.9 / 212.3 / 755.1 | 276.3 / 212.4 / 683.9 |
| transfers handed over / carried | 88 / 48 | 88 / 87 |
| garbage collection | not logged | 71 young collections, no full one, at most 5 MB live after a collection, longest pause 85 ms (at the start) |
| OutOfMemoryError in `seed.err` | none | none |

The difficulty rose by 2 bits every 30 blocks from 8 to 26 and stayed at 26, where the blocks came
116.7 s (64 MB run, 84 blocks) and 171.6 s (default run, 30 blocks) apart, close to the two-minute
target. In the default run the miner then stopped finding blocks: its last block came 6,046 s after
the genesis block, and nothing followed for the rest of the run while transfers piled up (40 waiting
at the end), although the next block needed the same 26 bits (`DifficultyRule.requiredFor` on the
chain file). That is a defect of the miner, not of the seed node, and is #151. So the default run's
last hour shows a seed node with nothing to relay; the 64 MB run carried blocks to its end and is the
one the per-block and traffic figures below rest on.

### What follows from it

- **CPU is not what a seed node needs.** At most 0.37 % of one core at the 95th percentile, over three
  hours with a block every two minutes. One vCPU is plenty, and even Oracle's E2.1.Micro with 1/8 of
  an OCPU (section 3) would carry this load; the proof of pun costs the miners, not the seed.
- **Memory is set by the JVM, not by the node.** After a collection at most 5 MB of the heap were
  alive. Offered the default heap, a quarter of this machine's 16 GB, the JVM grew to 245 MB; held to
  64 MB it stayed at 122 MB, without a full collection and without an OutOfMemoryError. So
  `-Xmx64m`: twelve times the live heap measured. The node keeps its whole chain in memory (below),
  so the heap is looked at again when the chain file passes some tens of MB - at the measured block
  size that is years away.
- **`MemoryMax=256M`** for the service: about twice the resident memory measured with the 64 MB heap,
  so a growing chain hits a warning from the monitoring (section 7) long before the limit.
- **Disk**: at the measured 254 to 276 bytes per block and 720 blocks a day, about 0.2 MB a day and
  some 70 MB a year; the ceiling from the block limit below is far higher.
- **Traffic**: 1.5 to 1.7 MB a day with one peer. Even 27 peers, the most a node keeps, would be
  below 50 MB a day, plus every new node's first download of the chain; every plan in section 11
  includes at least 1 TB a month.
- **The minimum, in short: 1 vCPU, 512 MB of memory, 10 GB of disk** - room for the operating system,
  Tor and the node's 122 MB.

### What it does not measure

- The main chain's load: how many nodes connect, how many transfers wait, how big the blocks become.
  The test network here had one peer and a transfer every two minutes.
- Many peers at once. A node keeps at most 12 outgoing and 16 incoming connections (ADR 0003,
  "Limits").
- Tor, and an ARM processor. Both are measured again on the instance before the start (#144).

### Bounds from the code, not measured

- **Memory grows with the chain.** The node holds its whole chain in memory, with a hash and a height
  per block (`LocalChain.java`, fields `chain`, `hashes`, `heights`).
- **Disk**: a block is at most 300,000 bytes (`BlockLimits`). At the two-minute target that is at most
  300,000 × 720 = 216,000,000 bytes a day, about 78.8 GB in a year of 262,800 blocks - a ceiling, far
  above the 254 to 276 bytes per block measured.
- **Traffic**: a node relays every block to every peer except the one it came from (ADR 0003,
  "Relaying"). Full blocks to 27 peers would be about 5.8 GB a day, plus every new node that
  downloads the whole chain from this one. Every plan in section 11 includes at least 1 TB a month.

## 3. Oracle Cloud Always Free as one seed node

### What is free

- **Ampere A1, an ARM processor.** "All tenancies get the first 1,500 OCPU hours and 9,000 GB hours
  per month for free for VM instances using the VM.Standard.A1.Flex shape, which has an Arm processor.
  For Always Free tenancies, this is equivalent to 2 OCPUs and 12 GB of memory" (Oracle, Always Free
  Resources). "Each OCPU corresponds to a single hardware execution thread" (Oracle, Compute Shapes).
- **The change in 2026.** The same page read "3,000 OCPU hours and 18,000 GB hours ... equivalent to 4
  OCPUs and 24 GB of memory" in its archived copy of 5 June 2026, and the new figures from its copy of
  12 June 2026 (web.archive.org). An e-mail from Oracle quoted on a forum names the date the new limit
  is enforced: "Beginning on August 18, 2026, Oracle will begin enforcing the updated Always Free
  compute limits. Compute instances that exceed the Always Free entitlement will be automatically
  terminated" (LowEndTalk, quoting Oracle, 4 August 2026; a secondary source, the e-mail itself was not
  seen). So the limit of 2 OCPUs and 12 GB holds for Always Free accounts, documented since 12 June and
  enforced since 18 August 2026.
- **Paid accounts kept the old amount.** "Each paid tenancy gets the first 3,000 OCPU hours and 18,000
  GB hours per month for free to create Ampere A1 Compute instances" (Oracle, Cloud Price List).
- **The rest**: "up to two Always Free VM instances using the VM.Standard.E2.1.Micro shape" with "1/8th
  of an OCPU" and "1 GB" of memory, "10 TB per month of outbound data", and "a total of 200 GB of Block
  Volume storage" (Oracle, Always Free Resources). The resources are free "in the home region of the
  tenancy" (same page).
- **Network**: VM.Standard.A1.Flex has "1 Gbps per OCPU, maximum 40 Gbps" (Oracle, Compute Shapes).

### Would a lethenon node count as idle? Measured: yes

"Idle Always Free compute instances may be reclaimed by Oracle. Oracle will deem virtual machine and
bare metal compute instances as idle if, during a 7-day period, the following are true:" "CPU
utilization for the 95th percentile is less than 20%", "Network utilization is less than 20%",
"Memory utilization is less than 20% (applies to A1 shapes only)" (Oracle, Always Free Resources).

| Oracle's criterion, each below 20 % | measured, the higher of the two runs | share |
|---|---|---|
| CPU, 95th percentile | 0.37 % of one core (64 MB run, whole run); 0.27 % in its last hour | about 0.4 % of one OCPU, which is one hardware thread; half that of a 2-OCPU instance |
| network | 20.1 B/s, 0.16 kbit/s (64 MB run, whole run) | 0.000008 % of the 2 Gbit/s of a 2-OCPU A1 instance |
| memory (A1 only) | 245 MB with the default heap; 122 MB with `-Xmx64m` | 2.04 % and 1.02 % of 12 GB |

All three stay far below 20 %. By the rule as written, a seed node of the test network on an A1
instance is idle and may be reclaimed. Seven days were not measured, but the smallest margin is a
factor of about 10 (memory with the default heap; 20 with the 64 MB heap, more than 50 for CPU), and
nothing in a seed node's work grows with the days except the chain.

What that means for the plan:

- The Oracle instance is one entry point among at least two, not the one the network relies on; the
  paid one carries it when Oracle reclaims its instance (section 1).
- An upgrade to Pay As You Go keeps the Always Free resources free: "Oracle doesn't charge for Always
  Free resources after you upgrade, and will only charge you for resource usage above the Always Free
  limits" (Oracle, Always Free Resources). Whether it also ends the idle reclamation, no current Oracle
  page says: the exemption appears only in an archived copy of the page from 2022 ("Unpaid Free Tier
  accounts only") and in a 2023 Oracle e-mail quoted by third parties, and the current page names
  neither. To be asked of Oracle before relying on it.
- Load generated only to stay above the thresholds is not part of this plan: it uses resources Oracle
  reclaims for others, and the rule is Oracle's to apply.

### Mining

The Oracle Cloud Services Agreement forbids it for every account, free or paid: the customer may not
"use the Services to perform cyber currency or crypto currency mining" (section 1.3(d); the same
clause in the agreement of Oracle Deutschland B.V. & Co. KG, in English and German). A seed node mines
nothing.

### ARM

"OCI Ampere Compute is a general-purpose, Arm-based compute platform based on the Ampere processor",
and "The Oracle Linux and Ubuntu platform images are also supported" (Oracle, Arm-Based Compute);
aarch64 images of Ubuntu 24.04 and Oracle Linux 9.8 dated 18 September 2026 are listed. lethenon is a
Java library and command line with no native code of its own, so it needs a JDK 25 for Linux on
aarch64; it was not run on ARM for this document, which is the first thing to check on the instance.

## 4. No mining on the servers

Every provider looked at forbids mining or needs a permission for it; none of the terms read forbids
running a node that mines nothing. The servers run nodes only.

| Provider | Mining | Where | A node that mines nothing |
|---|---|---|---|
| Oracle Cloud | forbidden, every account | Cloud Services Agreement, 1.3(d) | not mentioned |
| Hetzner Cloud | forbidden: "Operating applications that are used to mine crypto currencies" | Cloud and vServer Service Agreement, 2; terms and conditions, 8.3 | not mentioned |
| netcup | forbidden: "Operation of mining-services such as "Bitcoin", "Ethereum", "OneCoin" or "Monero"." | terms and conditions, 5 | not mentioned |
| Contabo | no clause in the terms; its blog: mining "is prohibited on Virtual Private Servers (VPS)" | terms and conditions, clause 9 (wear of hardware or bandwidth); blog | "we fully support the hosting of Crypto-Nodes" (blog) |
| OVHcloud | forbidden: "crypto-currency mining ... are strictly prohibited" | general terms of services, 3.5 | proof-of-stake validation allowed as an exception |
| IONOS | forbidden ("Krypto-Mining") | server terms, 2.1.5 | not mentioned |
| Scaleway | forbidden as a "harmful practice" | general terms of services, 8.2 | not mentioned |
| DigitalOcean | forbidden "without explicit written permission" | acceptable use policy, "Network Abuse" | not mentioned |
| Hostinger | forbidden: "Cryptocurrency Miners" | universal terms of service, 6, point 4 | blockchain workloads forbidden for its GPU service only |
| Vultr | forbidden: "CPU ... or GPU ... cryptocurrency mining" | acceptable use policy, 7 (read from an archived copy) | not mentioned |
| Akamai / Linode | forbidden "without the prior written permission of Akamai" | acceptable use policy (read from an archived copy) | not mentioned |

"Not mentioned" means the terms named were searched for blockchain, node and crypto without a match;
it is not a permission.

## 5. Tor: an onion service per seed node

- **What it gives**: "Onion services are services that can only be accessed over Tor", the service's
  IP address is protected, and "Onion services don't need open ports because they punch through NAT.
  They only establish outgoing connections." (Tor Project, onion services overview).
- **How**: a `HiddenServiceDir` and a `HiddenServicePort` in `torrc` per service, after which the
  onion address is in the directory's `hostname` file; the other files there are the service's keys
  and stay private (Tor Project, setup). lethenon's runbook does exactly this, with one onion service
  for the chain and one for the anonymity zone, because a zone carries no chain
  ([docs/tor.md](../tor.md), sections 1 and 3; [ADR 0004](../adr/0004-tor-transport.md)).
- **Per seed node**: at least the chain's onion service, leading to the node's listening port, so that
  a node over Tor can take the chain from it (`sync --proxy`, docs/tor.md). Its address goes on the
  website (section 8).
- **The providers**: DigitalOcean and Vultr forbid Tor exit nodes, Hostinger for its GPU service;
  Contabo may block a server running Tor once it learns the server is used unlawfully. An onion service
  is not an exit node, and none of the terms read forbids one.
- **Keep the keys**: the files in `HiddenServiceDir` are the onion address. They go into the backup
  (section 6), or the address changes with the next server.

## 6. Operation

- **A systemd service**, restarted when it fails, under a user of its own, with nothing writable but
  its data directory. The options are those of systemd's manuals (checked against systemd 259):

  ```ini
  [Unit]
  Description=lethenon seed node
  After=network-online.target tor.service
  Wants=network-online.target

  [Service]
  User=lethenon
  Environment=LETHENON_OPTS=-Xmx64m
  ExecStart=/opt/lethenon/bin/lethenon node --chain /var/lib/lethenon/chain.lethenon --listen 18480 --peer <the other seed node>:18480
  Restart=on-failure
  RestartSec=10
  MemoryMax=256M
  NoNewPrivileges=true
  ProtectSystem=strict
  ReadWritePaths=/var/lib/lethenon
  PrivateTmp=true

  [Install]
  WantedBy=multi-user.target
  ```

  Without `--for` a node "runs until interrupted" (ADR 0003, "The node command"). The port is the
  operator's choice. Stopping it with SIGTERM, as a service
  manager does, prints its stop line and its refusals, the same as `--for` (#129).
- **Updates**: the operating system's security updates automatically (on Debian and Ubuntu the
  `unattended-upgrades` package); lethenon itself by hand, from a signed release whose signature is
  checked against the fingerprint in section 10 before it is installed.
- **Firewall**: incoming only the node's port and SSH, everything else refused. A node that is reached
  only through its onion service needs no open port for it (section 5).
- **SSH with a key only**: `PasswordAuthentication no`, `KbdInteractiveAuthentication no`, and
  `PermitRootLogin no` (OpenSSH, `sshd_config`).
- **Backup**: the chain file and the `<chain>.pending` next to it, and the onion service keys.
  `ChainFile` writes both files atomically, by writing and renaming (ADR 0003, "Persistence"), so a
  copy taken while the node runs is a whole file. A lost chain can also be synchronised again from
  the other seed node and is verified block by block when it is; the backup saves that time, it is not
  the only copy. There is no wallet on the server to back up.

## 7. Monitoring

- **Height, and a stall**: from a machine that is not the seed node, every ten minutes,
  `lethenon sync --chain <monitoring copy> --peer <seed node>:<port>` prints "now at height N"
  (`SyncCommand`), and a non-zero exit status says the node did not answer. A warning goes out when
  the node does not answer twice in a row, or when its height has not moved for 30 minutes, which is
  fifteen blocks at the two-minute target. The sync asks for blocks only and verifies each one, so the
  check reads nothing that the network does not publish anyway.
- **Memory**: from the service manager, `systemctl show <service> -p MemoryCurrent`, against the
  `MemoryMax` of the unit.
- **Peers, and the rest of the node's state**: a running node prints a status line once a minute
  (`--status-every`, `NodeCommand`, #149). It gives the height, the tip's hash, the connected peers
  with the outgoing and incoming connections it holds, the anonymity zone's peers where there is a
  zone, the waiting transfers, and the refusals since the last line. Under systemd the line lands in
  the journal, where the operator's tools read it. A tip that differs from the monitoring copy's for
  longer than a few blocks is a fork worth a look.
- **Where warnings go**: e-mail or a push service, the operator's choice; the check runs where the
  operator is, not on the seed node, so a dead server cannot silence its own warning.

## 8. Domain and website

- **A website with the documentation and the downloads on GitHub Pages**: "GitHub Pages is available
  in public repositories with GitHub Free and GitHub Free for organizations"; a published site "may be
  no larger than 1 GB", and sites "have a soft bandwidth limit of 100 GB per month" (GitHub Docs,
  GitHub Pages limits). GitHub Pages "is not intended for or allowed to be used as a free web-hosting
  service to run your online business, e-commerce site, or any other website that is primarily
  directed at either facilitating commercial transactions" (same page); a site with nothing for sale
  is what it is for.
- **The downloads**: the signed releases on GitHub, linked from the site. Today a release of lethenon
  is a library, "without binaries" (README, "Releasing"); a node for the main chain needs its command
  line distribution as a signed release asset.
- **A domain of its own** is optional: "GitHub Pages supports using custom domains, or changing the
  root of your site's URL from the default, like octocat.github.io, to any domain you own" (GitHub Docs,
  custom domains). With one, its DNS carries an A and an AAAA record per seed node (for example
  `seed1.<domain>`), so a node names a seed by a name that survives a change of server.
- **The onion addresses** of the seed nodes are listed on the site.

## 9. Data protection

- **Seed nodes see IP addresses.** Every node that connects over TCP shows its address, and "Natural
  persons may be associated with online identifiers provided by their devices, applications, tools and
  protocols, such as internet protocol addresses" (GDPR, recital 30); personal data include data
  identifying a person by "an online identifier" (GDPR, Article 4(1)). For a dynamic IP address the
  Court of Justice held that it "constitutes personal data ... in relation to that provider, where the
  latter has the legal means which enable it to identify the data subject with additional data which
  the internet service provider has about that person" (C-582/14, Breyer, operative part 1).
- **What a node keeps**: the addresses of its peers in memory, and passes on those of listening nodes
  to others - "running a node that listens publishes its address" (ADR 0003, "Peer exchange"). The
  refusals it prints when it stops name the remote addresses (`Node.refusals`, `NodeCommand`), and
  under systemd they land in the journal.
- **Limit and delete**: data are to be "limited to what is necessary" and "kept in a form which permits
  identification of data subjects for no longer than is necessary" (GDPR, Article 5(1)(c) and (e)). In
  practice: no log beyond the journal, and the journal kept short (`MaxRetentionSec=7day` in
  `journald.conf`, for example); no list of addresses written anywhere else. The status line counts
  refusals and names no address (#149); only the stop report names them, once.
- **A privacy policy on the website**, with what Article 13 of the GDPR asks for: who runs the seed
  nodes, that they see and pass on IP addresses and why, how long the journal keeps them, the rights of
  the people concerned, and that the website itself is hosted by GitHub. Over Tor, a seed node's onion
  service sees no IP address of the node connecting.

## 10. Keys: who signs, and where the key lives

- **Today**: the library's releases are signed with the key `D8C403518C49CA75`, RSA 2048 bits, created
  2016-05-01, fingerprint `5B2F A6B1 1E29 8BC0 9287 F423 D8C4 0351 8C49 CA75` (`gpg --list-keys
  D8C403518C49CA75`). lethenon 0.3.0 on Maven Central carries a correct signature by it (`gpg --verify
  lethenon-0.3.0.jar.asc lethenon-0.3.0.jar`, run 2026-10-08). The signature is made in the publish
  workflow on the tag, from the repository secrets `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE`
  (`.github/workflows/publish.yml`), and the key is also on the maintainer's machine.
- **For the main chain**, the maintainer decides before the start:
  - whether the node's distribution is signed with the same key, and where its fingerprint is
    published - the README and the website, at least;
  - whether the key stays a repository secret, which every workflow run on a tag can use, or signs
    only on the maintainer's machine;
  - a revocation certificate, kept apart from the key, and whether the key gets an expiry date, which
    it does not have today.
- The genesis block is fixed in the code (#104, #137), so the signature on the code is what a node's
  operator trusts the main chain through.

## 11. Costs

From the measured need (1 vCPU, 512 MB of memory, 10 GB of disk), the smallest plan of each provider that meets it, as its own
pages showed it on 2026-10-08. Prices change; each is the provider's figure on that day.

| Provider | Plan | vCPU / RAM / disk | Traffic | Per month | Tax | Term, locations |
|---|---|---|---|---|---|---|
| Oracle Cloud | Ampere A1, Always Free | up to 2 OCPU / 12 GB / 200 GB block volume | 10 TB out | free | - | home region; may be reclaimed when idle (section 3) |
| Hetzner Cloud | CPX02 | 1 / 1 GB / 20 GB | 20 TB | EUR 6.49 with IPv4 | without VAT | monthly; Falkenstein, Nuremberg, Helsinki |
| netcup | VPS pico G11.5s | 1 / 1 GB / 30 GB | throttled to 100 Mbit/s above a 24-hour average of 100 Mbit/s | EUR 2.21 | with 19 % VAT | 12 months minimum, one per customer, Nuremberg only |
| OVHcloud | VPS-1 | 2 / 4 GB / 40 GB | unlimited, 500 Mbit/s | EUR 4.49 (3.81 with 12 months) | without VAT | Gravelines, Strasbourg, Roubaix, Germany, Warsaw, Milan, UK and more |
| IONOS | VPS S+ | 1 / 2 GB / 60 GB | unlimited, up to 1 Gbit/s | EUR 5 (2 for the first 3 months) | with VAT | EU, USA, UK; minimum term of the offer not stated |
| Scaleway | STARDUST1-S | 1 / 1 GB / block storage extra | egress included, 100 Mbit/s | about EUR 5.98 with IPv4 and 20 GB (computed from its prices) | without VAT | Paris, Amsterdam, Warsaw |
| Contabo | Cloud VPS 4 | 4 / 8 GB / 100 GB | "Unlimited Traffic*", 200 Mbit/s | EUR 5.50 (4.40 with 24 months) | without VAT | Germany, UK, US, Asia, Australia |
| DigitalOcean | Basic Droplet | 1 / 1 GiB / 25 GiB | 1,000 GiB | USD 6.00 | without tax | Amsterdam, Frankfurt, London and others |
| Hostinger | KVM 1 | 1 / 4 GB / 50 GB | 4 TB | EUR 5.49 as an offer, renews at EUR 11.99 | without VAT | paid in advance; Europe and others |
| Vultr | vc2-1c-1gb | 1 / 1 GB / 25 GB | 1 TB | USD 5.00 | not stated | Amsterdam, Frankfurt, London, Paris, Warsaw and others |
| Akamai / Linode | Nanode 1 GB | 1 / 1 GB / 25 GB | 1 TB | USD 5.00 | not stated | Frankfurt, Amsterdam, London, Paris, Stockholm and others |

An example set of three seed nodes at three providers: Oracle (free), Hetzner CPX02 in Helsinki
(EUR 6.49 without VAT, EUR 7.72 with 19 %) and netcup VPS pico in Nuremberg (EUR 2.21 with VAT) -
about EUR 9.93 a month with VAT, computed from the table. A domain is not priced here: the website
works without one, under GitHub's own address.

## 12. Later: a block explorer and a faucet for the test network

- **A block explorer** is a public page over the chain - the replay the desktop plugin's chain view
  already does, on a web server. Its server sees who looks at which block or account; that is the
  question lethenon's wallet never asks a server ([README](../../README.md), "Privacy, and its honest
  label"), so an explorer logs nothing it does not need and offers no search by address it would
  remember.
- **A faucet for the test network**: the command line has one (`lethenon faucet`). On a website it
  hands out test coins and asks for nothing beyond an account to pay; a limit per visitor works through
  the visitor's IP address, which is personal data again (section 9) and goes into the privacy policy.
  It hands out test coins only, never LETH of the main chain ([launch-checklist.md](launch-checklist.md),
  the last row).

## Sources

Retrieved 2026-10-08.

- Oracle, Always Free Resources (page modified 2026-06-12):
  <https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm>;
  archived copies of 5 and 12 June 2026:
  <https://web.archive.org/web/20260605112238/https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm>,
  <https://web.archive.org/web/20260612144234/https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm>;
  archived copy of 17 November 2022:
  <https://web.archive.org/web/20221117050750/https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm>
- Oracle, Free Tier: <https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm>
- Oracle, Cloud Price List: <https://www.oracle.com/cloud/price-list/>
- Oracle, Compute Shapes: <https://docs.oracle.com/en-us/iaas/Content/Compute/References/computeshapes.htm>;
  Arm-Based Compute: <https://docs.oracle.com/en-us/iaas/Content/Compute/References/arm.htm>; images:
  <https://docs.oracle.com/en-us/iaas/images/ubuntu-2404/canonical-ubuntu-24-04-aarch64-2026-09-18-0.htm>,
  <https://docs.oracle.com/en-us/iaas/images/oracle-linux-9x/oracle-linux-9-8-aarch64-2026-09-18-0.htm>
- Oracle Cloud Services Agreement, section 1.3(d):
  <https://www.oracle.com/contracts/docs/cloud_csa_online_v062223_us_eng.pdf>; Oracle Deutschland B.V.
  & Co. KG, English and German: <https://www.oracle.com/contracts/docs/cloud_csa_online_v062223_de_eng.pdf>,
  <https://www.oracle.com/contracts/docs/cloud_csa_online_v062223_de_deu.pdf>
- Oracle's e-mail on enforcement, quoted (secondary):
  <https://lowendtalk.com/discussion/218183/oracle-free-tier-being-reduced/p5>
- Hetzner: <https://www.hetzner.com/legal/cloud-server/>, <https://www.hetzner.com/legal/terms-and-conditions/>,
  <https://www.hetzner.com/cloud/regular-performance/>
- netcup: <https://www.netcup.com/en/terms-and-conditions>, <https://www.netcup.com/en/server/vps-lite>
- Contabo: <https://contabo.com/en/legal/terms-and-conditions/>,
  <https://contabo.com/blog/can-i-use-contabo-servers-for-crypto/>, <https://contabo.com/en/vps/>
- OVHcloud: <https://contract.eu.ovhapis.com/1.0/pdf/contrat_genServices-ie.pdf>,
  <https://www.ovhcloud.com/en-ie/vps/>
- IONOS: <https://www.ionos.de/terms-gtc/terms-server/>, <https://www.ionos.de/server/vps>
- Scaleway: <https://www-uploads.scaleway.com/Conditions_Generales_de_Services_v2026_a0028de41c.pdf>,
  <https://www.scaleway.com/en/pricing/virtual-instances/>
- DigitalOcean: <https://www.digitalocean.com/legal/acceptable-use-policy>,
  <https://www.digitalocean.com/pricing/droplets>
- Hostinger: <https://www.hostinger.com/legal/universal-terms-of-service-agreement>,
  <https://www.hostinger.com/de/vps>
- Vultr: <https://www.vultr.com/legal/use-policy/> (read from
  <https://web.archive.org/web/20260817184054/https://www.vultr.com/legal/use-policy/>), prices from
  <https://api.vultr.com/v2/plans>
- Akamai / Linode: <https://www.akamai.com/site/en/documents/corporate/acceptable-use-policy.pdf> (read
  from <https://web.archive.org/web/20260818150140/https://www.akamai.com/site/en/documents/corporate/acceptable-use-policy.pdf>),
  prices from <https://api.linode.com/v4/linode/types>
- GitHub Docs, GitHub Pages limits:
  <https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits>; custom
  domains: <https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/about-custom-domains-and-github-pages>
- Tor Project, onion services overview: <https://community.torproject.org/onion-services/overview/>;
  setup: <https://community.torproject.org/onion-services/setup/>
- OpenSSH, sshd_config: <https://man.openbsd.org/sshd_config>
- systemd manuals, checked against the installed systemd 259: `systemd.service` (`Restart=`),
  `systemd.exec` (`NoNewPrivileges=`, `ProtectSystem=`, `ReadWritePaths=`), `systemd.resource-control`
  (`MemoryMax=`), `journald.conf` (`MaxRetentionSec=`); upstream at
  <https://www.freedesktop.org/software/systemd/man/latest/>
- Regulation (EU) 2016/679 (GDPR), recital 30, Articles 4(1), 5(1)(c) and (e), 13:
  <https://eur-lex.europa.eu/eli/reg/2016/679/oj>
- Court of Justice of the European Union, C-582/14, Breyer, judgment of 19 October 2016:
  <https://curia.europa.eu/juris/liste.jsf?num=C-582/14>
- Maven Central, lethenon 0.3.0 and its signature:
  <https://repo1.maven.org/maven2/io/github/astrapi69/lethenon/0.3.0/>
