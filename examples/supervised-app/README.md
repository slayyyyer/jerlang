# Supervision, started from Java

OTP's `application` controller and `supervisor`, unmodified, running on the JVM.
Java starts the application, kills a worker, and the supervisor puts it back.

```sh
cd examples/supervised-app
gradle run -PotpLib="/c/Program Files/Erlang OTP/lib"
```

```
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

`Supervised.java` adds `kernel-*/ebin` and `stdlib-*/ebin` from that directory,
then the application's own `build/ebin`, which is where `demo.app` and the four
modules are compiled to.

## Files

| File | What it is |
| --- | --- |
| `src/main/erl/demo.app` | The application resource file. Its presence in `ebin` is what makes `application:start(demo)` work. |
| `src/main/erl/demo_app.erl` | The application callback: `start/2` returns the supervisor's pid. |
| `src/main/erl/demo_sup.erl` | A `one_for_one` supervisor with a map child spec. |
| `src/main/erl/demo_worker.erl` | A `gen_server` holding one integer, with `bump/0`, `count/0` and `crash/0`. |
| `src/main/java/example/Supervised.java` | Starts the application, crashes the worker, reads the tree, stops it. |

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

## One thing you will see in the output

Killing the worker prints a crash report, and that report itself fails:

```
Error in process <0.4.0> with exit value:
{"Not implemented: {put_map_assoc,...}",[{gen_server,error_info,7,...}, ...]}
```

`gen_server` builds its report with a map update the runtime does not implement
yet (`put_map_assoc`). The restart still happens — the supervisor does not need
the report — so this is noise on the way to the correct result, not a broken
example. It is left visible rather than filtered out, because a report that can
not be built is something an operator should know about.

## Limits

- No hot code upgrade and no release handling; this is `application:start/1`, not
  a boot script.
- `application_master` is not modelled, and a crashed *application* is not
  restarted automatically.
- One node, no distribution: everything here is local.
