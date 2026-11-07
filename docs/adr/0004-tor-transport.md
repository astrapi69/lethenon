# ADR 0004: Tor as a transport

- Status: accepted
- Date: 2026-10-07
- Released by the maintainer as the block after the main-chain prerequisites; every choice below
  was made within that release, by best practice and the recommendations recorded here, without a
  further question to the maintainer

## Context

ADR 0003 gave the test chain a network and said what it costs: a node that listens publishes its
address to every node it meets, and a transfer leaves from the IP address of whoever sends it. The
README promises "transport over Tor" as part of unlinkability (milestone 3), and ADR 0003 names Tor
as the step that changes what a listening node gives away.

### What the code does today

Measured on develop 8c2d5a0 with
`grep -rn "new Socket\|new ServerSocket\|InetSocketAddress\|InetAddress" src/main/java`:

- Outgoing connections are opened in two places: `Node.connect` and `Client.open`. `Client` serves
  `Handover` (`send --node`) and `Bootstrap`, and `Sync` goes through both. Both resolve the host
  name locally (`new InetSocketAddress(host, port)`).
- `Node.listen` binds every interface (`new InetSocketAddress(port)`).
- An accepted peer is passed on as `socket.getInetAddress()` and the port it announced
  (`Node.listeningAddress`). A peer that reaches the node through a Tor onion service arrives from
  127.0.0.1, and would be passed on as `127.0.0.1:<port>`.
- `HELLO` carries a node identity drawn once per node. Used on two networks, it would link them.
- `PeerAddress` takes any host. `PeerList` carries hosts up to 253 characters, so a v3 onion
  address (56 characters plus `.onion`) fits the wire as it is.

### Monero as the blueprint

`docs/ANONYMITY_NETWORKS.md` in monero-project/monero (read at f6a591c):

- `--proxy`: all external traffic goes through a SOCKS proxy, treated as the clearnet zone. Hidden
  services are not dialled over it.
- `--tx-proxy tor,127.0.0.1:9050[,max]`: an anonymity zone. A transfer that originates on the node
  ("lacks a valid context") is sent only to peers in that zone. Without such a peer it is held, not
  sent in the clear.
- Over anonymity networks, only handshakes, timed syncs and transfer broadcasts. The chain is not
  synchronised over hidden services, "to make Sybil attacks more difficult".
- `--anonymous-inbound <onion>:<port>,127.0.0.1:<port>[,max]`: a separate listener for the onion
  service. Its address is announced only to peers in the same zone.
- Its "Privacy Limitations" section is the list of what this design does not solve: timestamps that
  link zones, a node that runs only to send, bandwidth shaping, and one Tor stream used for two
  transfers.

### Measured in this environment

Tor 0.4.9.11 installs here, but it cannot bootstrap. With direct connections it stays at "5%
(conn): Connecting to a relay". Through the environment's HTTPS proxy, every relay is refused:
`The https proxy refused to allow connection to 205.185.125.239 (status code 403, "Forbidden")`
(tor log, 2026-10-07). So no test here can use the Tor network.

### Measured against a real Tor

