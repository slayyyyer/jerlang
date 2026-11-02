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
java -jar jerlang-1.1.0.jar
java -cp jerlang-1.1.0.jar:path/to/beams -Djerlang.otp.dir=/path/to/otp App
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
When the key carries a passphrase, `JERLANG_SIGNING_PASSPHRASE_FILE` points at a
file holding it and the script signs in loopback mode, so it never waits on a
pinentry dialog. Maven's `central` profile reads the same secret from the
`gpg.passphrase` server entry in `~/.m2/settings.xml`, which is why no passphrase
appears on a command line.
Verification is the ordinary GPG flow:

```
gpg --verify jerlang-1.1.0.jar.asc jerlang-1.1.0.jar
sha256sum -c jerlang-1.1.0.jar.sha256
```

`target/dist/BUILD-INFO` records the commit, the JDK and the checksum of the
artifact it describes, so a downloaded jar can be traced to a commit without
trusting the download page.

## Publishing to Maven Central

The pom is prepared for Central — metadata, source and javadoc jars, signatures
and the Portal plugin behind a `central` profile — and **1.0.0, 1.0.1 and 1.1.0 are published**:
`io.github.slayyyyer:jerlang:1.1.0` resolves from Maven Central. What follows is
how 1.0.0 was done, because every version since repeats it.

### One-time prerequisites

