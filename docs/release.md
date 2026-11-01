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
When the key carries a passphrase, `JERLANG_SIGNING_PASSPHRASE_FILE` points at a
file holding it and the script signs in loopback mode, so it never waits on a
pinentry dialog. Maven's `central` profile reads the same secret from the
`gpg.passphrase` server entry in `~/.m2/settings.xml`, which is why no passphrase
appears on a command line.
Verification is the ordinary GPG flow:

```
gpg --verify jerlang-1.0.0.jar.asc jerlang-1.0.0.jar
sha256sum -c jerlang-1.0.0.jar.sha256
```

`target/dist/BUILD-INFO` records the commit, the JDK and the checksum of the
artifact it describes, so a downloaded jar can be traced to a commit without
trusting the download page.

## Publishing to Maven Central

The pom is prepared for Central — metadata, source and javadoc jars, signatures
and the Portal plugin behind a `central` profile — but **nothing has been
uploaded**, because the three things publishing needs are not in this
repository and should not be.

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
   signatures it stores next to the artifacts.

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

The 1.0 exit test is a conjunction, and each line below is a thing that was
measured on the commit being tagged:

1. **Benchmarks against BEAM on the same machine.**
   `mvn -Dtest=BeamBenchTest -Djerlang.bench=true -Djerlang.bench.gate=true \
   -Djerlang.otp.dir="$OTP" test`. The table goes into `docs/benchmarks.md`; the
   gate enforces the message throughput budget.
2. **Soak at target load.** The long form is
   `mvn -Dtest=SoakTest -Djerlang.soak.seconds=86400 -Djerlang.soak.workers=32 test`.
   **1.0.0 ships without the 24 hour run.** That is a decision, not an oversight:
   a hosted CI job is killed after six hours, so the day-long run needs a machine
   that stays up for a day, and 1.0 was not going to wait on hardware it does not
   have. What was measured on the tagged commit is the same test at 330 seconds
   and 32 workers: 233 million messages delivered, the reported heap holding at
   196 MB for the whole load, the registry draining back to its baseline process
   count, and a final live heap of 4 MB. CI runs a shorter soak every night, and
   the 24 hour run is the exit test of [the 1.1 milestone](roadmap.md#11--the-24-hour-gate)
   rather than a gate this tag met. The residual
   risk is stated rather than dismissed: a leak slow enough to hide behind ten
   minutes of load — under a megabyte a minute — would ship untested.

   The soak fails on a process leak, on descriptor growth where the platform
   reports descriptors, and on heap growth past 256 MB, checked once after the
   drivers exit and compared against a reference taken when the load had settled
   rather than against the empty node. That reference exists because a run at 32
   workers showed the live set reaching about 200 MB within two minutes and then
   staying there while another 40 million messages went through: the runtime's
   stacks and queues grow to a high-water mark, and comparing a day-long run
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
