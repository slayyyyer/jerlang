# Mixing OTP and JErlang

The cluster example runs an OTP node and a JErlang node on the same host and
moves an `erpc` call in both directions. It is deliberately a control-plane
example: it proves that the two runtimes can address each other, not that the
link is a fast data path.

```sh
cd examples/mixed-cluster
gradle run
```

The Java process starts EPMD, names its node `mixed_jerlang`, starts a real OTP
child named `mixed_otp`, and registers a Java-backed Erlang function:

```java
node.registerFunction("java_caller", "echo", 1, arguments -> arguments[0]);
```

The three calls are:

1. Java calls `mixed_jerlang:echo/1` locally.
2. Java calls `mixed_jerlang:call_peer/2`, which uses
   `erpc:call(Peer, mixed_peer, echo, [Value])` to reach OTP.
3. OTP uses `erpc:call(LocalNode, java_caller, echo, [<<"from-otp">>])` to reach
   the JVM.

The last call is the important one for the thesis: the remote side is not a
separate Erlang node pretending to be Java. It is a real OTP node calling the
JVM's registered function through the same distribution protocol.

## Limits

- This is a two-node, same-host example. It does not test a remote host, a
  firewall, a rolling upgrade or a cluster with more than one peer.
- It uses `erpc`, not the old `rex`/`rpc:call` wire path. A node that only speaks
  the old path can not call the JVM node.
- There is no reconnect or failover demonstration here; the distribution tests
  cover connection loss. The example is about the call path.
- The OTP child is killed when the Java process exits; this is not a release or
  service-manager recipe.
