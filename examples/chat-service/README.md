# Chat service

The key-value example with the direction reversed where it matters: Erlang has to
reach a client that is not waiting for a reply. The transport is server-sent
events, the rooms are processes, the history is an ETS table, and the Java side
is still fixed plumbing that you never edit to add a feature.

`io.github.slayyyyer:jerlang:2.0.0` comes from Maven Central, as in the
`http-service` example.

## Running it

Needs a JDK 21, `erlc` on the PATH (or `ERLC=/path/to/erlc`), and Gradle 8.5 or
newer (tested with 9.8.2).

```sh
cd examples/chat-service
gradle run                          # http://127.0.0.1:8080/, 64 streams
gradle run -Pport=9000 -Pclients=8  # fewer concurrent streams
```

Then, in three terminals:

```sh
curl -N -s localhost:8080/events/general
```

```sh
curl -N -s localhost:8080/events/general
```

```sh
curl -s -X POST -H 'X-Author: justin' --data-binary 'hello everyone' \
  localhost:8080/say/general
```

Both listeners print:

```
retry: 2000

id: 1
event: message
data: general|justin|hello everyone

```

`-N` matters: without it curl buffers the stream and you see nothing.

```sh
curl -s localhost:8080/rooms              # [{"room":"general"}]
curl -s localhost:8080/history/general    # [{"seq":1,"author":"justin","text":"hello everyone"}]
curl -s localhost:8080/metrics            # runtime counters
```

## Files

| File | What it is |
| --- | --- |
| `build.gradle` | One dependency, one `erlc` task, as in `http-service`. |
| `src/main/java/example/ChatServer.java` | Listener, the Java sink Erlang calls, the SSE writer. Fixed. |
| `src/main/erl/chat.erl` | Routing, SSE frames, JSON. This is the service. |
| `src/main/erl/rooms.erl` | Room name to process, and the owner of the history table. |
| `src/main/erl/room.erl` | One process per room: members, sequence numbers, fan-out. |

## The parts that are not obvious

**The push direction.** `ChatServer` registers a Java lambda as
`chat_sink:deliver/2`. A room process calls it once per member, on its own
scheduler thread, so the lambda must not block: it offers the frame to that
connection's queue and reports `ok`, `full` or `gone`. The SSE thread is the only
place that touches the socket.

**The pool is sized by connection.** An SSE response holds its pool thread for as
long as the client stays connected, so `-Pclients` is a count of concurrent
streams, not of cores. That is the opposite of the key-value example.

**History is a table, not a process.** `rooms.erl` owns the ETS table, so a room
process can die without taking the messages with it. Sequence numbers come from
the room process because `ets:update_counter/3` is not implemented in 1.0.0.

Run time needs no Erlang installation; only `erlc` at build time.
