# Distribution

Milestone 0.4 (Network) has two parts: the distribution protocol, which is
what connects Erlang nodes, and the socket API (`kernel/inet`,
`kernel/gen_tcp`, `kernel/gen_udp`), which is what Erlang programs use to
open their own connections. This document covers both, starting with
distribution, which works in both directions: JErlang can connect to an
Erlang/OTP node, and an Erlang/OTP node can find, connect to and send
messages to JErlang.

## Node identity

`VirtualMachine` owns the identity of the node:

| Method | Meaning |
|--------|---------|
| `node()` | The node name as an atom. Defaults to `nonode@nohost`, like an Erlang node that was started without `-name` or `-sname`. |
| `nodeCreation()` | The creation number of this incarnation of the node. Defaults to `0`. |
| `setNode(Atom)` / `setNode(Atom, int)` | Names the node. `-name`, `-sname` and `-setcookie` are parsed by `Emulator`. |

PIDs and references are only unique together with the node that created
them, so both store a node atom and a creation number. A local PID prints
like Erlang/OTP prints it, without the node; a PID from another node prints
with the node. `erlang:node/0` returns the local node name and
`erlang:node/1` returns the node of a PID or reference.

A node only takes part in distribution when its name has the form
`name@host`. An unnamed node can still make outgoing connections, but no
node can find it.

## External term format

`org.jerlang.erts.etf` contains the encoder and decoder:

* `ExternalTermWriter.encode(Term)` writes the version byte and the term.
* `ExternalTermReader.read()` reads a term, with or without the version
  byte, so it is also used for BEAM literal chunks.

The BIFs `erlang:term_to_binary/1` and `erlang:binary_to_term/1` use them.
`binary_to_term/1` raises `badarg` for invalid input, as Erlang/OTP does.

### Encoded terms

| Term | Tag |
|------|-----|
| atom | `SMALL_ATOM_UTF8_EXT`, `ATOM_UTF8_EXT` for names longer than 255 bytes |
| integer | `SMALL_INTEGER_EXT`, `INTEGER_EXT`, `SMALL_BIG_EXT`, `LARGE_BIG_EXT` |
| float | `NEW_FLOAT_EXT` |
| binary, bitstring | `BINARY_EXT`, `BIT_BINARY_EXT` |
| string | `STRING_EXT` (latin-1 strings up to 65535 characters), `LIST_EXT` otherwise |
| list, nil | `LIST_EXT`, `NIL_EXT` |
| improper list | `LIST_EXT` with a non-list tail, for example `[alias|Ref]` |
| tuple | `SMALL_TUPLE_EXT`, `LARGE_TUPLE_EXT` for tuples with more than 255 elements |
| map | `MAP_EXT` |
| pid | `NEW_PID_EXT` (32 bit creation number) |
| reference | `NEWER_REFERENCE_EXT` with up to five 32 bit words |

The decoder also understands the older `PID_EXT`, `REFERENCE_EXT`,
`NEW_REFERENCE_EXT` and `FLOAT_EXT` tags, because a peer may send them.

### What is not encoded yet

* funs (`FUN_EXT`, `NEW_FUN_EXT`, `EXPORT_EXT`) and ports (`PORT_EXT`)
  are rejected with an error;
* compressed terms (`COMPRESSED`, tag 80) are rejected;
* atom cache tags (`ATOM_CACHE_REF`) are rejected. JErlang does not
  negotiate `DIST_HDR_ATOM_CACHE`, so peers do not send them.

## Connections

`DistConnection` is one TCP connection after the version 6 handshake:

```
length (4 bytes) | 112 | control term | message term
```

The control term selects the signal:

