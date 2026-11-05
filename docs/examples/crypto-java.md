# Crypto without NIFs

JErlang does not implement NIFs. OTP's `crypto`, `ssl` and much of `public_key`
are built on the `crypto` NIF, so loading those modules is not the same as
having working crypto. The practical replacement is not a compatibility shim:
it is an explicit call from Erlang to the JVM's security APIs through the
[`java` module](../java-interop.md).

The [`crypto-java` example](../../examples/crypto-java/README.md) shows the
smallest useful version. It compiles this module with JErlang and runs it in a
JVM with no Erlang installation:

```erlang
sha256_hex(Data) ->
    Digest = java:call_static("java.security.MessageDigest", "getInstance",
        ["SHA-256"]),
    try
        hex(java:call(Digest, "digest", [Data]))
    after
        java:release(Digest)
    end.

hmac_sha256_hex(Key, Data) ->
    KeySpec = java:new("javax.crypto.spec.SecretKeySpec",
        [Key, "HmacSHA256"]),
    Mac = java:call_static("javax.crypto.Mac", "getInstance",
        ["HmacSHA256"]),
    try
        java:call(Mac, "init", [KeySpec]),
        hex(java:call(Mac, "doFinal", [Data]))
    after
        java:release(Mac),
        java:release(KeySpec)
    end.
```

`byte[]` crosses the boundary as an Erlang binary, so the existing value mapping
does the work. The example also calls `SecureRandom.generateSeed/1` and turns
the result into hex.

## What can realistically be covered

| Need | Java route | Status |
| --- | --- | --- |
| SHA-1, SHA-256, SHA-384, SHA-512, MD5 | `java.security.MessageDigest` | Works in the example |
| HMAC | `javax.crypto.Mac` + `SecretKeySpec` | Works in the example |
| Secure random bytes | `java.security.SecureRandom` | Works in the example |
| AES, ChaCha20 and other `Cipher` algorithms | `javax.crypto.Cipher`, `SecretKeySpec`, `GCMParameterSpec` | Possible application code; not exercised here |
| PBKDF2 and other key derivation | `SecretKeyFactory` | Possible application code; not exercised here |
| RSA/ECDSA signatures | `java.security.Signature`, `KeyFactory` | Possible, but key and ASN.1 handling is application work |
| X.509 parsing | `CertificateFactory`, `X509Certificate` | Possible as Java objects; not OTP `public_key` terms |
| TLS client/server sockets | `javax.net.ssl.SSLSocket` / `SSLServerSocket` | A separate Java networking path, not OTP `ssl` |

These are real JVM primitives, and a service that only needs a hash or a MAC can
use them without waiting for NIF support. The cost is that the Erlang code is
now coupled to Java class names and exception shapes, and it must release every
object handle it creates.

## What this cannot cover

- **OTP module compatibility.** Code that calls `crypto:hash/2`,
  `crypto:mac/3`, `public_key:verify/5` or `ssl:connect/3` still needs those
  modules, and those modules still need the NIF implementation. This example
  does not make them load.
- **TLS distribution.** JErlang's distribution protocol is plain TCP with the
  cookie handshake. Calling `SSLSocket` in application code does not change
  that.
- **OTP certificate and key semantics.** `public_key` represents keys and
  certificates as OTP records and supports OTP's PEM/DER handling. The Java
  route exposes Java objects instead. Converting between the two is an
  application-level format bridge, not a drop-in replacement.
- **Constant-time and side-channel guarantees.** The JDK primitives make their
  own guarantees; this example does not add a constant-time comparison or audit
  the JVM provider.
- **NIF ABI and precompiled dependencies.** A precompiled NIF `.so`/`.dll` still
  cannot be loaded. Only the operations explicitly wrapped through Java are
  available.

## Run it

```sh
cd examples/crypto-java
gradle test
gradle run
```

Expected output includes:

```text
sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
hmac_sha256("Jefe", message) = 5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843
```

The test is part of CI, so a change to the Java interop conversion path that
breaks binary arguments or byte-array results fails the build. It does not
pretend to be a crypto conformance suite.
