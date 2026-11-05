# Releasing JErlang

> **Evidence, not a product release promise.** This page records how the
> prototype artifact is built, signed and made reproducible. The 3.0.0 artifact
> is a research snapshot, not a long-term support commitment.

This is the release guide for JErlang 3.0.0: what a version number means, what
was tested, how to build and verify an artifact, and what the release checklist
is. It is written to be usable by someone who has never cut a release here.

## What the artifacts are

`target/dist/jerlang-<version>.jar` is the thin library artifact. It contains
the runtime and this project's classes, and declares `jzlib`, `jnr-posix`,
`jline` and `commons-math3` as normal Maven dependencies. Maven and Gradle
resolve this artifact; it is the one to embed.

`target/dist/jerlang-<version>-all.jar` is the runnable artifact, attached under
the `all` classifier. It bundles those dependencies and has a `Main-Class` of
`org.jerlang.erts.Emulator`. Running it with no arguments starts the shell, so
it is the artifact for a one-file workflow or for running without a dependency
manager:

```
java -jar jerlang-3.0.0-all.jar
java -cp jerlang-3.0.0-all.jar:path/to/beams -Djerlang.otp.dir=/path/to/otp App
```

Running `java -jar` on the thin jar does not work: it has no `Main-Class`, and
the dependencies are supplied by the consumer's dependency manager rather than
being copied into it.

Requirements: Java 21 or newer. Erlang/OTP is only needed to run OTP's own
modules (the standard library, the `otp` test suite) or to talk to an OTP node;
a JVM-only deployment that uses the built-in modules does not need it.

## Version numbers