| Signal | Control message |
|--------|-----------------|
| `LINK` | `{1, FromPid, ToPid}` |
| `SEND` | `{2, Unused, ToPid}`, followed by the message |
| `EXIT` | `{3, FromPid, ToPid, Reason}` |
| `UNLINK` (legacy) | `{4, FromPid, ToPid}` |
| `REG_SEND` | `{6, FromPid, Unused, ToName}`, followed by the message |
| `EXIT2` | `{8, FromPid, ToPid, Reason}` |
| `MONITOR_P` | `{19, FromPid, ToProc, Ref}` |
| `DEMONITOR_P` | `{20, FromPid, ToProc, Ref}` |
| `MONITOR_P_EXIT` | `{21, FromProc, ToPid, Ref, Reason}` |
| `SPAWN_REQUEST` | `{29, ReqId, From, GroupLeader, {M,F,A}, Opts}`, followed by the argument list |
| `SPAWN_REPLY` | `{31, ReqId, To, Flags, Result}` |
| `UNLINK_ID` | `{35, Id, FromPid, ToPid}` |
| `UNLINK_ID_ACK` | `{36, Id, FromPid, ToPid}` |

`SEND` and `REG_SEND` carry a message; the other signals carry their
reason in the control term, because `DFLAG_EXIT_PAYLOAD` is not
negotiated. `NODE_LINK` and signals this node does not act on are ignored.
Messages for processes and names this node does not have are not decoded
and do not close the connection. A message that can not be decoded is
dropped for the same reason.

`Distribution` keeps the open connections and implements `!`:

* a local PID or registered name is delivered directly;
* a remote PID uses `SEND`;
* `{Name, Node}` uses `REG_SEND`;
* sending to a node that can not be reached drops the message, as Erlang
  does.

`erlang:send/2`, the `send` opcode, `erlang:nodes/0` and `net_adm:ping/1`
all use `Distribution`. A connection is established lazily on the first
send or ping.

## Finding nodes: EPMD

`EpmdClient` talks to the Erlang Port Mapper Daemon:

* `port/2` sends `PORT2_REQ` to find the distribution port of a peer;
* `register/3` sends `ALIVE2_REQ` and keeps the returned socket open. The
  node is unregistered when that socket closes, as the EPMD protocol
  specifies.

`DistListener` binds a TCP port (an ephemeral one by default, or
`-Djerlang.dist.port`), registers it with EPMD, and accepts incoming
connections. It is started by `VirtualMachine.start()` for a named node
and stopped by `VirtualMachine.shutdown()`.

### Accepted handshake

The listener implements the accepting side of the version 6 handshake:

1. read `send_name` (`N`, flags, creation, name);
2. send status `sok` and a challenge (our flags, challenge, creation,
   name);
3. verify the peer's `challenge_reply` digest against the cookie;
4. send the `challenge_ack` digest for the peer's challenge.

The cookie comes from `-setcookie`, `ERLANG_COOKIE`, `~/.erlang.cookie` or
`$XDG_CONFIG_HOME/erlang/.erlang.cookie`.

OTP nodes send a `$gen_call` to `net_kernel` asking `{is_auth, Node}` on a
new connection. The handshake already proved the cookie, so `DistConnection`
answers `{Tag, yes}` using the same connection. Without that answer OTP
closes the connection and `net_adm:ping` returns `pang`.

## Verification

* [`DistributionTest`](../src/test/java/org/jerlang/erts/dist/DistributionTest.java)
  uses a fake EPMD and a fake peer to test the outgoing handshake, the
  negotiated flags, `SEND`, `REG_SEND`, `nodes/0` and the failure paths
  (unknown node, wrong cookie).
* [`DistListenerTest`](../src/test/java/org/jerlang/erts/dist/DistListenerTest.java)
  uses a fake EPMD that records the `ALIVE2_REQ` and a fake OTP node that
  performs the client handshake against the listener, then delivers a
  `REG_SEND` and a `SEND`.
* [`OtpInteropTest`](../src/test/java/org/jerlang/erts/dist/OtpInteropTest.java)
  is opt-in (`-Djerlang.dist.otp=true`) and runs against a real OTP 29
  node. It checks `net_adm:ping/1`, both message directions, `nodes/0`,
  and a compiled Erlang module that receives a remote reply in a
  `receive ... after` expression.
