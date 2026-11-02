# JErlang

An Erlang runtime for the Java Virtual Machine.

JErlang reads the BEAM files that `erlc` produces and interprets them on the
JVM. Processes, message passing, links, monitors and distribution are
implemented in Java, and Java code is one reflective call away — not a separate
node, a port or an external term format round trip.

The published artifact is `io.github.slayyyyer:jerlang:1.0.1` on Maven Central.

```xml
<dependency>
  <groupId>io.github.slayyyyer</groupId>
  <artifactId>jerlang</artifactId>
  <version>1.0.1</version>
</dependency>
```

The jar is also runnable on its own, which starts the Erlang shell:

```sh
java -jar jerlang-1.0.1.jar
```

Requires Java 21 or newer. An Erlang/OTP 29 installation is needed only to run
OTP's own modules (`gen_server`, `supervisor`, `application`, …) or to talk to
an OTP node; a JVM-only application that uses the built-in modules does not
need one.

## Why run Erlang on the JVM

The primary target is a single JVM service that hosts an Erlang control plane
next to Java code: supervision trees, message-driven workers and a Java API in
one process, with calls in both directions. The same node can also join an
Erlang/OTP cluster over the distribution protocol, so Erlang and Java can be
deployed independently.

Two examples show both directions, and both are compiled and run in CI:

- [Java calls Erlang](examples/gen-server.md): Java starts OTP's own
  `gen_server` and calls it.
- [Erlang calls Java](examples/java-service.md): a compiled Erlang module calls
  static methods of a plain Java class.

## What works

- **BEAM loading.** All 161 non-deprecated OTP 29 opcodes have handlers, and all
  1366 modules shipped with OTP 29.1.2 load. Binary matching, bit syntax, `try`,
  funs, maps, list comprehensions and records work.
- **OTP.** OTP's own `gen_server`, `supervisor`, `application`, `proc_lib`,
  `logger` and `code` run unmodified from an OTP 29 installation.
- **Distribution.** Handshake, links, monitors, remote spawn, `rpc` and `erpc`,
  in both directions, verified against a real OTP 29 peer. EPMD registration and
  the accepting side of the handshake are implemented too.
- **Sockets.** `gen_tcp`, `gen_udp` and `inet`, including IPv6 and multicast.
- **Scheduling.** Reduction accounting and preemption for CPU-bound Erlang
  loops, priorities, and one timer wheel behind `receive ... after` and
  `send_after`.
- **ETS.** `set` and `ordered_set`, match specs, `update_counter`, ownership
  transfer, and table destruction on owner exit.
- **Java interop.** The `java` module for reflection from Erlang, and
  `org.jerlang.api.Node` for embedding the runtime in a JVM application.

## What does not work

These are decisions with reasons, not forgotten features:

- No NIFs, so `crypto`, `ssl` and much of `public_key` do not run.
- No hot code upgrade and no OTP release handling.
- No `global` name registration, no TLS distribution, no atom cache, and not the
  legacy `rex` protocol — `rpc` uses `erpc`'s spawn protocol instead.
- `gen_statem` and the wider standard library are not covered.
- Nested non-tail CPU-bound calls are not preempted until they return.
- OTP boot scripts are not interpreted, and `code:ensure_modules_loaded/1`
  walks its input in order.

[docs/beam-compatibility.md](beam-compatibility.md) has the per-opcode notes and
the one remaining language-feature gap (native records).

## Where to read next

- **[Getting started](getting-started.md)** — install it, compile a module with
  `erlc`, run it, and embed the runtime in Java.
- **[Java interoperability](java-interop.md)** — the value mapping between
  Erlang terms and Java types, object handle rules, and the embedding API.
- **[Distribution](distribution.md)** — the protocol, EPMD, links and monitors,
  and how to connect an OTP node to a JErlang node.
- **[Operating a node](operations.md)** — metrics, logging and shutdown.
- **[Benchmarks](benchmarks.md)** — measured against BEAM on the same machine.
- **[Roadmap](roadmap.md)** — milestones, and what was dropped from the original
  plan and why.
