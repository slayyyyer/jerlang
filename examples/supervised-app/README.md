# Supervision, started from Java

OTP's `application` controller and `supervisor`, unmodified, running on the JVM.
Java starts the application, keeps a Java HTTP service answering, kills a
worker, and the supervisor puts the worker back.

```sh
cd examples/supervised-app
gradle run -PotpLib="/c/Program Files/Erlang OTP/lib"
```

```
java service listening -> HTTP/1.1 200 OK
application:start(demo) -> ok
demo_worker:bump()      -> 1
demo_worker:bump()      -> 2
demo_worker:count()     -> 2
children before crash   -> [{demo_worker,<0.4.0>,worker,[demo_worker]}]
demo_worker:crash()     -> ok
children after crash    -> [{demo_worker,<0.10.0>,worker,[demo_worker]}]
worker restarted        -> true
demo_worker:count()     -> 0   (a fresh process starts at zero)
application:stop(demo)  -> ok
whereis(demo_sup)       -> undefined
java service served     -> N requests, including while the worker was down
java service after stop -> HTTP/1.1 200 OK
```

Needs an Erlang/OTP installation, unlike `http-service`, `chat-service` and
`junit-harness`: the supervision tree is OTP's code, so `kernel` and `stdlib` have
to be on the code path. The JVM supplies the processes, messages and timers; it
does not ship a second copy of the behaviours. This is also the one Gradle
example whose build still uses `erlc` for its own modules.

## Where the OTP root comes from

`-PotpLib=` points at the `lib` directory of an installation. It is not guessed,
because a machine can have several:

```sh
OTP_ROOT="$(erl -noshell -eval 'io:format("~s", [code:root_dir()]), halt().')"
gradle run -PotpLib="$OTP_ROOT/lib"
```

`Supervised.java` passes `Path.of(otpLib).getParent()` as `NodeOptions.otpRoot`;
the node adds `kernel-*/ebin` and `stdlib-*/ebin` from that root. It also passes
the application's own `build/ebin` as the code path, which is where `demo.app`
and the four modules are compiled to.

## Files

| File | What it is |
| --- | --- |
| `src/main/erl/demo.app` | The application resource file. Its presence in `ebin` is what makes `application:start(demo)` work. |
| `src/main/erl/demo_app.erl` | The application callback: `start/2` returns the supervisor's pid. |
| `src/main/erl/demo_sup.erl` | A `one_for_one` supervisor with a map child spec. |
| `src/main/erl/demo_worker.erl` | A `gen_server` holding one integer, with `bump/0`, `count/0` and `crash/0`. |
| `src/main/java/example/Supervised.java` | Starts the Java service and application, crashes the worker, reads the tree, stops it. |
| `src/main/java/example/JavaService.java` | A loopback HTTP service outside the supervision tree; it keeps answering during the restart. |

## What the run proves

- `.app` loading, `applications` dependencies and the application controller.
- A supervisor starting a child, and **restarting it after an abnormal exit** —
  the pid changes from `<0.4.0>` to `<0.10.0>`.
- The new process starts with fresh state (`count()` is 0), which is what
  `restart => permanent` means.
- `application:stop/1` takes the tree down: `whereis(demo_sup)` is `undefined`
  afterwards.
- `node.close()` actually ends the JVM. Schedulers are non-daemon threads, so
  without it the process stays up with nothing to do.
- The Java HTTP service keeps answering while the Erlang worker is down, and
  the final request succeeds after the restart.

## One thing you will see in the output

Killing the worker prints a crash report, and then a second failure while that
report is handled:

```
Error in process <0.4.0> with exit value:
{{badmatch,#{domain => [otp], report_cb => {Fun,gen_server:format_log/2}, ...}},
 [{gen_server,handle_msg,3,...}, ...]}
```

The `put_map_assoc` failure that used to appear here is fixed; `gen_server` now
builds the report. The remaining `badmatch` is in the path that handles the
report, not in the supervisor: the worker is still replaced, the new pid is
visible, and the restarted worker starts at zero. It is left visible rather than
filtered out because a crash report that cannot be handled cleanly is something
an operator should know about.

## Limits

- No hot code upgrade and no release handling; this is `application:start/1`, not
  a boot script.
- `application_master` is not modelled, and a crashed *application* is not
  restarted automatically.
- One node, no distribution: everything here is local.
