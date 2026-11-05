#!/usr/bin/env bash
#
# Checks the two embedding quickstarts as a consumer would run them: the thin
# artifact is installed locally, then Maven and Gradle compile the .erl source
# with JErlang and test it from Java. ERL and ERLC point at paths that do not
# exist, so a fallback to an Erlang toolchain fails instead of passing on a
# machine that happens to have OTP installed.
#
#   scripts/check-embedding.sh
#
# Requires a JDK 21+, Maven and Gradle on PATH (or MVN/GRADLE set to their
# executable paths). The Gradle example uses mavenLocal only for this local
# release candidate; a published 3.0.0 resolves from Central.
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
mvn="${MVN:-mvn}"
gradle="${GRADLE:-gradle}"
stage="$(mktemp -d "${TMPDIR:-/tmp}/jerlang-embed.XXXXXX")"
trap 'rm -rf "$stage"' EXIT

for tool in "$mvn" "$gradle" java; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "missing required command: $tool" >&2
        exit 2
    fi
done

# Deliberately broken paths: the consumers must compile Erlang with JErlang,
# never with erlc. This is the same guard the CI jobs use.
export ERL="$stage/missing-erl"
export ERLC="$stage/missing-erlc"

echo "installing the thin artifact"
"$mvn" -B -q -f "$repo/pom.xml" -DskipTests install

echo "maven-embed: building and testing without Erlang"
(
    cd "$repo/examples/maven-embed"
    "$mvn" -B clean verify
)

# 3.0.0 is not published while it is a release candidate. The Gradle example
# declares Central, so add the local repository for this gate only.
cat > "$stage/jerlang-local.init.gradle" <<'EOF'
allprojects {
    repositories {
        mavenLocal {
            content {
                includeGroup 'io.github.slayyyyer'
            }
        }
    }
}
EOF

echo "gradle-embed: building and testing without Erlang"
(
    cd "$repo/examples/gradle-embed"
    "$gradle" --no-daemon --console=plain \
        --init-script "$stage/jerlang-local.init.gradle" clean test
)

echo "embedding consumer checks passed"