[Semantic versioning](https://semver.org): `MAJOR.MINOR.PATCH`.

* **PATCH** (3.0.x) — bug fixes only. No public API change, no behavioural
  change to a documented function.
* **MINOR** (3.x.0) — new functionality, no removal and no incompatible change.
* **MAJOR** (4.0.0) — anything that breaks the API or changes the meaning of an
  existing call.

The public API is the `org.jerlang.*` surface compared by `japicmp` against the
`src/api-baseline` jar during `mvn verify`. A change that breaks binary or
source compatibility fails the build, and the only way past it is a major
version bump, which is what `breakBuildBasedOnSemanticVersioning` enforces. The
gate is a build check, not a review habit.

## Compatibility policy

These are the boundaries of the prototype, not an SLA or a support contract.

* The 3.x line is the current research line. Fixes are made when they support
  the demonstrated embedding and failure-model claims; there is no guaranteed
  support window.
* Tested platforms for 3.0: Linux and Windows on Temurin 21 and newer. CI runs
  the suite on Linux; the maintainers run it on Windows.
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

The script builds both artifacts twice and fails if either pair of checksums
differs, so "reproducible" is checked rather than asserted. This works because
the pom pins `project.build.outputTimestamp` instead of embedding the wall
clock.

Signing needs a GPG secret key and `JERLANG_SIGNING_KEY` naming it. Without a
key the script says the artifacts are unsigned and exits successfully; with
`--sign` and no key it fails rather than produce files that look signed.
When the key carries a passphrase, `JERLANG_SIGNING_PASSPHRASE_FILE` points at a
file holding it and the script signs in loopback mode, so it never waits on a
pinentry dialog. Maven's `central` profile reads the same secret from the
`gpg.passphrase` server entry in `~/.m2/settings.xml`, which is why no passphrase
appears on a command line.
Verification is the ordinary GPG flow:

```
gpg --verify jerlang-3.0.0.jar.asc jerlang-3.0.0.jar
gpg --verify jerlang-3.0.0-all.jar.asc jerlang-3.0.0-all.jar
sha256sum -c jerlang-3.0.0.jar.sha256
sha256sum -c jerlang-3.0.0-all.jar.sha256
```

`target/dist/BUILD-INFO` records the commit, the JDK and both checksums, so a
downloaded jar can be traced to a commit without trusting the download page.

## Publishing to Maven Central

The pom is prepared for Central — metadata, source and javadoc jars, signatures
and the Portal plugin behind a `central` profile — and **1.0.0, 1.0.1, 1.1.0, 2.0.0 and 3.0.0 are published**:
`io.github.slayyyyer:jerlang:3.0.0` resolves from Maven Central. The 3.0.0
artifact shape is described below. The earlier sections record how 1.0.0 was
done, because every version since repeats it.

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

### What 2.0.0 published

2.0.0 is the compiler release. The jar, `-sources.jar`, `-javadoc.jar` and the
pom are on Central with detached signatures. Central's `.sha256` file and the
downloaded jar both hash to
`85a1e37b7e3726e363046a572ae4bcc7cf2e375b07ae60ef330568b1c7ded392`, matching the
reproducible local build; `gpg --verify` reports a good signature from
`53566F66…`. The signed tag is `2.0.0`, on commit `de20a56`.

The bundle was uploaded as deployment
`dff2f083-18e2-486e-95d3-4f526d68760c` and released in the Portal on
2026-11-04. The release check was the 1,800-second target-load soak; the 24 hour
soak remains deferred and is not part of the 2.0.0 gate.

### What 3.0.0 publishes

3.0.0 is a packaging break. The main artifact is the thin library jar; the
runnable jar is attached with the `all` classifier. Both are uploaded and signed
alongside the `-sources.jar`, `-javadoc.jar` and the pom. Maven and Gradle users
keep the same coordinates and dependency declaration. Users who ran
`java -jar jerlang-2.0.0.jar` must switch to `jerlang-3.0.0-all.jar`; that is
the only artifact with a `Main-Class`.

The bundle was uploaded as deployment
`7fcae535-dfe2-4e06-9580-3199f76931ca` and released on 2026-11-05. The thin jar
Central serves is the reproducible artifact with sha256
`b030be40599f5d352e97e51e751cfac11cd6f276eb7b32c36005aafaf2a4e973`; the
runnable `all` jar is
`940ece5a7bab0b83faea7c251dfd368fec2529817f698c419c9f3613cd8869e5`. The same
hashes are recorded in `target/dist/BUILD-INFO` by `scripts/package.sh`.

### The commands

```
mvn -B -Pcentral -DskipTests -Dgpg.skip=true package   # build the bundle, no key
mvn -B -Pcentral -DskipTests deploy                     # upload to the Portal
```

The first form is the one to run to check the bundle: it produces
`jerlang-<version>.jar`, `jerlang-<version>-all.jar`, `-sources.jar`,
`-javadoc.jar` and the pom. The second needs the key and the token, and it
**uploads rather than publishes**: `autoPublish` is `false`, so the bundle waits
in the Portal until someone has read the checklist below and releases it by
hand.

What gets uploaded is those five files plus a detached `.asc` signature for
each. The main artifact is the thin library jar, which is deliberate: a
dependency manager should receive one copy of each library, declared by the pom.
The attached `all` jar keeps the one-file workflow and carries the bundled
classes and `Main-Class`. The 2.0.0 release did the opposite — the main artifact
was the bundled jar and its dependencies were also declared — so 3.0.0 is a
major version for packaging, not only for API.

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
2. **Soak at target load.** For 3.0.0, run the same test at the 1,800-second
   duration CI uses nightly:
   `mvn -Dtest=SoakTest -Djerlang.soak.seconds=1800 -Djerlang.soak.workers=32 test`.
   **The 24 hour run is not part of the 3.0.0 gate.** That is a decision, not an
   oversight: a hosted CI job is killed after six hours, so the day-long run
   needs a machine that stays up for a day. Earlier releases also shipped
   without it. What was measured on the 2.0.0 tagged commit was the same test at
   1,800 seconds and 32 workers: 936,074,881 messages delivered, 64 processes
   at peak, a peak heap of 2,630 MB against a 390 MB reference, and a final live
   heap of 4 MB. The 3.0.0 candidate run on 2026-11-05 recorded 993 samples,
   946,325,766 messages delivered, 64 processes at peak, a peak heap of
   2,630 MB against a 551 MB reference, and a final live heap of 4 MB;
   descriptor growth was not observable on Windows 11. `SoakTest` passed in
   1,801 seconds. The 24 hour run is deferred, not a gate for 3.0.0. The shorter
   run still leaves a residual risk, which is stated rather than dismissed: a
   leak slow enough to hide behind the run — under a megabyte a minute — would
   ship untested.

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
5. **Embedding consumer gates.** From a clean checkout, run
   `scripts/check-embedding.sh`. It installs the thin artifact, then builds and
   tests both the Maven and Gradle quickstarts with `ERL` and `ERLC` pointed at
   paths that do not exist. It must finish with `embedding consumer checks
   passed`; each consumer runs its own two tests.
6. **Artifacts built, reproducible and installable from a clean machine**: run
   the `all` jar on a machine with only a JDK, run one of the checked-in
   examples against the thin artifact, check that its dependency tree contains
   one JErlang jar and no bundled dependency classes, and compare both checksums
   against `BUILD-INFO`.
7. **Tag.** `git tag -s 3.0.0 -m "JErlang 3.0.0"` on the commit that passed 1–6,
   then push the tag with the release notes. Name the key with `-u` or set
   `user.signingkey`: left to itself git guesses from the committer identity,
   and when two keys share that address it guesses the wrong one. A protected
   key also needs the passphrase cached in `gpg-agent` or a `gpg.program`
   wrapper that signs in loopback mode, or the command waits on a pinentry
   dialog a headless run never shows.
8. **Publish** (separate from the tag; required for a public release): `mvn
   -B -Pcentral -DskipTests deploy`, then release the bundle in the Portal. A
   deployment that is not released sits there and becomes nothing, so the tag
   does not depend on this step.

Steps 1–5 are commands in this repository, not prose: a release that can not
re-run them is not a release.

## Upgrade notes: 2.0 to 3.0

* **Packaging:** the normal `io.github.slayyyyer:jerlang` artifact is now the
  thin library jar. Maven and Gradle consumers keep the same coordinates and no
  longer receive bundled copies of `jzlib`, `jnr-posix`, `jline` or
  `commons-math3`; the pom declares them once, as normal transitive
  dependencies. Remove workarounds that excluded or relocated those classes for
  JErlang.
* **`java -jar`:** the main jar no longer has a `Main-Class`. Use
  `jerlang-3.0.0-all.jar` (the `all` classifier) for the shell, the `compile`
  command or any other one-file launch. A dependency-managed application uses
  the thin jar and its normal classpath.
* **Lifecycle:** `NodeOptions.codePath(...)` and `NodeOptions.otpRoot(...)`
  replace the example-only `CodePaths.reset()`/`ModuleRegistry.reset()` setup.
  A process runs one active node at a time; a failed start rolls back cleanly,
  `Node.close()` is idempotent, and `Module.apply` has `Duration` overloads that
  kill a timed-out process. See [embedding.md](embedding.md) and the
  [2.0-to-3.0 migration guide](migration-2-to-3.md).
* **Runtime boundary:** Java 21 or newer is required. OTP 29 is needed only for
  OTP's own modules or an OTP peer; a module that uses JErlang's built-in
  modules needs no Erlang installation. NIF-backed `crypto`, `ssl` and
  `public_key` remain unsupported.
* **Version:** 3.0.0 is a major release because the packaging change is visible
  to users even though the Java API remains source-compatible. The 2.0.0
  artifacts remain available for existing builds.

## Upgrade notes: 1.1 to 2.0

* The compiler is now public API: `org.jerlang.compiler` and the runnable jar's
  `compile` subcommand. Existing embedding entry points in `org.jerlang.api`
  remain; `mvn verify` enforces the public API gate.
* 2.0.0 targets OTP 29 BEAM files on both sides: it loads OTP 29 output and
  emits BEAM that OTP 29 can load.
* No NIF, hot-upgrade, or OTP release-handling support was added; the 1.x
  limitations remain and are listed below.

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

## Known limitations at 3.0

These are decisions, not open bugs, and they are repeated from
[roadmap.md](roadmap.md) so a release note can not quietly drop them:

* No NIFs: `crypto`, `ssl` and much of `public_key` do not run as shipped.
* No hot code upgrade and no OTP release handling.
* No `global` name registration, no TLS distribution, no atom cache, and no
  `rex` compatibility layer.
* `gen_statem` and the wider standard library are not covered.
* Native-record values (OTP 29's `-record #name{...}` data type) are not
  implemented; ordinary tuple records are.
* The interpreter is not compiled; performance is reported against BEAM in
  [benchmarks.md](benchmarks.md) rather than claimed.
