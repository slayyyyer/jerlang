# Supervision from Java

The other examples use one process or a few. This one hands the JVM an OTP
**application**: a supervisor, a worker, a `.app` file, and the crash-and-restart
behaviour that makes Erlang worth running in the first place.

```sh
cd examples/supervised-app
OTP_ROOT="$(erl -noshell -eval 'io:format("~s", [code:root_dir()]), halt().')"
gradle run -PotpLib="$OTP_ROOT/lib"
```

```console
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

The two `children` lines are the point: the pid changed, so the supervisor
replaced the process, and `count()` is `0` because a restarted worker starts with
fresh state.

## What is OTP's code and what is the runtime's

Everything in `src/main/erl` except the modules you write is OTP's:
`application` controller, `supervisor`, `gen_server`, `proc_lib` and `logger` all
come from the Erlang installation on the code path. The JVM provides the
processes, mailboxes, links, monitors and timers they are built from.

That is why this example, unlike
[`http-service`](http-service.md), [`chat-service`](chat-service.md) and
[`junit-harness`](junit-harness.md), needs an Erlang installation at run time:

```java
for (Path directory : otpEbins(otpLib)) {          // kernel-*/ebin, stdlib-*/ebin
    CodePaths.addPatha(CodePaths.normalizePath(directory.toString()));
}
CodePaths.addPatha(CodePaths.normalizePath(Path.of(ebin).toAbsolutePath().toString()));
```

This is also the only Gradle example whose own modules are still compiled with
`erlc`; the others use JErlang and need no Erlang installation for their build.
The `lib` directory is an argument (`-PotpLib=`), not something the code guesses:
a machine can have more than one installation, and picking the wrong one is a
confusing failure.

## The tree

```erlang
init([]) ->
    Worker = #{id => demo_worker,
               start => {demo_worker, start_link, []},
               restart => permanent,
               shutdown => 5000,
               type => worker,
               modules => [demo_worker]},
    {ok, {#{strategy => one_for_one, intensity => 3, period => 10}, [Worker]}}.
```

Map child specs, `one_for_one`, and the intensity limits are parsed by OTP's own
supervisor code — so this doubles as a check that map literals and matches work
well enough for OTP to read them.

The worker is an ordinary `gen_server`:

```erlang
handle_cast(crash, _Count) ->
    exit(simulated_crash);
```

An abnormal exit is what a supervisor restarts on; `exit(normal)` would be
ignored for a `permanent` child only in the sense that the child is restarted
anyway — the example uses a crash so the behaviour is unambiguous.

## One thing that fails on the way, and is left visible

Killing the worker produces a crash report, and building that report fails:

```
Error in process <0.4.0> with exit value:
{"Not implemented: {put_map_assoc,...}", [{gen_server,error_info,7,...}, ...]}
```

`gen_server` assembles the report with a map update the runtime does not
implement yet. The restart still happens, so the example reaches the right answer,
but the report is lost. It is left in the output rather than filtered out: a
runtime that can not build a crash report is something to know about before
running it in production, and this page would be lying if it showed a clean log.

## Shutting down

```java
node.close();   // schedulers are non-daemon threads
```

Without it the JVM stays alive after `main` returns, with every scheduler parked
and nothing to do. The shutdown hook is registered as well, so a Ctrl-C is also
clean, and closing twice is harmless.

## Limits

- No hot code upgrade, no release handling, no boot scripts: this is
  `application:start/1` on a running node.
- `application_master` is not modelled, and a crashed application is not
  restarted for you.
- Single node. Nothing here uses distribution; see
  [`mnesia-service`](mnesia-service.md) for that direction.
