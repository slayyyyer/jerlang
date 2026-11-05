# mnesia from an HTTP service

This example answers a question the other three avoid: where does the data live?
In `http-service` it is a list inside a process, in `chat-service` an ETS table.
Here it is **mnesia on an Erlang/OTP node**, reached over distribution, with the
JVM node acting as an HTTP front end and a distribution client.

That split is not a stylistic choice. mnesia does not run inside this runtime
today (the [compatibility page](../beam-compatibility.md) and the probes below
explain why), but a jerlang node is a first-class distribution peer, and
`rpc:call/5` works. So the durable state stays where it already ran well. The
JVM build compiles `people.erl` with JErlang; only the separate OTP node uses
`erlc` and `erl`.

## The shape

```
curl ──▶ MnesiaServer.java        people.erl            erl -sname mnesia_node
         (JVM node, HTTP)   ──▶   rpc:call/5    ──▶     mnesia (ram_copies)
         mnesia_http@host                                mnesia_node@host
```

Both nodes need the same cookie. The JVM node takes its name from `-sname`, set
through `Emulator.parse` before `Node.start()`:

```java
Emulator.parse(new String[] {"-sname", nodeName, "-setcookie", cookie});
Node node = Node.start(NodeOptions.builder().schedulers(threads).build());
```

That is the only way to name an embedded node: `NodeOptions` has no name, and
`net_kernel:start/1` is not implemented, so the node can not make itself live
from Erlang. Without a name it stays `nonode@nohost` and no peer can reach it.

The peer defaults to `mnesia_node` on this node's own host, read back from the
runtime rather than hardcoded:

```java
String local = node.apply("erlang", "node").toString();   // mnesia_http@savannah-laptop
int at = local.indexOf('@');
return "mnesia_node" + (at < 0 ? "" : local.substring(at));
```

A hop, end to end, from a real run:

```console
$ curl -s localhost:8300/people
[{"name":"ada","age":36},{"name":"grace","age":45}]

$ curl -s -X PUT --data-binary '54' localhost:8300/people/linus
{"name":"linus","age":54}

$ curl -s localhost:8300/people
[{"name":"ada","age":36},{"name":"linus","age":54},{"name":"grace","age":45}]

$ curl -s localhost:8300/people/nobody
{"error":"not_found","detail":"nobody"}

$ curl -s -X PUT --data-binary 'abc' localhost:8300/people/linus
{"error":"invalid_age","detail":"abc"}

$ curl -s -X DELETE localhost:8300/people/linus -o /dev/null -w '%{http_code}\n'
204
$ curl -s -X DELETE localhost:8300/people/linus -o /dev/null -w '%{http_code}\n'
404

$ curl -s localhost:8300/health
{"mnesia":"up"}
```

Status codes for the whole set: `200` for the routes above, `204` for a delete
that removed a row, `404` for a missing row or an unknown path, `400` for a
non-numeric age, `405` for a method the path does not support.

## One place turns a dead peer into an answer

`rpc:call` does not raise on a dead node; it returns `{badrpc, Reason}`. Every
route funnels through one wrapper, so the failure is a value:

```erlang
call(Peer, Module, Function, Args) ->
    case rpc:call(Peer, Module, Function, Args, ?TIMEOUT) of
        {badrpc, Reason} -> {down, Reason};
        Result -> Result
    end.
```

With the OTP node killed and the service left running:

```console
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:8300/people
503
$ curl -s localhost:8300/health
{"mnesia":"down"}
```

The `5 s` timeout is what keeps that honest: without it the request would hang
for as long as the node took to answer, which after a crash is never.

## The OTP side

`otp/mnesia_node.erl` is not used by the JVM at all — it is compiled and run by
the OTP installation:

```sh
erlc -o otp otp/mnesia_node.erl
erl -sname mnesia_node -setcookie examplecookie -noshell -pa otp -eval 'mnesia_node:boot().'
```

```text
created table people
mnesia node 'mnesia_node@savannah-laptop' ready
```

The table is `{ram_copies, [node()]}`, so no `-mnesia dir` is needed and nothing
touches the disk. For rows that survive a restart, change it to
`{disc_copies, [node()]}` and pass `-mnesia dir`; nothing on the JVM side changes.

## Why mnesia is not inside the JVM

Measured against 1.0.0, by putting an OTP 29.1.2 install's `stdlib`, `kernel` and
`mnesia` ebins on the code path and calling mnesia from Erlang:

| Call | Result |
| --- | --- |
| `code:which(mnesia)` | `'./mnesia.beam'` — OTP's own module loads |
| `mnesia:start()` on an unnamed node | `{aborted,{node_not_running,nonode@nohost}}` |
| `mnesia:create_schema/1` | runs into `mnesia_bup` → `mnesia_schema` → `mnesia_lib`, then stops |
| `application_controller:get_loaded/1` | `Invalid function` — the runtime replaces that module |
| `net_kernel:start/1` | `Invalid function` — a node can not name itself |
| `file:read_file_info/1` | `Invalid function` — the disk layer mnesia needs |
| `dets:open_file/2` | does not return `{ok,Name}` |

So mnesia is three gaps away, not one: the application controller call, a way to
bring the node up, and `file:read_file_info/1`. Until then the interesting part is
that the gap is in the *runtime's* modules, not in the interpreter — OTP's real
mnesia code was being executed when it stopped.

## What this example does not do

- **One peer, no failover.** The peer is a single atom; there is no list, no
  retry with backoff, no reconnection policy.
- **No authentication beyond the cookie.** Distribution is unencrypted, and the
  cookie is on both command lines.
- **No `disc_copies`.** The table is in RAM on the OTP node, which is the point:
  nothing here depends on a working directory or a schema file.
- **The name in a response comes from mnesia, not from the request.** A row read
  back after a write echoes what was stored. Building the same JSON from a binary
  carved out of the path produced `{"name":"","age":36}` in 1.0.0; the list route,
  which uses the name mnesia returned, was correct. The cause is not established,
  so the example uses the value that is known to work and says so rather than
  hiding it.
- **Not a benchmark.** Nothing here measures the cost of a distributed call; the
  numbers would be dominated by the round trip, not by the runtime.
