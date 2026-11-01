# A chat service over SSE

The [key-value service](http-service.md) is request/reply: one call in, one tuple
out. Chat needs the other direction too — a client that is sitting on an open
connection has to be written to when somebody else speaks. That is the whole
difference, and it is what `Node.registerFunction` is for.

This page covers the design, the parts that had to be worked around, and the
places the example stops short.

## Why SSE and not WebSocket

The listener is `com.sun.net.httpserver`, which is part of the JDK and, like the
rest of it, has no WebSocket support: there is no upgrade path. The choices are a
dependency on a WebSocket server (Netty, Jetty, Undertow) or server-sent events,
which are ordinary chunked HTTP responses. This example uses SSE, so the Java
side stays dependency-free and a browser consumes the stream with
`new EventSource("/events/general")`.

The cost is threads. An open SSE response holds its pool thread for the life of
the connection, so the pool is sized by **concurrent streams**:

```java
http.setExecutor(Executors.newFixedThreadPool(clients));   // -Djerlang.example.clients=64
```

That is the opposite of the [key-value example](http-service.md), where the pool
matches the scheduler count because each thread is inside one short call. Past a
few hundred streams on this server, SSE is the wrong tool and NIO or WebSockets
are the right one.

## The push direction

`ChatServer` registers one Java function that Erlang can call:

```java
node.registerFunction("chat_sink", "deliver", 2, argv -> {
    BlockingQueue<byte[]> out = CLIENTS_BY_ID.get(number(argv[0]));
    if (out == null) {
        return Atom.of("gone");
    }
    return out.offer((byte[]) Bridge.toJava(argv[1])) ? Atom.of("ok") : Atom.of("full");
});
```

A room process calls it once per member:

```erlang
deliver([Conn | Rest], Frame) ->
    case chat_sink:deliver(Conn, Frame) of
        full -> deliver(Rest, Frame);
        _Ok -> [Conn | deliver(Rest, Frame)]
    end.
```

Three properties matter, and each is a consequence of where the call runs:

- **It runs on a scheduler thread** — the room process's own. So the lambda must
  not write to a socket, or a slow client would block a scheduler. It only offers
  to a queue.
- **The return value is backpressure.** A full queue (256 frames) means that
  client is too slow; the room drops it rather than stalling the room. It
  reconnects, which is why the stream starts with `retry: 2000`.
- **The SSE thread does the writing**, blocking on `poll(15, SECONDS)` and
  emitting `: keepalive` on a timeout, so a vanished client is noticed even in a
  silent room.

The room key is a binary from both sides: `join` and `leave` are called as
`join(Room, Conn)`, with the room as a binary term, not as an Erlang string. A
charlist there produces a second room that looks identical in the URL and
receives nothing — which is exactly the bug this example shipped with for one
build.

## The Erlang side

| Module | Job |
| --- | --- |
| `chat.erl` | `handle/4` for the HTTP routes, SSE framing, JSON. |
| `rooms.erl` | Room name to process; owner of the ETS history table. |
| `room.erl` | One process per room: members, sequence numbers, fan-out. |

A room is a single process, so ordering is free — joins, leaves and messages
arrive in one mailbox. `say` allocates the sequence number, writes the history
row, then fans out, all in one clause, so the order clients see matches the order
in the table.

History lives in the table rather than in the room process, which means a room
process can die without taking the messages with it. That matters more here than
in the key-value example: there is no OTP supervisor, so a room that dies is not
restarted by anything.

## A session

```console
$ curl -N -s localhost:8080/events/general
retry: 2000

id: 1
event: message
data: general|justin|hello everyone

```

With two clients on `/events/general` and a third on `/events/other`, posting one
message produced that frame on both `general` subscribers and nothing but
`retry: 2000` on the other one:

```console
$ curl -s -X POST -H 'X-Author: justin' --data-binary 'hello everyone' -o /dev/null -w '%{http_code}\n' localhost:8080/say/general
204
$ curl -s localhost:8080/rooms
[{"room":"other"},{"room":"general"}]
$ curl -s localhost:8080/history/general
[{"seq":1,"author":"justin","text":"hello everyone"}]
$ curl -s localhost:8080/history/other
[]
```

Other routes: `GET /health`, `GET /metrics`, and the `405`/`404` answers for a
known path with the wrong method and an unknown path.

## What this runtime does and does not have

Four things shaped this example, each checked against 1.0.0 by calling it:

| What | Result |
| --- | --- |
| `ets:lookup` with a binary key | **works by content**: an entry stored under one binary is found by an equal copy — unlike `maps:find`, which compares identity |
| `ets:update_counter/3` | `badarg` — not implemented, so sequence numbers live in the room process |
| `erlang:send_after/3` | works; used nowhere yet, but this is how a per-room idle timer would be written |
| `registerFunction` called from a running Erlang process | works: the sink recorded the call from inside the room |

The `maps` identity quirk from the [key-value example](http-service.md) does not
extend to ETS, which is why the history can be keyed by `{Room, Seq}`.

The usual gaps still apply — no `binary:split/2`, no `string` module, no
`iolist_to_binary/1`, and no appending to a binary accumulator in a loop — so
`chat.erl` hand-rolls `sanitize/1` and `escape/1` the same way `api.erl` does.

## Where the example stops

These are deliberate, and the first one is the most likely next step:

- **No resume.** Frames carry `id: <seq>`, but nothing reads `Last-Event-ID` on
  reconnect. A client that was dropped has to fetch `/history/<room>` and
  de-duplicate by sequence. Replaying from the table is a small change: the ids
  are already there.
- **No supervision.** Nothing restarts a dead room process, and the registry
  keeps handing out its pid. A real service wants OTP (or a supervisor written
  by hand) and a registry that notices.
- **Bounded history.** The table grows without limit; `/history` returns the last
  50. Eviction or a time window belongs in `room.erl`.
- **No auth.** `X-Author` is taken at its word, so anyone can post as anyone. The
  [key-value page](http-service.md) has the note about terminating TLS in front.
- **Single node.** Rooms are local pids. Making chat work across nodes is the
  distribution story, not an HTTP story.
- **One writer per room.** Fan-out is sequential inside the room process: correct
  and simple, and the reason a very large room would want a different shape.
