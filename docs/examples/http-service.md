# An Erlang HTTP service, from Gradle

This example is the smallest honest version of the embedding shape people ask
for: a Java process serves HTTP, and the service itself is Erlang. The Java class
is fixed plumbing you never edit; the routes, status codes and bodies live in
`.erl` files next to it.

The runtime is not built from this repository. It is the published artifact:

```gradle
plugins {
    id 'application'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.slayyyyer:jerlang:1.1.0'
}
```

So this is also a check that the Central artifact is usable on its own, without
a checkout of the source tree.

## The shape of it

```
examples/http-service/
├── build.gradle
├── settings.gradle
├── src/main/java/example/Server.java   fixed plumbing
└── src/main/erl/
    ├── api.erl                         routing, status codes, JSON
    └── store.erl                       the state
```

One request crosses the boundary as three binaries and comes back as one tuple:

```
method, path, body  ->  {Status, ContentType, Body}
```

`Server.java` reads the `HttpServer` exchange, calls `api:handle/3` with those
three terms, and writes whatever tuple comes back:

```java
Term reply = node.apply("api", "handle",
    Bridge.toTerm(exchange.getRequestMethod().getBytes(StandardCharsets.UTF_8)),
    Bridge.toTerm(path.getBytes(StandardCharsets.UTF_8)),
    Bridge.toTerm(body));

Object[] parts = (Object[]) Bridge.toJava(reply);
send(exchange, ((Number) parts[0]).intValue(),
    new String((byte[]) parts[1], StandardCharsets.UTF_8), (byte[]) parts[2]);
```

That is the whole Java side of the contract. `204` and `304` send no body; the
listener stops accepting before `node.close()` drains on shutdown.

## Running it

Requires a JDK 21, `erlc` on the PATH (or `ERLC=/path/to/erlc`), and Gradle 8.5 or
newer — tested with 9.8.2. No Gradle wrapper is committed, so use an installed
Gradle.

```sh
cd examples/http-service
gradle run                 # http://127.0.0.1:8080/
gradle run -Pport=9000
```

```sh
gradle installDist
./build/install/http-service/bin/http-service     # run from this directory
```

The `run` task passes `jerlang.example.ebin`; the installed script falls back to
`build/ebin` relative to the working directory. `erlc` runs once, in
`compileErlang`, which `classes` depends on — nothing compiles Erlang at run
time, and the service needs no Erlang installation to run.

The HTTP thread pool and the scheduler count are the same number on purpose. One
HTTP thread is inside one Erlang call at a time, so more HTTP threads than
schedulers would only queue work that is already runnable.

## Every route is Erlang

`api.erl` is a set of `handle/3` clauses — method as the first argument, path as
the second:

```erlang
handle(<<"GET">>, <<"/health">>, _Body) ->
    text(200, <<"ok\n">>);

handle(<<"GET">>, <<"/kv">>, _Body) ->
    json(200, kv_json(store:all()));

handle(<<"GET">>, Path, _Body) ->
    case key_of(Path) of
        {ok, Key} -> lookup(Key);
        error -> json(404, error_json(<<"no_such_route">>, Path))
    end;

handle(Method, _Path, _Body) ->
    json(405, error_json(<<"method_not_allowed">>, Method)).
```

Adding a route is adding a clause above the catch-alls. Nothing in Java changes,
and no route is compiled into the listener.

`key_of/1` matches the path as a binary, because this runtime has no
`binary:split/2` and no `string` module:

```erlang
key_of(<<"/kv/", Key/binary>>) when Key =/= <<>> -> {ok, Key};
key_of(_) -> error.
```

A key is one path segment: nothing is URL-decoded, so a key cannot contain a
slash. That is the limit of an example, not of the runtime.

## The state is a process

`store.erl` keeps the entries in one registered process with a `receive` loop:

```erlang
loop(Entries) ->
    receive
        {From, {get, Key}} ->
            From ! {store, find(Key, Entries)},
            loop(Entries);
        {From, {put, Key, Value}} ->
            From ! {store, ok},
            loop(store(Key, Value, Entries))
    end.
```

Callers send a message and wait in a `receive`. That is a parked process, not a
blocked thread: a thousand concurrent callers cost a thousand processes and no
OS threads, and the scheduler keeps running everything else while they wait.

