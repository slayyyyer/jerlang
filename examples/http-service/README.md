# HTTP service

An HTTP service where the Java side is fixed plumbing and everything you would
actually change is Erlang. `io.github.slayyyyer:jerlang` comes from Maven Central,
so the build file here is what a consumer's build file looks like:

```gradle
dependencies {
    implementation 'io.github.slayyyyer:jerlang:1.0.0'
}
```

Requests enter through the JDK's `HttpServer`, cross into Erlang as three
binaries (method, path, body), and come back as a tuple:

```erlang
{Status, ContentType, Body}
```

Every route, status code and byte of every body is decided in `api.erl`. Adding
a route means adding a `handle/3` clause; nothing in Java changes.

## Running it

Needs a JDK 21, `erlc` on the PATH (or `ERLC=/path/to/erlc`), and Gradle 8.5 or
newer — tested with 9.8.2. No wrapper is committed.

```sh
cd examples/http-service
gradle run                 # http://127.0.0.1:8080/
gradle run -Pport=9000     # another port
```

`erlc` is only needed to compile: `compileErlang` writes `build/ebin`, and the
runtime loads the `.beam` files from there. It runs on a plain JDK — the service
state is a registered process with a `receive` loop, not an OTP `gen_server`, so
no Erlang installation is needed at run time.

To run it as a standalone install:

```sh
gradle installDist
./build/install/http-service/bin/http-service
```

Run that from this directory: the script's default code path is `build/ebin`,
relative to the working directory.

```sh
curl -s localhost:8080/health                    # ok
curl -s -X PUT -d 'Berlin' localhost:8080/kv/city
curl -s localhost:8080/kv/city                   # {"key":"city","value":"Berlin"}
curl -s localhost:8080/kv                        # [{"key":"city","value":"Berlin"}]
curl -s localhost:8080/metrics                   # runtime counters
```

## Files

| File | What it is |
| --- | --- |
| `build.gradle` | The whole Java integration: one dependency, one `erlc` task. |
| `src/main/java/example/Server.java` | Listener, request → Erlang call, reply → response. Fixed. |
| `src/main/erl/api.erl` | Routing, status codes, JSON. This is the service. |
| `src/main/erl/store.erl` | The state: a list of key/value pairs in one registered process. |

## What the runtime does not have

Three things had to be worked around, all found by trying them against 1.0.0 and
all documented in `docs/examples/http-service.md`:

* no `binary:split/2`, `binary:part/3` or `string` module, so paths are matched
  as binaries;
* a binary accumulated in a loop (`<<Acc/binary, More/binary>>`) compiles but
  fails at run time with *Unsupported bs_create_bin segment type: private_append*,
  so `escape/1` recurses and appends the call's result;
* map lookups compare binary keys by object identity rather than by content, so
  the store keeps a list and matches keys by content with pattern matching.
