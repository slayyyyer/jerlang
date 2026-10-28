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

The control term selects the signal. `SEND` (`{2, Unused, ToPid}`) and
`REG_SEND` (`{6, FromPid, Unused, ToName}`) carry a message; other signals
are ignored. Messages for processes and names this node does not have are
not decoded and do not close the connection. A message that can not be
decoded is dropped for the same reason.

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

* Links, monitors and `EXIT` signals. Unknown signals are ignored, so a
  crashing peer does not propagate an exit.
* Remote process spawn (`rex`), registered-name monitoring and the
  `global` name server. OTP's messages to those names are dropped.
* Atom cache in the distribution header, compressed terms, funs and
  ports in messages.
* Tick handling: the connection sends ticks but does not use them to
  detect a dead peer.
