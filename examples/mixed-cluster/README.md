# A mixed OTP and JErlang cluster

Two nodes on one host, speaking Erlang distribution:

* `mixed_jerlang` is the JVM node started by `MixedCluster.java`.
* `mixed_otp` is a real OTP node started as a child process.

The Java caller makes a local Erlang call, then calls the OTP peer through
`erpc:call/4`. The OTP peer calls back into the JVM through `erpc:call/4` to a
function registered with `Node.registerFunction`. Both directions use the
SPAWN_REQUEST/monitor protocol; no `rex` process is involved.

```sh
cd examples/mixed-cluster
gradle run
```

Expected shape:

```text
OTP peer ready -> READY mixed_otp@host
Java -> JErlang -> "from-java"
Java -> JErlang -> erpc -> OTP -> "from-jerlang"
OTP_TO_JERLANG "from-otp"
```

Needs `erl`, `erlc` and `epmd` on `PATH`, or `ERL`/`ERLC`/`EPMD` pointing at them.
The Java process starts EPMD, names the JVM node `mixed_jerlang`, and gives the
OTP child the same cookie. The peer is compiled by `erlc`; `mixed_jerlang.erl`
is compiled by JErlang.

## What is and is not covered

This is a control-plane call, not a throughput claim. The `erpc` path is the
same one covered by `OtpInteropTest`; this example adds the two-node Java
startup and a visible callback. It does not implement OTP's `rex` protocol,
and a node using the old `rpc:call` transport can not call the JVM node.