* [`DistSignalsTest`](../src/test/java/org/jerlang/erts/dist/DistSignalsTest.java)
  runs [`dist_signals_demo.erl`](../src/test/erl/dist_signals_demo.erl)
  against a fake peer and checks `LINK`, `UNLINK_ID`/`UNLINK_ID_ACK`,
  `EXIT`, `EXIT2`, `MONITOR_P`, `DEMONITOR_P`, `MONITOR_P_EXIT`, name
  monitors, `noconnection`, node monitoring, the net-tick timeout, and
  `rpc:call`/`rpc:cast` in both directions.
* [`DistSpawnTest`](../src/test/java/org/jerlang/erts/dist/DistSpawnTest.java)
  runs [`dist_spawn_demo.erl`](../src/test/erl/dist_spawn_demo.erl) and
  checks `spawn/4`, the reply, `link` and `monitor` options of
  `spawn_request/5`, `spawn_request_abandon/1`, error replies, local
  requests, and incoming `SPAWN_REQUEST`/`SPAWN_REPLY` in both
  directions.
* [`ExternalTermFormatTest`](../src/test/java/org/jerlang/erts/etf/ExternalTermFormatTest.java)
  round-trips every supported term, including improper lists and five-word
  references, and compares byte sequences with `erlang:term_to_binary/1`
  on OTP 29.

To run the live test, start the peer and then run Maven:

```
erl -sname peer -setcookie dist-test-cookie -noshell -eval \
  'register(echo, spawn(fun Loop() -> receive {From, Msg} ->
     From ! {echo, Msg}, Loop() end end)),
   io:format("READY ~p~n", [node()]),
   receive after infinity -> ok end.'

mvn test -Dtest=OtpInteropTest -Djerlang.dist.otp=true
```

`net_adm:ping('jerlang@<host>')` from a second OTP node must return
`pong`, and a message to `{sink, 'jerlang@<host>'}` must arrive at the
JErlang process registered as `sink`.

## Local links and monitors

A node also needs the local half of Erlang's process semantics, because that
is what supervision and `rpc` are built on. `link/1`, `unlink/1`,
`monitor/2`, `demonitor/1,2`, `exit/1,2`, `process_flag(trap_exit, Boolean)`,
`whereis/1`, `unregister/1`, `registered/0` and `is_process_alive/1` work
within the node.

A process that terminates reports its reason to every linked process and
sends `{'DOWN', Ref, process, Pid, Reason}` to every monitor. A linked
process without `trap_exit` gets the exit signal, and terminates with the
same reason; a trapping process receives `{'EXIT', From, Reason}` instead.
`exit(Pid, kill)` kills regardless of `trap_exit` and reports `killed`, and
a link or monitor on a process that is already gone reports `noproc`. An
uncaught error or throw makes the reason a `{Reason, Stacktrace}` tuple, as
in OTP; a plain `exit/1` or `killed` reason stays bare.

Exit signals take effect at the next instruction, or at the next scheduling
boundary when the process is waiting. A linked process that exits with
reason `normal` is ignored unless the peer traps exits.

[`LinkMonitorTest`](../src/test/java/org/jerlang/jvm/LinkMonitorTest.java)
runs [`src/test/erl/link_demo.erl`](../src/test/erl/link_demo.erl), which
covers link propagation, `trap_exit`, `unlink`, `demonitor`, `kill`, the
`noproc` cases, `spawn_link`, `spawn_monitor`, `spawn_opt` and the registry
functions.

## Distributed links and monitors

`link/1`, `unlink/1`, `monitor/2` (PIDs and `{Name, Node}`),
`demonitor/1,2` and `exit/2` treat a PID on another node as a distributed
signal instead of a local operation. `Process` records the link and
monitor state; `Distribution` encodes the matching control message.

