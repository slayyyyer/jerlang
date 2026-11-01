# Examples

Two programs, one for each direction of the Java interop, plus an HTTP service
built on the published artifact.

| Directory | What it shows |
| --- | --- |
| `gen-server` | Java embeds the runtime, starts an Erlang `gen_server` and calls it. |
| `java-service` | Erlang calls static methods of a Java class through `java:call_static/3`. |
| `http-service` | Erlang behind an HTTP listener: Gradle pulls the artifact, Java is fixed plumbing, the routes are `.erl`. |

All three use `org.jerlang.api.Node`, which owns the runtime: it starts the
schedulers, applies the options and, on `close()`, drains the object handle
table.

## Running them

```sh
mvn -B -DskipTests compile
bash examples/run.sh
```

`run.sh` needs `javac`, `erl` and `erlc` on the PATH. The first example runs
OTP's own `gen_server`, so it needs a real Erlang installation, not just the
runtime. The second one only needs a JDK, but `run.sh` compiles both.

`http-service` is its own Gradle build and is not covered by `run.sh`:

```sh
cd examples/http-service
gradle run                 # http://127.0.0.1:8080/
```

It needs `erlc` to compile the `.erl` files and nothing Erlang-shaped to run
them. See its [README](http-service/README.md) and the
[docs page](../docs/examples/http-service.md).

Expected output:

```
--- Java calls Erlang ---
counter: 2
--- Erlang calls Java ---
hello, erlang
6 * 7 = 42
```

## Why the OTP root is passed in

`counter` is an ordinary `gen_server` callback module. The server that runs it
is OTP's `stdlib`, so `GenServerDemo` adds `stdlib` and `kernel` from the OTP
installation to the code path. A node that only uses the built-in modules does
not need that.
