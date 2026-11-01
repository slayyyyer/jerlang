#!/usr/bin/env bash
#
# Builds the 1.0 release artifact, checks that the build is reproducible and
# writes a checksum next to it. A detached signature is produced when
# JERLANG_SIGNING_KEY names a GPG secret key; without one the script says the
# artifact is unsigned instead of pretending otherwise.
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

version="$(sed -n 's:^  <version>\(.*\)</version>$:\1:p' "$repo/pom.xml" | head -1)"
jar="$dist/jerlang-$version.jar"

# The two builds are staged outside target/, because each build starts with
# mvn clean and would delete the artifact it is meant to be compared against.
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT

build() {
    mvn -B -q -f "$repo/pom.xml" -DskipTests clean package
    cp "$repo/target/jerlang-$version.jar" "$1"
}

echo "building $version"
build "$stage/first.jar"

# Reproducibility is a claim about two builds of the same source, so it is
# checked rather than assumed: the pom pins project.build.outputTimestamp.
echo "rebuilding to check the build is reproducible"
build "$stage/second.jar"

first="$(sha256sum "$stage/first.jar" | cut -d' ' -f1)"
second="$(sha256sum "$stage/second.jar" | cut -d' ' -f1)"
if [ "$first" != "$second" ]; then
    echo "not reproducible: $first != $second" >&2
    exit 1
fi
echo "reproducible: $second"

mkdir -p "$dist"
cp "$stage/second.jar" "$jar"
(
    cd "$dist"
    sha256sum "jerlang-$version.jar" > "jerlang-$version.jar.sha256"
)

{
    echo "version: $version"
    echo "commit: $(git -C "$repo" rev-parse HEAD)"
    echo "jdk: $(java -version 2>&1 | head -1)"
    echo "sha256: $second"
    echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$dist/BUILD-INFO"

if [ "$sign" = "1" ]; then
    if [ -z "${JERLANG_SIGNING_KEY:-}" ]; then
        echo "JERLANG_SIGNING_KEY is not set; refusing to claim a signature" >&2
        exit 1
    fi
    gpg --batch --yes --local-user "$JERLANG_SIGNING_KEY" \
        --detach-sign --armor --output "$jar.asc" "$jar"
    echo "signed: $jar.asc"
else
    echo "unsigned: no JERLANG_SIGNING_KEY, so no .asc was written"
fi

echo
echo "artifacts in $dist:"
ls -1 "$dist"