### The link protocol

JErlang advertises `DFLAG_DIST_MONITOR`, `DFLAG_DIST_MONITOR_NAME`,
`DFLAG_UNLINK_ID` and `DFLAG_SPAWN`, so a peer knows that monitors, the
new link protocol and remote spawn work in both directions. `DFLAG_UNLINK_ID`
is required by OTP 26 and later, so JErlang implements the new link
protocol rather than the obsolete `UNLINK` signal:

* `link/1` sends `LINK` and records the link as active. A second `link/1`
  while the link is active sends nothing.
* `unlink/1` marks the link inactive, gives it a new unlink identifier and
  sends `UNLINK_ID`. The link information stays until the peer acknowledges
  it, so both sides agree on whether the link exists.
* A received `UNLINK_ID` is acknowledged straight from the connection
  reader thread, before any other signal can be written to that peer, as
  the protocol requires. The link information is removed if the link was
  active.
* A received `UNLINK_ID_ACK` removes the inactive link information when its
  identifier matches.
* A received `LINK` is ignored when link information for that peer already
  exists: an outstanding unlink has to win, or a link could be resurrected
  after it was removed.
* The legacy `UNLINK` signal is accepted and removes the link, for peers
  that do not use identifiers.

An exit signal from a link (`EXIT`) is acted on only while the link is
active; an inactive link is waiting for its acknowledgement. `EXIT2`,
which `exit/2` sends, is delivered regardless of any link. `kill` stays
uncatchable because the signal ends up in the same pending exit a local
kill uses.

### Monitors

A remote PID, or `{Name, Node}`, gets `MONITOR_P` with the caller's
reference. `DEMONITOR_P` removes it. `MONITOR_P_EXIT` becomes
`{'DOWN', Ref, process, Object, Reason}`, where `Object` is the PID, or
`{Name, Node}` when a registered name was monitored. The reason is
forwarded unchanged, so a remote monitor of a process that died of an
uncaught error also receives `{Reason, Stacktrace}`. Monitoring a
`{Name, Node}` whose name is not registered reports `noproc`, whether the
node is local or remote.

A peer can also monitor a name registered on this node. The name is kept
with the monitor information, so `MONITOR_P_EXIT` reports the name back
rather than the PID.

### Remote spawn

`erlang:spawn(Node, Module, Function, Args)` starts a process on another
node. The node is sent a `SPAWN_REQUEST` and the call waits for the
`SPAWN_REPLY`, so it returns the real PID of the new process. A node that
can not be reached yields a PID that names no process, as documented for
`spawn/4`; a connection that is lost while the request is in flight, or a
peer that does not advertise `DFLAG_SPAWN`, raises an error instead. The
wait is bounded by a 30 second timeout.

`erlang:spawn_request(Node, Module, Function, Args, Options)` returns a
request identifier immediately and reports the result as a message:

```erlang
{spawn_reply, ReqId, ok, Pid}
{spawn_reply, ReqId, error, Reason}
```

`spawn_request_abandon(ReqId)` drops a request whose reply has not arrived
yet; it returns `true` when the caller was still waiting.

The request identifier is also the monitor reference when `monitor` is
used, exactly as in Erlang/OTP. The supported options are:

| Option | Effect |
|--------|--------|
| `link` | The peer links the new process to the caller before answering. |
| `monitor` | The peer sets up a monitor to the new process. `{monitor, []}` is also accepted; monitor sub-options such as `alias` are not. |
| `{reply_tag, Tag}` | First element of the reply message; `spawn_reply` by default. |
| `{reply, yes \| no \| error_only \| success_only}` | Which reply messages the peer sends. `error_only` and `no` are forwarded, so an OTP `erpc` caller gets exactly the replies it asked for. |

An invalid option produces `{spawn_reply, ReqId, error, badopt}` without
contacting the peer. A failed remote spawn produces `noconnection`,
`notsup` or `system_limit`, and never activates the requested link or
monitor.

