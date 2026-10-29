# JErlang

An Erlang runtime for the [Java Virtual Machine](https://en.wikipedia.org/wiki/Java_virtual_machine) (JVM).

[![Tests](https://github.com/slayyyyer/jerlang/actions/workflows/tests.yml/badge.svg?branch=master)](https://github.com/slayyyyer/jerlang/actions/workflows/tests.yml)

JErlang reads the BEAM files that `erlc` produces and interprets them on the
JVM. Erlang processes, message passing, links, monitors and distribution are
implemented in Java, and Java code is one reflective call away instead of a
separate node, a port or an external term format round trip.

## Intended goals

**Run Erlang where the Java code is.** The primary target is a single JVM
service that hosts an Erlang control plane next to Java code: supervision
trees, message-driven workers and a Java API, in one process, with calls in
both directions. The same node joins an Erlang/OTP cluster over the
distribution protocol, so Erlang and Java can be deployed independently.

**Use OTP's own library as the compatibility bar.** The measure of correctness
is not "how many opcodes exist" but "does `gen_server` from an OTP installation
start and behave". Running OTP's `gen_server`, `supervisor`, `application`,
`proc_lib` and `logger` unmodified is the bar for the standard library work.

**Stay honest about the boundary.** JErlang is an interpreter, not a port of
BEAM's C internals. There are no NIFs, no hot code upgrade, no OTP release
handling and no bytecode compiler today. Those are decisions with reasons in
[docs/roadmap.md](docs/roadmap.md), not forgotten features.

The north-star metric is the share of a checked-in module corpus that loads and
runs correctly against OTP 29. Opcode counts are a means, not the goal.

## Status

What works today:

* BEAM files compiled by OTP 29 load, including the literal, line, atom, export,
  import and lambda tables.
* Most opcodes of the interpreter are implemented: calls, tail calls, funs,
  `try`/`catch`, stack frames, binaries, maps, tuples, list comprehensions, and
  exceptions with real stacktraces. Record updates are not among them yet.
* Distribution in both directions: handshake, external term format (including
  funs, ports, and compressed terms on receive), links, monitors, remote spawn,
  `erpc` and `rpc`, and node monitors. Verified against a real OTP 29 peer.
* `gen_tcp`, `gen_udp` and `inet`, including IPv6 and multicast.
* A reflective Java bridge (`java:new`, `java:call`, fields, release) and an
  embedding entry point for Java calling Erlang.
* A 315-test suite, of which 12 are opt-in tests against a live OTP node, run by
  GitHub Actions on every push and pull request.

What does not work yet:

* 20 OTP 29 opcodes, including the binary-matching family, `bs_create_bin`,
  record updates and `bif3`. Many real modules hit these on the first function.
* ETS is a facade: `new`, `lookup` and `all` exist, but `insert` is a stub, so a
  table can never hold an object.
* The OTP behaviours and most of the standard library: there is no `gen_server`,
  `supervisor`, `application`, `logger` or `code` module.
* Fair scheduling. There are no reductions, so a CPU-bound loop can hold a
  scheduler while other processes wait.
* A module search path. Modules are loaded from the working directory only.

[docs/beam-compatibility.md](docs/beam-compatibility.md) keeps the precise list
of unimplemented opcodes and the constructs that need them.

## Roadmap

|Version|Focus                     |Exit test                                                        |Status |
|:-----:|--------------------------|-----------------------------------------------------------------|:-----:|
|0.1    |File format               |BEAM files parse                                                 |Done   |
|0.2    |Proof of concept          |A simple "hello world" is interpreted                            |Done   |
|0.3    |Language                  |Most BEAM opcodes are implemented                                |Partial|
|0.4    |Network                   |Distribution and sockets work against OTP 29                     |Done   |
|0.5    |Language completeness     |A corpus of constructs runs with no unsupported opcode           |Next   |
|0.6    |Standard library boot     |Unmodified `gen_server`/`supervisor` start via `application`     |Planned|
|0.7    |Runtime and concurrency   |Preemption, timer bounds, no leaked processes under stress       |Planned|
|0.8    |Interop and embedding     |Java↔Erlang examples in CI and a frozen API                      |Planned|
|0.9    |Cluster and operations    |Metrics, reconnection and graceful shutdown under load           |Planned|
|1.0    |Release                   |Benchmarks against OTP 29, 24 hour soak, fuzzing, artifact       |Planned|

The full plan, including what was dropped from the original roadmap and why, is
in [docs/roadmap.md](docs/roadmap.md).

## Java interoperability

Erlang code runs on the JVM itself, so calling Java is a direct reflective
call: no external term format, no sockets, no separate node.

```erlang
Builder = java:new("java.lang.StringBuilder", []),
java:call(Builder, "append", ["hello"]),
"hello" = java:call(Builder, "toString", []),
java:release(Builder).
```

| Function | Purpose |
|----------|---------|
| `java:new(Class, Args)` | Call a constructor, returns an object handle |
| `java:call(Object, Method, Args)` | Call an instance method |
| `java:call_static(Class, Method, Args)` | Call a static method |
| `java:get(Object, Field)` / `java:set(Object, Field, Value)` | Instance fields |
| `java:get_static(Class, Field)` / `java:set_static(Class, Field, Value)` | Static fields |
| `java:release(Object)` | Drop the handle, allowing the JVM to collect the object |

Values are converted on the way in and out: integers to `int`/`long`/`short`/
`byte`/`double`/`float`/`BigInteger`/`BigDecimal`, floats to `double`/`float`,
strings and atoms to `String`, binaries to `byte[]`, lists to collections or
arrays, tuples to `Object[]`, `true`/`false` to `boolean`, and `null` back to
the atom `undefined`. Java exceptions surface as
`error({java_exception, ClassName, Message})`.

The reverse direction is `org.jerlang.api.Bridge`:

```java
Term result = Bridge.call("lists", "reverse", List.of(Integer.of(1), Integer.of(2)));
```

The bridge's type mapping and handle lifetime are not frozen yet; that is the
0.8 milestone.

## Compiled Erlang

JErlang does not compile Erlang source. Compile it with `erlc` and load the
resulting `.beam` from the working directory:

```
erlc -o src/test/resources src/test/erl/bridge_demo.erl
```

Modules built by current OTP releases need the loader support described in
[docs/beam-compatibility.md](docs/beam-compatibility.md).

## Build and test

JErlang builds with Maven and JDK 21, the version CI uses. The bytecode target
is still Java 8. The unit tests need no installed Erlang.

```
mvn test
mvn package
java -jar target/jerlang-0.3.0.jar
```

The live interoperability tests are opt-in because they need a peer node. Start
an OTP 29 node with the cookie from
[docs/distribution.md](docs/distribution.md), then:

```
mvn test -Dtest=OtpInteropTest -Djerlang.dist.otp=true
```

## Documentation

* [docs/roadmap.md](docs/roadmap.md) — milestones from 0.4 to a production release
* [docs/beam-compatibility.md](docs/beam-compatibility.md) — opcode and language coverage
* [docs/distribution.md](docs/distribution.md) — distribution protocol and sockets

## Contribute

JErlang uses the [C4.1 (Collective Code Construction Contract) process](http://rfc.zeromq.org/spec:22) for contributions. Pull requests are handled in this repository.

## See also

* [erjang: A JVM-based Erlang VM](https://github.com/trifork/erjang)
* [JErlang: Erlang with Joins](http://www.doc.ic.ac.uk/~susan/jerlang/)

JErlang began as the [jerlang/jerlang](https://github.com/jerlang/jerlang)
project; this repository continues it.

## License

[Apache 2.0 License](LICENSE)
