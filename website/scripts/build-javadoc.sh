#!/usr/bin/env bash
set -euo pipefail

readonly MODULES=(nexora-core nexora-plugin-spi nexora-api)
readonly JAVA_RELEASE=21
readonly JDK_API_URL="https://docs.oracle.com/en/java/javase/${JAVA_RELEASE}/docs/api/"
readonly CLASSPATH_FILE="target/javadoc-classpath.txt"

log() { printf 'build-javadoc: %s\n' "$*" >&2; }
fail() { log "error: $*"; exit 1; }

[[ $# -ge 2 && $# -le 3 ]] || fail "usage: build-javadoc.sh <ref> <output-dir> [version-label]"
readonly REF="$1"
readonly OUTPUT_DIR="$2"
readonly VERSION_LABEL="${3:-${REF#v}}"

command -v git >/dev/null || fail "git is not installed"
command -v mvn >/dev/null || fail "mvn is not installed"
command -v javadoc >/dev/null || fail "javadoc is not installed (a JDK ${JAVA_RELEASE}+ is required)"

REPO_ROOT="$(git rev-parse --show-toplevel)" || fail "not inside a git repository"
readonly REPO_ROOT
WORK_DIR="$(mktemp -d)"
readonly WORK_DIR
SOURCE_DIR="$REPO_ROOT"

cleanup() {
    if [[ "$SOURCE_DIR" != "$REPO_ROOT" ]]; then
        git -C "$REPO_ROOT" worktree remove --force "$SOURCE_DIR" >/dev/null 2>&1 || true
    fi
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

if [[ "$REF" != "HEAD" ]]; then
    git -C "$REPO_ROOT" rev-parse --verify --quiet "${REF}^{commit}" >/dev/null || fail "unknown git ref: $REF"
    SOURCE_DIR="$WORK_DIR/source"
    log "checking out $REF"
    git -C "$REPO_ROOT" worktree add --detach --quiet "$SOURCE_DIR" "$REF"
fi

for module in "${MODULES[@]}"; do
    [[ -d "$SOURCE_DIR/$module/src/main/java" ]] || fail "$REF has no $module sources"
done

log "resolving the classpath of $REF"
module_list="$(IFS=,; echo "${MODULES[*]}")"
mvn -B -ntp -q -f "$SOURCE_DIR/pom.xml" -pl "$module_list" -am \
    -DskipTests -DskipITs -Dspotless.check.skip=true -Dnexora.javadoc.lint.skip=true \
    compile dependency:build-classpath -Dmdep.outputFile="$CLASSPATH_FILE" -Dmdep.includeScope=compile

classpath=""
sourcepath=""
for module in "${MODULES[@]}"; do
    module_dir="$SOURCE_DIR/$module"
    [[ -f "$module_dir/$CLASSPATH_FILE" ]] || fail "no classpath was written for $module"
    classpath+="$(cat "$module_dir/$CLASSPATH_FILE"):$module_dir/target/classes:"
    sourcepath+="$module_dir/src/main/java:"
done

sources_file="$WORK_DIR/sources.txt"
for module in "${MODULES[@]}"; do
    find "$SOURCE_DIR/$module/src/main/java" -name '*.java' -print
done | sort >"$sources_file"
[[ -s "$sources_file" ]] || fail "no Java sources found for $REF"

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
log "generating JavaDoc for $(wc -l <"$sources_file" | tr -d ' ') source files into $OUTPUT_DIR"
javadoc \
    --release "$JAVA_RELEASE" \
    -d "$OUTPUT_DIR" \
    -classpath "$classpath" \
    -sourcepath "$sourcepath" \
    -encoding UTF-8 -docencoding UTF-8 -charset UTF-8 \
    -Xdoclint:none \
    -quiet \
    -notimestamp \
    -link "$JDK_API_URL" \
    -doctitle "Nexora ${VERSION_LABEL} API" \
    -windowtitle "Nexora ${VERSION_LABEL} API" \
    -header "Nexora ${VERSION_LABEL}" \
    "@$sources_file"

[[ -f "$OUTPUT_DIR/index.html" ]] || fail "javadoc wrote no index.html"
for module in "${MODULES[@]}"; do
    first_source="$(grep -m1 "/$module/src/main/java/" "$sources_file")"
    relative="${first_source#"$SOURCE_DIR/$module/src/main/java/"}"
    page="$OUTPUT_DIR/${relative%.java}.html"
    [[ "$relative" == */package-info.java ]] && page="$OUTPUT_DIR/$(dirname "$relative")/package-summary.html"
    [[ -f "$page" ]] || fail "$module is missing from the output: no $page"
done
log "done: $OUTPUT_DIR/index.html"
