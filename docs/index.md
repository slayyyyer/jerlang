# JErlang

An Erlang runtime for the Java Virtual Machine.

JErlang compiles Erlang source to BEAM and interprets it on the JVM. It also
loads the BEAM files that `erlc` produces. Processes, message passing, links,
monitors and distribution are implemented in Java, and Java code is one
reflective call away — not a separate node, a port or an external term format
round trip.

The 3.0 artifact is `io.github.slayyyyer:jerlang:3.0.0`. The main jar is the thin
library; the runnable jar is attached under the `all` classifier. 2.0.0 remains
the latest published release until 3.0.0 ships.

```xml
<dependency>
  <groupId>io.github.slayyyyer</groupId>
  <artifactId>jerlang</artifactId>
  <version>3.0.0</version>
</dependency>
```

The `all` classifier is the runnable artifact, which starts the Erlang shell:

```sh
java -jar jerlang-3.0.0-all.jar
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
- **ETS.** `set`, `ordered_set`, `bag` and `duplicate_bag`; match specs;
  `update_counter`; ownership transfer and heirs; table destruction on owner
  exit.
- **Java interop.** The `java` module for reflection from Erlang, and
  `org.jerlang.api.Node` for embedding the runtime in a JVM application.
- **Compilation.** A `compile` command turns Erlang source into BEAM without
  `erlc`; `-I`, `-pa`, `-D`, `-W`, `+debug_info` and parse transforms are
  supported.

## What does not work

These are decisions with reasons, not forgotten features:

- No NIFs, so `crypto`, `ssl` and much of `public_key` do not run.
- No hot code upgrade and no OTP release handling.
- No `global` name registration, no TLS distribution, no atom cache, and not the
  legacy `rex` protocol — `rpc` uses `erpc`'s spawn protocol instead.
- `gen_statem` and the wider standard library are not covered.
- Nested non-tail CPU-bound calls are not preempted until they return.
- OTP boot scripts are not interpreted. `code:ensure_modules_loaded/1` loads
  its input in parallel.

[docs/beam-compatibility.md](beam-compatibility.md) has the per-opcode notes and
the one remaining language-feature gap (native records).

## Where to read next

- **[Getting started](getting-started.md)** — install it, compile a module with
  the `compile` command or `erlc`, run it, and embed the runtime in Java.
- **[Embedding in a JVM service](embedding.md)** — the 3.0 plan for building
  `.erl` with JErlang and running it inside a Java service.
- **[Java interoperability](java-interop.md)** — the value mapping between
  Erlang terms and Java types, object handle rules, and the embedding API.
- **[Distribution](distribution.md)** — the protocol, EPMD, links and monitors,
  and how to connect an OTP node to a JErlang node.
- **[Operating a node](operations.md)** — metrics, logging and shutdown.
- **[Benchmarks](benchmarks.md)** — measured against BEAM on the same machine.
- **[Roadmap](roadmap.md)** — milestones, and what was dropped from the original
  plan and why.
- **[Compiling Erlang](compiler.md)** — the compiler, its command-line surface,
  and what emitting `.beam` files from Erlang source costs.