1. **A verified namespace — decided, and it is not `org.jerlang`.** The
   coordinates are `io.github.slayyyyer:jerlang`. `org.jerlang` would need a DNS
   record on `jerlang.org` or a record on the `jerlang` GitHub organisation, and
   this fork controls neither, so the name it cannot prove was dropped rather
   than hoped for. `io.github.<username>` is the namespace a GitHub account
   verifies itself: sign in at [central.sonatype.com](https://central.sonatype.com)
   with GitHub, add the namespace whose name matches the account, and it is
   verified immediately because GitHub is the proof of ownership.

   What does **not** change: the artifact id (`jerlang`), the jar names, and
   every Java package (`org.jerlang.*`). A group id is a coordinate, not a
   package prefix — renaming the packages would break every embedder and every
   `import` in this repository for nothing.
2. **A Portal token** in `~/.m2/settings.xml`, as the server id `central`:

   ```xml
   <server>
     <id>central</id>
     <username>your-portal-username</username>
     <password>the-token</password>
   </server>
   ```

   The password is the generated token, not the account password.
3. **A GPG key** whose public half is on a keyserver, so Central can check the
   signatures it stores next to the artifacts. 1.0.0 used `53566F66…`, a
   passphrase-protected key; the note under *Building a release* covers how
   Maven and `scripts/package.sh` get at the secret without a prompt.

### What 1.0.0 published

The namespace `io.github.slayyyyer` was verified through the GitHub sign-in on
2026-11-01, the bundle was uploaded under deployment
`6d1cfff1-3a9f-4609-a2a8-b7400ef8b81a`, Sonatype validated it (`1 out of 1
Components Validated`) and it was released by hand in the Portal. Central now
serves:

```
https://repo1.maven.org/maven2/io/github/slayyyyer/jerlang/1.0.0/jerlang-1.0.0.jar
https://repo1.maven.org/maven2/io/github/slayyyyer/jerlang/1.0.0/jerlang-1.0.0-sources.jar
https://repo1.maven.org/maven2/io/github/slayyyyer/jerlang/1.0.0/jerlang-1.0.0-javadoc.jar
https://repo1.maven.org/maven2/io/github/slayyyyer/jerlang/1.0.0/jerlang-1.0.0.pom
```

The published jar is the artifact `target/dist/BUILD-INFO` describes: its
sha256 is
`e650cbd72a798f7b8a8fb75dcfe51905536a61b3d79a0299486d998b8526cef7`, the same
bytes the reproducible build produced hours earlier, in a different `target/`.
That is the check worth repeating for every release, and it has to be made
against Central rather than against a local copy:

```sh
BASE=https://repo1.maven.org/maven2/io/github/slayyyyer/jerlang/1.0.0
curl -sO $BASE/jerlang-1.0.0.jar
curl -sO $BASE/jerlang-1.0.0.jar.asc
curl -s  $BASE/jerlang-1.0.0.jar.sha256
gpg --verify jerlang-1.0.0.jar.asc jerlang-1.0.0.jar
```

Replication to `repo1.maven.org` lagged the Portal by about seven minutes: a
404 immediately after releasing means the artifact has not propagated yet, not
that the release failed. The Portal's own status is `PUBLISHED` before Central
serves the file, so trust the Portal for "did it release" and Central for "can
anyone download it".

### What 1.0.1 published

1.0.1 is a patch: no public API change, and the one behaviour change is the fix
recorded in [beam-compatibility.md](beam-compatibility.md). The jar Central
serves has sha256
`bade6f5abe4276ebc969e99edb4f2b7f5969eb224f6e249a47a8e67ff4942e70`, which is
both the value Central's own `.sha256` file reports and the value the local
build produced, and `gpg --verify` on the downloaded jar reports a good
signature from `53566F66…`. The jar, `-sources.jar`, `-javadoc.jar` and the pom
each resolve, with a detached signature beside them.

The upload went through the plugin and the release through the Portal API, on
deployment `a4ee8f90-177c-4423-b5d2-42b74376a64f`:

```sh
curl -X POST -H "Authorization: Bearer <token>"   https://central.sonatype.com/api/v1/publisher/deployment/a4ee8f90-177c-4423-b5d2-42b74376a64f
```

The status endpoint reported `PUBLISHED` within about three minutes, and
`repo1.maven.org` served the file at the same time. While the deployment was in
`PUBLISHING`, the status payload carried a `common` error, `Deployment components
info not found`, with an empty `purls` list; the same error was still in the
`PUBLISHED` payload. It did not prevent publication and the artifacts verify, so
read the artifact from Central rather than the error text.

### What 1.1.0 published

1.1.0 is a minor release: the public API remains compatible with 1.0.1, and the
tracing, ETS, loader and distribution changes are additive. The jar Central
serves has sha256
`a11df9f9332accf951c3a874e54380bad8db015e72f0a5c3cabf0ef272439874`, matching
Central's `.sha256` file and the reproducible local build. `gpg --verify`
reports a good signature from `53566F66…`, and the jar, `-sources.jar`,
`-javadoc.jar` and the pom each resolve with a detached signature.

The bundle was uploaded through the plugin and released in the Portal on
2026-11-02, deployment `67c13130-7e15-4a7f-9371-ccead09418db`. Sonatype
validated the component (`1 out of 1 Components Validated`). The release check
was the 1,800-second target-load soak; the 24 hour soak remains deferred.

### The commands

```
mvn -B -Pcentral -DskipTests -Dgpg.skip=true package   # build the bundle, no key
mvn -B -Pcentral -DskipTests deploy                     # upload to the Portal
```

The first form is the one to run to check the bundle: it produces
`jerlang-<version>.jar`, `-sources.jar`, `-javadoc.jar` and the pom. The second
needs the key and the token, and it **uploads rather than publishes**:
`autoPublish` is `false`, so the bundle waits in the Portal until someone has
read the checklist below and releases it by hand.

What gets uploaded is those four files plus a detached `.asc` signature for
each. The main artifact is the shaded, runnable jar, which is deliberate — the
artifact the release notes tell people to run with `java -jar` is the artifact
in the repository — and it has a cost worth stating: the jar's dependencies are
also declared in the pom, so a consumer of the library gets both the bundled
classes and the transitive ones. Same versions, so it resolves and runs; a
consumer that cares can exclude them or use the thin `original-` jar that
`maven-shade-plugin` writes. Attaching the runnable jar under a classifier and
publishing the thin one is the cleaner shape, and it is the change to make if
the bundled classes ever cause trouble.

One more thing had to change for Central: the pom declared two plaintext
`http://` repositories, and a pom that points at one is rejected. Both were
dead; with them gone, every dependency resolves from Central, which the build
check above demonstrates.

## Release checklist

The release exit test is a conjunction, and each line below is a thing that must
be measured on the commit being tagged:

1. **Benchmarks against BEAM on the same machine.**
   `mvn -Dtest=BeamBenchTest -Djerlang.bench=true -Djerlang.bench.gate=true \
   -Djerlang.otp.dir="$OTP" test`. The table goes into `docs/benchmarks.md`; the
   gate enforces the message throughput budget.
2. **Soak at target load.** For 1.1.0, run the same test at the 1,800-second
   duration CI uses nightly:
   `mvn -Dtest=SoakTest -Djerlang.soak.seconds=1800 -Djerlang.soak.workers=32 test`.
   **The 24 hour run is not part of the 1.1.0 gate.** That is a decision, not an
   oversight: a hosted CI job is killed after six hours, so the day-long run
   needs a machine that stays up for a day. 1.0.0 also shipped without it. What
   was measured on the tagged commit was the same test at 330 seconds and 32
   workers: 233 million messages delivered, the reported heap holding at 196 MB
   for the whole load, the registry draining back to its baseline process count,
   and a final live heap of 4 MB. The 24 hour run is deferred, not a gate for
   1.1.0. The shorter run still leaves a residual risk, which is stated rather
   than dismissed: a leak slow enough to hide behind the run — under a megabyte
   a minute — would ship untested.

   The soak fails on a process leak, on descriptor growth where the platform
   reports descriptors, and on heap growth past 256 MB, checked once after the
   drivers exit and compared against a reference taken when the load had settled
   rather than against the empty node. That reference exists because a run at 32
   workers showed the live set reaching about 200 MB within two minutes and then
   staying there while another 40 million messages went through: the runtime's
   stacks and queues grow to a high-water mark, and comparing a long run
   against startup would have measured that warm-up rather than a leak. The heap
   is not asserted while the load runs, because there the number is not
   trustworthy: `System.gc()` returns without a full collection when one is
   already in progress, so one run reported 196 MB and 320 MB over the reference
   seconds apart while an external class histogram put the live set at a flat
   172 MB. A leak that survives the load is still caught, which is the leak a
   release has to care about.
3. **Fuzzing campaign.**
   `mvn -Dtest='*FuzzTest' -Djerlang.fuzz.iterations=20000 test`. The checked-in
   run is 200 iterations per target on every push; the campaign is the long run.
4. **Full suite green**, including the OTP-backed and conformance jobs.
5. **Artifact built, reproducible and installable from a clean machine**: unpack
   the jar on a machine with only a JDK, run one of the checked-in examples from
   `examples/`, and compare checksums against `BUILD-INFO`.
6. **Tag.** `git tag -s 1.0.0 -m "JErlang 1.0.0"` on the commit that passed 1–5,
   then push the tag with the release notes. Name the key with `-u` or set
   `user.signingkey`: left to itself git guesses from the committer identity,
   and when two keys share that address it guesses the wrong one. A protected
   key also needs the passphrase cached in `gpg-agent` or a `gpg.program`
   wrapper that signs in loopback mode, or the command waits on a pinentry
   dialog a headless run never shows.
7. **Publish** (optional, and separate from the tag): `mvn -B -Pcentral
   -DskipTests deploy`, then release the bundle in the Portal. A deployment that
   is not released sits there and becomes nothing, so the tag does not depend on
   this step.

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
* **Shutdown, found by the soak:** `Node.close()` could hang forever when
  processes were still running. Schedulers ended on their interrupt status, and
  an interrupt that arrived inside a blocking mailbox send had already been
  cleared by the time the loop checked, so the thread never stopped and the
  shutdown join waited on it. Schedulers now stop on an explicit request, and
  the join is bounded at ten seconds with a message naming a scheduler that
  outlives it.

## Known limitations at 1.0

These are decisions, not open bugs, and they are repeated from
[roadmap.md](roadmap.md) so a release note can not quietly drop them:

* No NIFs: `crypto`, `ssl` and much of `public_key` do not run as shipped.
* No hot code upgrade and no OTP release handling.
* No `global` name registration, no TLS distribution, no atom cache, and no
  `rex` compatibility layer.
* The interpreter is not compiled; performance is reported against BEAM in
  [benchmarks.md](benchmarks.md) rather than claimed.
