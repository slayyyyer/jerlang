# Releasing JErlang

This is the document 1.0 ships with: what a version number means, what is
supported, how to build and verify an artifact, and what the release checklist
is. It is written to be usable by someone who has never cut a release here.

## What the artifact is

`target/dist/jerlang-<version>.jar` is a shaded jar: the runtime, its
dependencies (`jzlib`, `jnr-posix`, `jline`, `commons-math3`) and a `Main-Class`
of `org.jerlang.erts.Emulator`. Running it with no arguments starts the shell,
so the artifact is usable on its own:

```
java -jar jerlang-1.0.0.jar
java -cp jerlang-1.0.0.jar:path/to/beams -Djerlang.otp.dir=/path/to/otp App
```

Requirements: Java 21 or newer. Erlang/OTP is only needed to run OTP's own
modules (the standard library, the `otp` test suite) or to talk to an OTP node;
a JVM-only deployment that uses the built-in modules does not need it.

## Version numbers

[Semantic versioning](https://semver.org): `MAJOR.MINOR.PATCH`.

* **PATCH** (1.0.x) — bug fixes only. No public API change, no behavioural
  change to a documented function.
* **MINOR** (1.x.0) — new functionality, no removal and no incompatible change.
* **MAJOR** (2.0.0) — anything that breaks the API or changes the meaning of an
  existing call.

The public API is the `org.jerlang.*` surface compared by `japicmp` against the
`src/api-baseline` jar during `mvn verify`. A change that breaks binary or
source compatibility fails the build, and the only way past it is a major
version bump, which is what `breakBuildBasedOnSemanticVersioning` enforces. The
gate is a build check, not a review habit.

## Support policy

* The 1.x line is supported. The most recent minor release gets fixes; the one
  before it gets security fixes for twelve months after its successor ships.
* Supported platforms for 1.0: Linux and Windows on Temurin 21 and newer. CI
  runs the suite on Linux; the maintainers run it on Windows.
* Supported OTP for interoperability and the standard library: OTP 29. The
  conformance rate is recorded against 29.1.2, and the `otp` job installs OTP 29.
* Anything on the roadmap's non-goals list — NIF-based applications, hot code
  upgrade, OTP release handling, `global` locks — is not supported and its
  absence is not a bug report.

## Building a release

```
scripts/package.sh            # build, check reproducibility, checksum
scripts/package.sh --sign     # the same, plus a detached GPG signature
```

The script builds the artifact twice and fails if the two checksums differ, so
"reproducible" is checked rather than asserted. This works because the pom pins
`project.build.outputTimestamp` instead of embedding the wall clock.

Signing needs a GPG secret key and `JERLANG_SIGNING_KEY` naming it. Without a
key the script says the artifact is unsigned and exits successfully; with
`--sign` and no key it fails rather than produce a file that looks signed.
Verification is the ordinary GPG flow:

```
gpg --verify jerlang-1.0.0.jar.asc jerlang-1.0.0.jar
sha256sum -c jerlang-1.0.0.jar.sha256
```

`target/dist/BUILD-INFO` records the commit, the JDK and the checksum of the
artifact it describes, so a downloaded jar can be traced to a commit without
trusting the download page.

## Release checklist

The 1.0 exit test is a conjunction, and each line below is a thing that was
measured on the commit being tagged:

1. **Benchmarks against BEAM on the same machine.**
   `mvn -Dtest=BeamBenchTest -Djerlang.bench=true -Djerlang.bench.gate=true \
   -Djerlang.otp.dir="$OTP" test`. The table goes into `docs/benchmarks.md`; the
   gate enforces the message throughput budget.
2. **Soak, 24 hours at target load.**
   `mvn -Dtest=SoakTest -Djerlang.soak.seconds=86400 -Djerlang.soak.workers=32 test`.
   This can not run in GitHub Actions: a hosted job is killed after six hours, so
   the 24 hour run happens on a machine that stays up, and CI runs a shorter soak
   every night. The soak fails on a process leak, on descriptor growth where the
   platform reports descriptors, and on heap growth past 64 MB after collection.
3. **Fuzzing campaign.**
   `mvn -Dtest='*FuzzTest' -Djerlang.fuzz.iterations=20000 test`. The checked-in
   run is 200 iterations per target on every push; the campaign is the long run.
4. **Full suite green**, including the OTP-backed and conformance jobs.
5. **Artifact built, reproducible and installable from a clean machine**: unpack
   the jar on a machine with only a JDK, run one of the checked-in examples from
   `examples/`, and compare checksums against `BUILD-INFO`.
6. **Tag.** `git tag -s 1.0.0 -m "JErlang 1.0.0"` on the commit that passed 1–5,
   then push the tag with the release notes.

Steps 1–3 are commands in this repository, not prose: a release that can not
re-run them is not a release.

## Upgrade notes: 0.9 to 1.0

* **Added** `Metrics.messagesDelivered()` and `Metrics.messagesPerSecond()`, and
  the `jerlang_messages_delivered` / `jerlang_messages_per_second` lines in the
  text format. Both are documented in [operations.md](operations.md). Existing
  fields are unchanged.
* **Added** `Node.metrics()` fields only; nothing was removed. The `japicmp` gate
  confirms the public surface is a superset of 0.3.0 and therefore of 0.9.
* **Behaviour:** the delivered-message counter resets when the VM starts, so it
  reports one run rather than the process lifetime. The average rate is over the
  current run's uptime.
* **Hardening, from the 1.0 fuzzing work:**
  * the external term format reader refuses a length field larger than the
    buffer it was given (or 64 MiB on a stream) instead of allocating it, so a
    short malformed message can no longer exhaust the heap;
  * a compressed term is refused when it decompresses past the size it declares;
  * the BEAM loader refuses a module whose export table did not parse, instead
    of returning a module where every call is `{undef}`.
* **Packaging:** the version is now `1.0.0`, the build is reproducible, and
  `scripts/package.sh` is the supported way to produce a release artifact.

## Known limitations at 1.0

These are decisions, not open bugs, and they are repeated from
[roadmap.md](roadmap.md) so a release note can not quietly drop them:

* No NIFs: `crypto`, `ssl` and much of `public_key` do not run as shipped.
* No hot code upgrade and no OTP release handling.
* No `global` name registration, no TLS distribution, no atom cache, and no
  `rex` compatibility layer.
* The interpreter is not compiled; performance is reported against BEAM in
  [benchmarks.md](benchmarks.md) rather than claimed.
