# Java calls Erlang: OTP's gen_server from Java

`examples/gen-server` embeds the runtime in a Java program, starts an ordinary
Erlang `gen_server` and calls it. Nothing here is a JErlang reimplementation of
`gen_server`: `counter` is a three-clause callback module, and the server that
runs it is the one in OTP's own `stdlib`.

This is the shape of the intended deployment — a JVM service that hosts an
Erlang control plane next to Java code, with calls in both directions inside one
process.

## The Erlang side

```erlang
%% A gen_server that counts. OTP's own gen_server runs it; nothing here is a
%% JErlang rewrite.
-module(counter).
-behaviour(gen_server).

-export([start/0, increment/1, value/1, stop/1]).
-export([init/1, handle_call/3, handle_cast/2]).

start() ->
    gen_server:start({local, counter}, ?MODULE, 0, []).

increment(Server) ->
    gen_server:cast(Server, increment).

value(Server) ->
    gen_server:call(Server, value).

stop(Server) ->
    gen_server:stop(Server).

init(Count) ->
    {ok, Count}.

handle_call(value, _From, Count) ->
    {reply, Count, Count}.

handle_cast(increment, Count) ->
    {noreply, Count + 1}.
```

The state is the count, `increment/1` is an asynchronous cast, and `value/1` is
a synchronous call. The error handling, the reply protocol and the process
registry all belong to OTP.

## The Java side

```java
public static void main(String[] args) {
    String otp = System.getProperty("jerlang.otp.dir");
    if (otp == null) {
        System.err.println("run with -Djerlang.otp.dir=/path/to/erlang");
        System.exit(2);
    }

    try (Node node = Node.start(NodeOptions.builder()
            .otpRoot(Path.of(otp)).build())) {
        Tuple started = node.apply("counter", "start").toTuple();
        Term server = started.element(2);

        node.apply("counter", "increment", server);
        node.apply("counter", "increment", server);
        Object value = Bridge.toJava(node.apply("counter", "value", server));
        System.out.println("counter: " + value);

        node.apply("counter", "stop", server);
    }
}
```

`NodeOptions.otpRoot` takes `code:root_dir()` and locates the versioned
`kernel-*/ebin` and `stdlib-*/ebin` directories under `<root>/lib`. It does not
need an `ebin/2` helper, and the working directory remains the default code path
entry for `counter.beam`.

## What each part is doing

| Line | Why it is there |
| --- | --- |
| `NodeOptions.otpRoot(Path.of(otp))` | `counter` is a real `gen_server`, so `gen_server`, `proc_lib` and the rest of `stdlib` must be loadable, and `kernel` under them. The node adds both. |
| `try (Node node = Node.start(...))` | `Node` is `AutoCloseable`; closing it stops the schedulers, drains the Java object handle table and clears the code path and module registry. |
| `apply("counter", "start")` | Returns the `{ok, Pid}` tuple from `gen_server:start/4`. |
| `started.element(2)` | The second element of that tuple is the server's pid. |
| `apply("counter", "increment", server)` | A cast: it returns `ok` immediately and the increment happens in the Erlang process. |
| `Bridge.toJava(...)` | Converts the Erlang integer term to a Java value for printing. |

## Running it

```sh
mvn -B -DskipTests compile
bash examples/run.sh
```

`run.sh` compiles both examples with `javac`, compiles the `.beam` files with
`erlc`, and runs each one from the directory the modules were compiled into. It
needs `javac` and `erl` on the `PATH`. It also builds the runtime classpath with
`dependency:build-classpath`, because `target/classes` alone is not enough: the
loader inflates compressed BEAM chunks through `jzlib`, so a run against the
bare classes fails with `NoClassDefFoundError` before the first call.

To run this one yourself:

```sh
OTP_ROOT="$(erl -noshell -eval 'io:format("~s", [code:root_dir()]), halt().')"
java -cp "$CP" -Djerlang.otp.dir="$OTP_ROOT" GenServerDemo
```

Expected output:

```text
--- Java calls Erlang ---
counter: 2
```

Two increments, then a synchronous read, so `2` is the count the Erlang process
is holding.

## Where this leads in a real service

- **One node per process.** Start it at application startup and close it at
  shutdown. Creating a `Node` per request would restart the schedulers and
  throw away every process and ETS table in it.
- **Let OTP own the concurrency.** Reach for `supervisor` and `gen_server`
  rather than Java executors for state that must survive a crash; the Java side
  can stay a thin transport.
- **Expose the boundary deliberately.** `node.apply/3` for Erlang functions,
  `registerFunction` for Java callbacks the Erlang side calls by name, and
  `NodeOptions.reflection(true)` only if arbitrary class names have to be
  reachable from Erlang.
- **Watch the handle table.** A Java object with no value mapping comes back to
  Erlang as a handle; `java:release/1` drops it, `Node.close()` drains the rest,
  and `maxHandles` (100 000 by default) raises `system_limit` when exceeded.

Errors surface the way the runtime documents them: a Java exception thrown
through reflection arrives in Erlang as `error({java_exception, Class, Msg})`,
and an Erlang `exit` does not stop the JVM.