It is a list rather than a map for a concrete reason — see
[what this runtime does not have](#what-this-runtime-does-not-have-yet) below.

## A session

```console
$ curl -s localhost:8080/health
ok
$ printf 'value with "quotes" and\na newline' | curl -s -X PUT --data-binary @- localhost:8080/kv/name -o /dev/null -w '%{http_code}\n'
204
$ curl -s localhost:8080/kv/name
{"key":"name","value":"value with \"quotes\" and\na newline"}
$ curl -s -X PUT --data-binary 'Berlin' -o /dev/null -w '%{http_code}\n' localhost:8080/kv/city
204
$ curl -s localhost:8080/kv
[{"key":"name","value":"replaced"},{"key":"city","value":"Berlin"}]
$ curl -s localhost:8080/kv/missing
{"error":"not_found","detail":"missing"}
$ curl -s -X DELETE localhost:8080/kv/city -o /dev/null -w '%{http_code}\n'
204
$ curl -s -X DELETE localhost:8080/kv/city -o /dev/null -w '%{http_code}\n'
404
$ curl -s -X POST localhost:8080/kv/name
{"error":"method_not_allowed","detail":"POST"}
```

Timings on a loopback connection, from curl's `time_total`: `GET /health`
0.0019 s, `PUT /kv/a` 0.0020 s, `GET /kv/a` 0.0027 s, with the first request after
startup at 0.0077 s. That is a single client measuring round trips — not a
throughput benchmark; for those, see [Benchmarks](../benchmarks.md).

## Metrics come from the runtime, not from Erlang

`GET /metrics` is served in Java, straight from the node's own counters:

```java
if (path.equals("/metrics")) {
    send(exchange, 200, "text/plain; charset=utf-8",
        node.metrics().toText().getBytes(StandardCharsets.UTF_8));
    return;
}
```

```console
$ curl -s localhost:8080/metrics | head -6
jerlang_processes 1
jerlang_ports 0
jerlang_run_queue 0
jerlang_reductions 423
jerlang_mailbox_messages 0
jerlang_messages_delivered 22
```

Scraping keeps working when the thing that broke is the service's own Erlang
code. The request path has the other half of that property: when `api:handle`
raised during development, those requests answered `500` and the listener kept
serving.

## What this runtime does not have yet

Three gaps shaped this code, all checked against 1.0.0 by calling them:

| What | Result |
| --- | --- |
| `binary:split/2`, `binary:replace/3`, `binary:part/3` | `Invalid function: binary:split/2` |
| the `string` module | `Invalid module: string` |
| `iolist_to_binary/1` | `Invalid function: erlang:iolist_to_binary/1` |
| `list_to_binary/1` on a computed list | `Invalid function: erlang:list_to_binary/1` — it only works where `erlc` folds the call at compile time |
| `<<Acc/binary, More/binary>>` inside a loop | compiles, then fails at run time: `Unsupported bs_create_bin segment type: private_append` |
| map lookups with binary keys | compare by object identity, not content |

The last one is why `store.erl` is a list. Storing a key and then looking it up
with an equal but separately built binary returns `error`:

```erlang
maps:find(<<"a">>, maps:put(binary:copy(<<"a">>), 1, #{}))  % error
K = <<"a">>, maps:find(<<"a">>, maps:put(K, 1, #{}))         % error
K = <<"a">>, maps:find(K, maps:put(K, 1, #{}))               % {ok,1}
```

Pattern matching and `=:=` *do* compare binaries by content, so the store matches
keys with a head pattern instead. The same reason is why `escape/1` recurses and
appends the result of the recursive call rather than threading an accumulator.

Check [BEAM compatibility](../beam-compatibility.md) before reaching for a
function from the standard library; these six were found by trying them, not by
reading a list.

## If you ship something like this

* Move the state to ETS (measured at 5x BEAM's cost) or to an OTP `gen_server`
  for supervision, and only then worry about the linear scan.
* Decode URLs and handle query strings; the route matcher here takes one segment.
* Use a JSON library on one side of the boundary rather than hand-rolling the
  encoder, or keep the bodies to a shape you control.
* Terminate TLS in front of it and keep the listener on loopback, as with any
  plain-HTTP service. See [Operating a node](../operations.md) for the drain
  timeout and shutdown behaviour.
