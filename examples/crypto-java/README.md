# Crypto through Java interop

JErlang has no NIFs, so OTP's `crypto` module does not run. This example takes
the narrower route that does work: an Erlang module calls the JDK's
`java.security.MessageDigest`, `javax.crypto.Mac` and `java.security.SecureRandom`
through `java:call_static/3`, `java:new/2` and `java:call/3`.

```sh
cd examples/crypto-java
gradle test     # known SHA-256 and HMAC-SHA256 vectors
gradle run
```

The build compiles `src/main/erl/crypto_demo.erl` with JErlang, so only a JDK is
required. Reflection is opt-in, which is why the Java runner starts the node with
`NodeOptions.reflection(true)`.

## What the module provides

| Function | Java primitive | Output |
| --- | --- | --- |
| `sha256_hex(Data)` | `MessageDigest("SHA-256")` | lowercase hex |
| `hmac_sha256_hex(Key, Data)` | `Mac("HmacSHA256")` + `SecretKeySpec` | lowercase hex |
| `random_hex(Bytes)` | `SecureRandom.generateSeed/1` | lowercase hex |

The test uses the standard `abc` SHA-256 vector and RFC 4231 test case 2 for
HMAC-SHA256, so it checks the boundary conversion as well as the primitive.

## What this does not cover

This does not make `crypto`, `ssl` or `public_key` load or behave like OTP. It
does not provide OTP's `crypto:hash/2` or `crypto:mac/3` APIs, certificate
verification, TLS, or NIF-compatible key handling. See the
[crypto guide](../../docs/examples/crypto-java.md) for the coverage table and the
limits that remain.