`spawn_link/1,3,4`, `spawn_monitor/1,3` and `spawn_opt/2,4,5` use the same
machinery. Locally they create the process, install the link or monitor
before the process is scheduled, and return the PID (or `{Pid, Ref}` when
monitoring). `spawn_opt` accepts `link`, `monitor` and
`{priority, max | high | normal | low}`; the heap and scheduler options
(`{fullsweep_after, ...}`, `{min_heap_size, ...}`, `{message_queue_data, ...}`
and similar) are accepted for source compatibility and ignored by the JVM
runtime. On another node only `link` and `monitor` have meaning; the other
options describe the local scheduler.

The `link` and `monitor` flags in `SPAWN_REPLY` (bits 1 and 2) tell the
caller which state the peer established. The caller records it before the
reply message is delivered, so a signal that follows the reply always
finds its link or monitor.

A node that receives a `SPAWN_REQUEST` creates the process without
scheduling it, sets up the requested link and monitor, and sends the
`SPAWN_REPLY` before the new process can run. The reply therefore always
precedes an `EXIT` or `MONITOR_P_EXIT` the new process may produce. The
group leader in the request is accepted but ignored, because JErlang does
not implement process groups yet.

A spawn request still pending when the connection closes fails with
`noconnection`. If a process was created on the peer, its link state is
what tells it that the connection disappeared.

### Remote calls

`kernel/rpc` is implemented on the same `SPAWN_REQUEST`/monitor protocol
instead of the legacy `rex` server:

| Function | Behaviour |
|----------|-----------|
| `rpc:call/4,5` | Starts `erpc:execute_call/4` on the peer. A returned value comes back as the monitor `DOWN` reason `{ResultRef, return, Value}`. Failures use `rpc`'s `{badrpc, Reason}` form; a plain thrown value is returned unchanged. |
| `rpc:cast/4` | Sends a no-reply spawn request for the target function. It returns `true` without waiting. |
| `rpc:block_call/4,5` | Behaves like `rpc:call`; there is no global serialization lock. |
| `rpc:multicall/3,4,5` | Calls the listed nodes in order and returns `{Replies, BadNodes}`. |
| `rpc:eval_everywhere/4`, `rpc:abcast/3` | Cast or send to every listed node. |

An incoming OTP `erpc:call` arrives as a `SPAWN_REQUEST` for
`erpc:execute_call/4`; the process exits with the result tuple and the
caller's monitor reports it as the `DOWN` reason. An incoming
`erpc:cast` uses `erpc:execute_cast/3` and normally carries
`{reply, no}`. Both are covered by `DistSignalsTest`. The old `rex`
protocol is not spoken, so a node that still uses `rex` for `rpc:call`
can not call this node.

### Node monitoring

`erlang:monitor_node/2,3` and `net_kernel:monitor_nodes/1,2` work from the
local process side. A subscription receives `{nodeup, Node}` when a
connection is established and `{nodedown, Node}` when it is lost, with one
message per subscription for `monitor_node` and one for each
`monitor_nodes` subscriber. A node that is not connected at subscription
time reports `nodedown` immediately. The wire-level `NODE_LINK` signal used
by OTP's own node monitor is not sent or interpreted yet.

### Losing the connection

When a connection closes, every link to a process on that node receives an
exit signal with reason `noconnection`, and every monitor on that node
delivers `DOWN` with `noconnection`. A process that traps exits sees
`{'EXIT', Pid, noconnection}`. If a second connection to the same node
exists, closing the duplicate does not fail anything.

The connection also detects a peer that stops answering without closing
the socket. A ticker sends a zero-length net tick every quarter of the net
tick time, and a connection that has received no packet for a full net tick
time is closed and fails its links and monitors with `noconnection`. The
default is 60 seconds, matching Erlang/OTP; tests shorten it with the
`jerlang.dist.ticktime` system property (in seconds). A node that can not
be reached at all fails the link or monitor immediately with
`noconnection` instead of retrying through connection setup.

