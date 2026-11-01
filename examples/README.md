# Examples

Two programs, one for each direction of the Java interop, plus three services built
on the published artifact.

| Directory | What it shows |
| --- | --- |
| `gen-server` | Java embeds the runtime, starts an Erlang `gen_server` and calls it. |
| `java-service` | Erlang calls static methods of a Java class through `java:call_static/3`. |
| `http-service` | Erlang behind an HTTP listener: Gradle pulls the artifact, Java is fixed plumbing, the routes are `.erl`. |
| `chat-service` | The same shape with a push direction: rooms, ETS history and server-sent events through a Java sink Erlang calls. |
| `mnesia-service` | The data lives in mnesia on an OTP node, reached over distribution with `rpc:call/5`; the JVM node is the HTTP front end. |

All four use `org.jerlang.api.Node`, which owns the runtime: it starts the
schedulers, applies the options and, on `close()`, drains the object handle
table. `mnesia-service` also names its node with `-sname`, because a distribution
peer needs an identity.

## Running them

```sh
mvn -B -DskipTests compile
bash examples/run.sh
```

`run.sh` needs `javac`, `erl` and `erlc` on the PATH. The first example runs
OTP's own `gen_server`, so it needs a real Erlang installation, not just the
runtime. The second one only needs a JDK, but `run.sh` compiles both.

`http-service`, `chat-service` and `mnesia-service` are their own Gradle builds
and are not covered by `run.sh`:

```sh
cd examples/http-service
gradle run                 # http://127.0.0.1:8080/

cd examples/chat-service
gradle run                 # http://127.0.0.1:8080/, 64 streams
```

`mnesia-service` also needs the OTP node that owns the table, so it takes two
terminals:

```sh
cd examples/mnesia-service
erlc -o otp otp/mnesia_node.erl
erl -sname mnesia_node -setcookie examplecookie -noshell -pa otp -eval 'mnesia_node:boot().'

gradle run                 # http://127.0.0.1:8080/, in another terminal
```

The first two need `erlc` to compile the `.erl` files and nothing Erlang-shaped to
run them; `mnesia-service` needs a running OTP node as well. See their READMEs and
docs pages: [http-service](http-service/README.md),
[chat-service](chat-service/README.md),
[mnesia-service](mnesia-service/README.md).

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
not need that.
