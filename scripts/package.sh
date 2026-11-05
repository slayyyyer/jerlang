#!/usr/bin/env bash
#
# Builds the 3.0 release artifacts, checks that the build is reproducible and
# writes a checksum next to each one. A detached signature is produced when
# JERLANG_SIGNING_KEY names a GPG secret key; without one the script says the
# artifacts are unsigned instead of pretending otherwise.
#
#   scripts/package.sh                 build, check both builds match, checksum
#   scripts/package.sh --sign          also sign with JERLANG_SIGNING_KEY
#
# Output lands in target/dist/.
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
dist="$repo/target/dist"
sign=0
for arg in "$@"; do
    case "$arg" in
        --sign) sign=1 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

# sed reads the whole file and sed -n '1p' consumes the stream, so nothing
# closes the pipe early. Piping into head did: sed died of SIGPIPE, pipefail
# made that a failed command substitution, and set -e then killed the script
# before its first line of output -- intermittently, on the same source.
version="$(sed -n 's:^  <version>\(.*\)</version>$:\1:p' "$repo/pom.xml" | sed -n '1p')"
jar="$dist/jerlang-$version.jar"
runnable="$dist/jerlang-$version-all.jar"

# The two builds are staged outside target/, because each build starts with
# mvn clean and would delete the artifact it is meant to be compared against.
# /tmp is not always writable: on Git Bash for Windows it maps to
# C:\WINDOWS\tmp, where mktemp fails with "Permission denied" even when TMPDIR
# names a writable directory. The fallback stages inside the repository, which
# is still outside target/, because each build starts with mvn clean.
if ! stage="$(mktemp -d 2>/dev/null)"; then
    stage="$(mktemp -d "$repo/.package-stage.XXXXXX")"
fi
trap 'rm -rf "$stage"' EXIT

build() {
    mvn -B -q -f "$repo/pom.xml" -DskipTests clean package
    cp "$repo/target/jerlang-$version.jar" "$1"
    cp "$repo/target/jerlang-$version-all.jar" "$2"
}

echo "building $version"
build "$stage/first.jar" "$stage/first-all.jar"

# Reproducibility is a claim about two builds of the same source, so it is
# checked rather than assumed: the pom pins project.build.outputTimestamp.
echo "rebuilding to check the build is reproducible"
build "$stage/second.jar" "$stage/second-all.jar"

first="$(sha256sum "$stage/first.jar" | cut -d' ' -f1)"
second="$(sha256sum "$stage/second.jar" | cut -d' ' -f1)"
first_all="$(sha256sum "$stage/first-all.jar" | cut -d' ' -f1)"
second_all="$(sha256sum "$stage/second-all.jar" | cut -d' ' -f1)"
if [ "$first" != "$second" ]; then
    echo "thin jar not reproducible: $first != $second" >&2
    exit 1
fi
if [ "$first_all" != "$second_all" ]; then
    echo "runnable jar not reproducible: $first_all != $second_all" >&2
    exit 1
fi
echo "reproducible thin: $second"
echo "reproducible runnable: $second_all"

mkdir -p "$dist"
cp "$stage/second.jar" "$jar"
cp "$stage/second-all.jar" "$runnable"
(
    cd "$dist"
    sha256sum "jerlang-$version.jar" > "jerlang-$version.jar.sha256"
    sha256sum "jerlang-$version-all.jar" > "jerlang-$version-all.jar.sha256"
)

{
    echo "version: $version"
    echo "commit: $(git -C "$repo" rev-parse HEAD)"
    echo "jdk: $(java -version 2>&1 | head -1)"
    echo "thin-sha256: $second"
    echo "runnable-sha256: $second_all"
    echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$dist/BUILD-INFO"

if [ "$sign" = "1" ]; then
    if [ -z "${JERLANG_SIGNING_KEY:-}" ]; then
        echo "JERLANG_SIGNING_KEY is not set; refusing to claim a signature" >&2
        exit 1
    fi
    # The key carries a passphrase now, and --batch has no way to ask for one,
    # so loopback mode plus a passphrase file is what keeps this unattended.
    # maven-gpg-plugin reads the same passphrase from ~/.m2/settings.xml.
    for artifact in "$jar" "$runnable"; do
        gpg --batch --yes --pinentry-mode loopback \
            ${JERLANG_SIGNING_PASSPHRASE_FILE:+--passphrase-file "$JERLANG_SIGNING_PASSPHRASE_FILE"} \
            --local-user "$JERLANG_SIGNING_KEY" \
            --detach-sign --armor --output "$artifact.asc" "$artifact"
        echo "signed: $artifact.asc"
    done
else
    echo "unsigned: no JERLANG_SIGNING_KEY, so no .asc files were written"
fi

echo
echo "artifacts in $dist:"
ls -1 "$dist"
