# mnesia service

The same shape as `http-service` — Gradle pulls
`io.github.slayyyyer:jerlang:1.1.0`, Java is fixed plumbing, the routes are
`.erl` — with one difference that matters: **the data is not in the JVM**. It is
in mnesia on an Erlang/OTP node, reached over distribution. The JVM node loads no
mnesia modules and never starts mnesia; it speaks the distribution protocol and
sends `rpc:call/5`.

Why not mnesia in the JVM: it does not run there. `mnesia.beam` loads from an OTP
installation and gets as far as `mnesia_schema`/`mnesia_bup`, then stops on
`application_controller:get_loaded/1`, which this runtime does not implement, and
on a `file` module without `read_file_info/1`. The JVM side also can not make
itself a distributed node from Erlang (`net_kernel:start/1` is missing) — it takes
a name from `-sname`, which is what this example does.

## Running it

Needs a JDK 21, `erlc`/`erl` on the PATH (or `ERLC=/path/to/erlc`), and Gradle
8.5 or newer (tested with 9.8.2).

Terminal one, the node that owns the table:

```sh
cd examples/mnesia-service
erlc -o otp otp/mnesia_node.erl
erl -sname mnesia_node -setcookie examplecookie -noshell -pa otp -eval 'mnesia_node:boot().'
```

```
created table people
mnesia node 'mnesia_node@savannah-laptop' ready
```

Terminal two, the HTTP service:

```sh
gradle run
```

```
listening on http://127.0.0.1:8080/ as mnesia_http@savannah-laptop, peer mnesia_node@savannah-laptop
```

```sh
curl -s localhost:8080/people
curl -s localhost:8080/people/ada
curl -s -X PUT --data-binary '54' localhost:8080/people/linus
curl -s -X DELETE localhost:8080/people/linus -o /dev/null -w '%{http_code}\n'
```

The peer defaults to `mnesia_node` on this node's own host. Point it elsewhere
with `-Ppeer=mnesia_node@other-host` (or `-Djerlang.example.peer=`), and change
the JVM node's own name and cookie with `-Pnodename=` and `-Pcookie=` — the OTP
node has to be started with the same cookie.

## Files

| File | What it is |
| --- | --- |
| `build.gradle` | One dependency, one `erlc` task, as in the other examples. |
| `src/main/java/example/MnesiaServer.java` | Names the node, listens, calls `people:handle/4`. Fixed. |
| `src/main/erl/people.erl` | The routes, the `rpc:call` wrapper and the JSON. This is the service. |
| `otp/mnesia_node.erl` | The OTP node. Not used by the JVM at all; it owns the table. |

## The parts that are not obvious

**A peer is configuration, not state.** The peer atom arrives as an argument on
every request; there is no global that a request can race with.

**Timeouts are the failure mode.** `rpc:call/5` with a 5 s timeout turns an
unreachable node into `{badrpc, Reason}` and a `503`
(`{"mnesia":"down"}`), instead of a request that hangs until the client gives up.
Kill the OTP node and the service keeps serving, answering 503.

**The table is `ram_copies`,** so no `-mnesia dir` is needed and nothing is
written to disk. Change `otp/mnesia_node.erl` to `{disc_copies, [node()]}` and
pass `-mnesia dir` for rows that survive a restart. That is mnesia's
configuration; the JVM side does not change.

**The cookie is a secret.** It is on the command line of both nodes, which is
visible in the process list. Fine for a local example; not for anything else.

Run time needs Erlang only for the *OTP node*. The JVM services need `erlc` at
build time and nothing Erlang-shaped to run.
