#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
launcher="$script_dir/../flydb-cli/src/main/distribution/bin/flydb"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/flydb-launcher-test.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

fake_java_home="$tmp_dir/jdk"
mkdir -p "$fake_java_home/bin"
cat > "$fake_java_home/bin/java" <<'EOF'
#!/usr/bin/env sh
if [ "${1:-}" = "-version" ]; then
    echo 'Picked up _JAVA_OPTIONS: -Djava.net.preferIPv4Stack=true' >&2
    echo 'java version "17.0.13" 2024-10-15 LTS' >&2
    exit 0
fi
echo 'FLYDB_LAUNCHER_OK'
EOF
chmod +x "$fake_java_home/bin/java"

set +e
output=$(JAVA_HOME="$fake_java_home" sh "$launcher" version 2>&1)
status=$?
set -e
if [ "$status" -ne 0 ]; then
    printf '%s\n' "$output" >&2
    exit "$status"
fi
printf '%s\n' "$output" | grep -Fx 'FLYDB_LAUNCHER_OK' >/dev/null
printf '%s\n' 'CLI launcher injected-version-line regression passed'