The runbook (step 4) ran on the maintainer's machine on 2026-10-08, Tor 0.4.9.11, recorded in #112.
A node took the chain from an onion service, a second node joined the first one's zone through its
onion service and nothing else, and an own transfer reached it through the zone only. The first run
found that a connection through Tor needs more time to open than a direct one: from a freshly
started Tor client, the first CONNECT to an onion service took 3.7 to 6.8 s in seven of eight
samples, and Tor gave up on the eighth after its own 120 s. A connection through a SOCKS proxy now
has Monero's `P2P_DEFAULT_SOCKS_CONNECT_TIMEOUT`, 45 s, a direct one keeps 5 s (#127). A
configured peer that could not be reached was dialled only once (#128); it is now dialled again
while it is not connected, after 5 s and then after a pause that doubles up to 5 minutes.

## Decision

### No Tor library, a Tor daemon

lethenon talks SOCKS5 to a Tor daemon the operator runs, as Monero does. The JDK's SOCKS client
(`java.net.Proxy.Type.SOCKS`) sends an unresolved host as a domain name, so the proxy resolves it
and the node never asks DNS for a peer's name. This adds no dependency. A Java Tor implementation
would put a large, little-reviewed codebase inside the process that holds the chain, for nothing
the daemon does not already do.

### Steps, one pull request each

1. **A proxy for every outgoing connection (`--proxy host:port`, #114).** Node, `sync` and the
   genesis bootstrap dial through SOCKS5 with the host unresolved. `send --node` keeps its direct
   route: it hands a transfer to the node that serves the same chain file and reads that file's
   pool afterwards, so that node is local; one's own transfers reach Tor through step 2. A proxy given means no
   connection is ever made around it. A node with a proxy listens on 127.0.0.1 unless `--bind`
   says otherwise. `PeerAddress` recognises v3 onion addresses: 56 base32 characters and
   `.onion`, case-insensitive, other lengths refused. An onion address without a proxy is refused
   before anything touches the network.
2. **An anonymity zone (`--tx-proxy tor,host:port[,max]`, #116).** Onion peers given with `--peer`
   are dialled through that proxy, at most `max` of them (default 10), and belong to a zone of
   their own:
   - kept apart from the clearnet peers: nothing the node relays in the clear reaches them;
   - their own node identity in `HELLO`, drawn apart from the clearnet one, listening port 0, and
     the genesis block as the tip, so the zone's `HELLO` says nothing about the node's chain and
     never makes the peer ask for it;
   - no chain: `GET_CHAIN`, `CHAIN`, `GET_BLOCKS`, `BLOCKS`, `GET_PEERS` and `PEERS` from such a
     peer disconnect, and none are sent to it. A `BLOCK` it relays is passed over without being
     adopted, because an onion node that does not know it is in a zone relays its blocks to every
     peer;
   - only `HELLO` and `TRANSFER`, and since step 3 the exchange of onion addresses.

   With the zone on, a transfer that originates on this node goes only to anonymity peers: the
   node's own `submitTransfer` and a transfer handed over by a command such as `send --node`. A
   command is told apart by its missing node identity (`nodeId` 0), not by its listening port,
   since a node that does not listen announces port 0 as well and its transfers are relayed, not
   originated. Without one, the
   transfer waits in the pool and the refusals say so. It is not sent in the clear. A transfer that
   arrives from a peer is relayed as before.
3. **Anonymous inbound (`--anonymous-inbound <onion>:<port>,127.0.0.1:<port>[,max]`, #120).** A
   second listener, on loopback only, whose peers belong to the anonymity zone, at most `max` of
   them (default 16, the node's incoming limit); it needs `--tx-proxy`, as Monero's does.
   - Its onion address travels only inside that zone: `HELLO` gains a field for it (protocol
     version 3), filled only on connections in the anonymity zone, with the onion service's port
     as the listening port, and empty everywhere else. A clearnet `HELLO` that carries one is
     refused, because a node that did that would link its IP address to its onion address.
   - Zone peers exchange onion addresses with `GET_PEERS` and `PEERS`, a peer exchange of the zone's
     own: an accepted zone peer is passed on by the onion address it announced, never as
     127.0.0.1; only onion addresses are kept from a `PEERS` in the zone; learnt ones are dialled
     through the zone while it has room, unless `--no-discovery` (`Node.discoverPeers(false)`)
     keeps the node with the peers it was given, in the zone as on the clearnet. Nothing learnt
     in the zone reaches the clearnet lists, and nothing from the clearnet lists reaches the zone.
   - A zone slot that frees up is filled again, and an onion address that never completed a
     handshake is forgotten, as on the clearnet (#121).
4. **The runbook against a real Tor.** `docs/tor.md`: the torrc lines (`SocksPort`,
   `HiddenServiceDir`, `HiddenServicePort`), the three node configurations, and a manual
   end-to-end run between two machines. It cannot be executed here (see "Measured in this
   environment"); the maintainer ran it on 2026-10-08 (see "Measured against a real Tor",
   #112), and the README says so.

### How it is tested

Each step is tested against a SOCKS5 server that lives in the test sources. It records every
`CONNECT`: the address type (IPv4 or domain name), the host and the port. It forwards an onion
name to a local port the test chose. That makes the properties checkable that matter more than the
connection itself:
- with a proxy, nothing is dialled directly: the target port is closed to direct connections and
  reachable only through the proxy;
- an onion host reaches the proxy as a domain name, never resolved;
- a transfer that originates locally never reaches a clearnet peer while the zone is on;
- an onion address never appears in a clearnet `HELLO` or `PEERS`.

## What this does not solve

These are the limits Monero lists, and they apply here as well:
- **Running only to send.** A node started to hand over one transfer and stopped again tells an
  observer of the connection when it sent. `send --node` through Tor hides the address, not the
  moment.
- **One circuit, two transfers.** Two transfers over the same connection let the onion service's
  operator link them. Rotating connections is a later step.
- **Bandwidth shaping** against a Tor connection is not mitigated.
- **Timestamps.** `HELLO` carries no clock time, so this linkage of the zones does not apply. A
  block's timestamp is the miner's, and blocks do not travel in the anonymity zone.

Two that come with the anonymity zone:
- **The node's own miner.** A node that mines can put its own waiting transfer into a block before
  any other node has it, which tells every peer where the transfer came from. A node that sends
  over Tor should not mine on the same pool.
- **Every own transfer to every zone peer.** A zone peer's operator learns all of this node's own
  transfers and can link them to each other, though not to an address. Monero sends over two
  outgoing connections it rotates every five minutes; doing the same is a later step.

And one that is lethenon's own: the chain is public, and every transfer names its sender's account
(ADR 0002, sender ambiguity postponed). Tor hides where a transfer was sent from, not who sent it.

## Consequences

- No new dependency. The operator runs Tor.
- The chain package stays unable to name a networking type (`NoBalanceQueryTest`). Everything here
  lives in `transport` and `cli`.
- Protocol version 3 with step 3. Version-2 nodes are refused, as version 1 was at #102.
- Inside the anonymity zone the chain is never synchronised, after Monero's reasoning on Sybil
  attacks. A peer reached through `--proxy` is an ordinary peer, onion address or not, and a node
  or `sync` takes the chain from it: that is what a wallet needs to catch up over Tor, as Monero's
  wallet reaches a daemon's RPC over an onion service. This sentence said "never over onion
  services" when the ADR was accepted; step 1 showed that the restriction belongs to the zone, not
  to the proxy.
