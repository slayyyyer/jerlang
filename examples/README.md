# Examples

!!! note "Build plugin (in progress)"
    A build plugin that runs `.erl` sources directly, without a separate
    compilation step, is in progress. These examples keep the current explicit
    compile flow so they remain runnable today; the plugin path will be added
    when it lands.

Ten programs: the two directions of the Java interop, a crypto-through-Java
example, four services built on the published artifact, a test harness, and the
Maven and Gradle embedding quickstarts.

## Which one do I want?

| I want to… | Start from |
| --- | --- |
| call Erlang from Java, in-process | [`gen-server`](gen-server/README.md) |
| build a Maven service with no Erlang toolchain | [`maven-embed`](maven-embed/README.md) |
| build a Gradle service with no Erlang toolchain | [`gradle-embed`](gradle-embed/README.md) |
| call a Java class from Erlang | [`java-service`](java-service/README.md) |
| hash or MAC without a NIF | [`crypto-java`](crypto-java/README.md) |
| serve HTTP with the routes in Erlang | [`http-service`](http-service/README.md) |
| push events to a browser | [`chat-service`](chat-service/README.md) |
| keep data in mnesia on an OTP node | [`mnesia-service`](mnesia-service/README.md) |
| test my `.erl` modules from JUnit | [`junit-harness`](junit-harness/README.md) |
| run a supervision tree, started from Java | [`supervised-app`](supervised-app/README.md) |

| Directory | What it shows |
| --- | --- |
| `gen-server` | Java embeds the runtime, starts an Erlang `gen_server` and calls it. |
| `maven-embed` | Maven compiles `.erl` with JErlang and Java calls it; no `erlc`. |
| `gradle-embed` | The same workflow with Gradle and JErlang; no `erlc`. |
| `java-service` | Erlang calls static methods of a Java class through `java:call_static/3`. |
| `crypto-java` | SHA-256, HMAC-SHA256 and secure random through the JDK's security APIs; no OTP `crypto` module. |
| `http-service` | Erlang behind an HTTP listener: Gradle pulls the artifact, Java is fixed plumbing, the routes are `.erl`. |
| `chat-service` | The same shape with a push direction: rooms, ETS history and server-sent events through a Java sink Erlang calls. |
| `mnesia-service` | The data lives in mnesia on an OTP node, reached over distribution with `rpc:call/5`; the JVM node is the HTTP front end. |
| `junit-harness` | Erlang modules under test in a JUnit 5 suite, no Erlang installation needed to run it. |
| `supervised-app` | OTP's `application` and `supervisor` running on the JVM: a worker is killed and restarted, with the pid changing. |

All nine use `org.jerlang.api.Node`, which owns the runtime: it resets the
process-wide code path and module registry, applies the options and starts the
schedulers; on `close()`, it drains the object handle table and clears that
state again. Code paths and an OTP root are supplied through `NodeOptions`, not
by calling `CodePaths.reset()` in each program. `mnesia-service` also names its
node with `-sname`, because a distribution peer needs an identity.

## Running them

```sh
mvn -B -DskipTests compile
bash examples/run.sh
```

`run.sh` needs `javac`, `erl` and `erlc` on the PATH. The first example runs
OTP's own `gen_server`, so it needs a real Erlang installation, not just the
runtime. The second one only needs a JDK, but `run.sh` compiles both.

The rest are their own builds and are not covered by `run.sh`:

```sh
cd examples/maven-embed
mvn verify                  # compiles greeter.erl with JErlang, then tests it

cd examples/gradle-embed
gradle test                 # compiles greeter.erl with JErlang, then tests it

cd examples/http-service
gradle run                 # http://127.0.0.1:8080/

cd examples/chat-service
gradle run                 # http://127.0.0.1:8080/, 64 streams

cd examples/junit-harness
gradle test                # compiles the .erl with JErlang, then the suite

cd examples/crypto-java
gradle test                # known SHA-256 and HMAC-SHA256 vectors

cd examples/supervised-app
gradle run -PotpLib="$(erl -noshell -eval 'io:format("~s",[code:root_dir()]),halt().')/lib"
```

`mnesia-service` also needs the OTP node that owns the table, so it takes two
terminals:

```sh
cd examples/mnesia-service
erlc -o otp otp/mnesia_node.erl
erl -sname mnesia_node -setcookie examplecookie -noshell -pa otp -eval 'mnesia_node:boot().'

gradle run                 # http://127.0.0.1:8080/, in another terminal
```

The Maven and Gradle quickstarts, `junit-harness`, `http-service`, `chat-service`
and the JVM side of `mnesia-service` compile their modules with JErlang.
`supervised-app` still uses `erlc` for its own modules because it runs OTP's own
`application` and `supervisor`, and the OTP node in `mnesia-service` is compiled
by the OTP installation that runs it.

Erlang at **run** time is needed only by `supervised-app` and by the OTP node
that `mnesia-service` talks to. `http-service`, `chat-service`, `junit-harness`,
`maven-embed`, `gradle-embed` and the JVM side of `mnesia-service` need none at
all. See their READMEs and docs pages:
[maven-embed](maven-embed/README.md),
[gradle-embed](gradle-embed/README.md),
[http-service](http-service/README.md),
[chat-service](chat-service/README.md),
[mnesia-service](mnesia-service/README.md),
[junit-harness](junit-harness/README.md),
[supervised-app](supervised-app/README.md).

Expected output:

```
--- Java calls Erlang ---
counter: 2
--- Erlang calls Java ---
hello, erlang
6 * 7 = 42
```

## Why the OTP root is passed in

`counter` is an ordinary `gen_server` callback module. The server that runs it is
OTP's `stdlib`, so `GenServerDemo` adds `stdlib` and `kernel` from the OTP
installation to the code path. A node that only uses the built-in modules does
not need that — which is exactly why `supervised-app` takes `-PotpLib=` and
`http-service` does not.
