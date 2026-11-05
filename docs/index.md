# JErlang

**A proof of concept: BEAM's failure model inside a JVM service.**

JErlang is an experimental Erlang runtime for the Java Virtual Machine. It
compiles Erlang source to BEAM and interprets it on the JVM. Processes, message
passing, links, monitors, supervisors and distribution are implemented in Java,
and a Java method is one reflective call away.

!!! warning "Proof of concept"
    JErlang is not production software. It is a working prototype used to test
    one claim, and its limits are part of the result. Do not treat this site as
    a support contract or an adoption guide.

## Use it

JErlang 3.0.0 is the current release, published as
`io.github.slayyyyer:jerlang:3.0.0`. The primary usage path is a Maven or Gradle
dependency in a JVM service; the checked-in quickstarts compile `.erl` with
JErlang and need only a JDK.

=== "Maven"

    ```xml
    <dependency>
      <groupId>io.github.slayyyyer</groupId>
      <artifactId>jerlang</artifactId>
      <version>3.0.0</version>
    </dependency>
    ```

=== "Gradle"

    ```kotlin
    implementation("io.github.slayyyyer:jerlang:3.0.0")
    ```

Start with [Embedding in a JVM service](embedding.md). The normal artifact is
the thin library jar; the `all` classifier is for the shell or a one-file
experiment.

## The thesis

A JVM service can get BEAM's failure model — isolated processes, links,
monitors and supervision — without leaving the JVM.

The second half matters as much as the first: that model should be **easier for
a human to audit** than the equivalent Java actor code. A reviewer should be
able to see what is supervised, what crashes, and what restart policy applies
without reconstructing an executor, a callback graph and a retry table from
several files.

This is not a claim that Erlang syntax is nicer, and it is not an argument for
rewriting a Java codebase in Erlang. The useful shape is narrower: a Java
service hosts a small Erlang control plane next to its Java code, and both sides
can call each other in-process.

## What the prototype demonstrates

- **OTP's own code runs unmodified.** `gen_server`, `supervisor`,
  `application`, `proc_lib` and `logger` load from an OTP 29 installation and
  run on this runtime. The JVM supplies processes, mailboxes, links, monitors
  and timers; it does not ship a second implementation of the behaviours.
- **Crashes are supervised, not swallowed.** The
  [supervision example](examples/supervised-app.md) kills a worker and shows the
  supervisor replacing it with a fresh process.
- **Java and Erlang share a process.** Java starts a node, calls an exported
  function, and exposes a Java lambda to Erlang through the same module
  registry. There is no port and no external term format on this path.
- **Two nodes can be one cluster.** The distribution protocol connects to a real
  OTP node in both directions, including `erpc`-style calls and monitors.
- **The runtime is observable and measurable.** Process counts, mailbox sizes,
  run queues, scheduler utilisation and message throughput are exposed as a
  snapshot; the [benchmarks](benchmarks.md) compare the same Erlang source on
  JErlang and BEAM.

Each of those is demonstrated by code that runs in CI, not by a diagram.

## What does not work

These are deliberate boundaries of the prototype, not a backlog disguised as a
feature list:

- **No NIFs.** OTP's own `crypto`, `ssl` and much of `public_key` are NIF-backed
  and do not run as shipped. Some operations can be implemented explicitly
  through Java's security APIs; that is application code, not OTP crypto
  compatibility. See [Crypto without NIFs](examples/crypto-java.md).
- **No hot code upgrade and no OTP release handling.**
- **No `global` name registration or TLS distribution.** Distribution is plain
  TCP with the cookie handshake.
- **`gen_statem` and the wider standard library are not covered.** Nested
  non-tail CPU-bound calls are not preempted until they return.
- **OTP boot scripts are not interpreted.**
- **Native records**, OTP 29's experimental `-record #name{...}` data type, are
  not implemented. Ordinary tuple records are.

The [BEAM compatibility page](beam-compatibility.md) keeps the per-opcode notes,
and [the roadmap](roadmap.md) records why these boundaries were chosen.

## Artifacts

The normal Maven and Gradle artifact is the thin library jar. The runnable jar,
for the shell or a one-file experiment, is attached under the `all` classifier:

```sh
java -jar jerlang-3.0.0-all.jar
```

Java 21 or newer is required. An Erlang/OTP 29 installation is needed only to
run OTP's own modules or to join an OTP cluster; a JVM-only module that uses the
built-in runtime does not need one.

## Read the evidence

- **[Embedding in a JVM service](embedding.md)** — the primary 3.0 path:
  Maven and Gradle quickstarts, the lifecycle contract and the OTP boundary.
- **[Spring Boot service](examples/spring-boot.md)** — a singleton `Node` bean,
  an HTTP endpoint and container-managed shutdown.
- **[Getting started](getting-started.md)** — compile a module with the command
  line, then embed a node in Java.
- **[Migrating from 2.0 to 3.0](migration-2-to-3.md)** — the artifact packaging,
  lifecycle and Erlang-boundary changes for existing embedders.
- **[Supervision from Java](examples/supervised-app.md)** — the crash-and-restart
  demonstration.
- **[Crypto without NIFs](examples/crypto-java.md)** — hashing and HMAC through
  Java's security APIs, and the OTP compatibility that remains missing.
- **[Java interoperability](java-interop.md)** — the value mapping and the
  embedding API.
- **[Distribution](distribution.md)** — the protocol and the OTP boundary.
- **[Operating a node](operations.md)** — what the prototype exposes, and what it
  does not.
- **[Benchmarks](benchmarks.md)** — measured against BEAM on the same machine,
  including the million-process case JErlang does not reach.
- **[Million processes and a ring](examples/concurrency.md)** — the concurrency
  workload and its measured scaling limit.
- **[Roadmap](roadmap.md)** — what has been demonstrated and what was dropped.