## Sockets

`kernel/gen_tcp`, `kernel/gen_udp` and `kernel/inet` are implemented
natively on `java.net.Socket`, `java.net.ServerSocket` and
`java.net.DatagramSocket`, so compiled Erlang can open, send on, receive
from and accept connections without a separate Erlang runtime.

The TCP calls are `gen_tcp:listen/1,2`, `accept/1,2`, `connect/3,4`, `send/2`,
`recv/2,3`, `close/1`, `shutdown/2` and `controlling_process/2`. The UDP
calls are `gen_udp:open/1,2`, `close/1`, `send/2,4`, `recv/2,3`, `connect/3`
and `controlling_process/2`. Both socket types share `inet:setopts/2`,
`getopts/2`, `sockname/1`, `peername/1`, `port/1`, `ntoa/1`,
`parse_address/1` and `close/1`.

Sockets support binary and list modes, `{active, false | true | once | N}`,
`reuseaddr`, `nodelay`, `keepalive`, `backlog`,
`{linger, {Boolean, Seconds}}`, `{sndbuf, Bytes}`, `{recbuf, Bytes}`,
`{buffer, Bytes}`, `{tos, N}` and `{exit_on_close, Boolean}`. TCP
additionally supports `{packet, 1 | 2 | 4 | line}` framing; the packet
option is ignored for UDP. Active TCP sockets deliver
`{tcp, Socket, Data}` and active UDP sockets deliver
`{udp, Socket, Address, Port, Data}`.

Buffer sizes are read back from the socket, so `getopts` reports the size
the OS actually uses, which can be larger than the requested value.
`{exit_on_close, false}` keeps a TCP socket writable after the peer
half-closes, with `{tcp_closed, Socket}` still delivered; the default closes
the socket then. `{delay_send, Boolean}` and `{buffer, Bytes}` are recorded
and returned by `getopts` but are hints this implementation does not act on,
and unknown options are ignored.

UDP supports IPv4 and IPv6 multicast through
`{add_membership, {Group, Interface}}`,
`{drop_membership, {Group, Interface}}`, `{multicast_if, Interface}`,
`{multicast_ttl, TTL}` and `{multicast_loop, Boolean}`, both at open time and
through `inet:setopts/2`. For IPv4 an interface is an address tuple; for
IPv6 it is the interface index. `{send_timeout, ...}`, `{ipv6_v6only, ...}`,
`{dontroute, ...}`, `{ttl, ...}`, `{priority, ...}` and raw socket options
are not implemented.

`GenTcpTest` and `GenUdpTest` run
[`src/test/erl/gen_tcp_demo.erl`](../src/test/erl/gen_tcp_demo.erl) and
[`src/test/erl/gen_udp_demo.erl`](../src/test/erl/gen_udp_demo.erl). They
exercise passive, active, list-mode and IPv4/IPv6 multicast round trips,
receive timeouts, default and explicit listen options, half-close with
`exit_on_close` disabled, option changes and address helpers. The IPv6 test
selects an up, multicast-capable interface by index and is skipped when none
exists.

## Not implemented yet

* The `NODE_LINK` wire signal and OTP's `MONITOR_NODES` subscription
  messages. Local `monitor_node` and `net_kernel:monitor_nodes` work from
  connection state; a peer running OTP can not ask this node to monitor a
  node through those signals.
* `rex`, the `global` name server and the old `rpc:call` wire protocol.
  Messages to those names are dropped. `rpc:call` between this node and
  OTP uses `erpc`'s `SPAWN_REQUEST`/monitor protocol.
* Remote funs: `spawn_link(Node, Fun)` and sending a fun to another node
  are not implemented. Remote spawn requires a module, function and
  arguments.
* Atom cache in the distribution header, compressed terms, funs and
  ports in messages.
