#!/usr/bin/env sh
set -eu

# 用法: $0 [--signatures] <staging 目录> [zip 搜索目录]
# --signatures: 发布模式额外校验——staging 内每个 jar/zip 都必须有配对 .asc
#               且 gpg --verify 通过（Maven Central 强制签名）。
signatures=false
if [ "${1:-}" = "--signatures" ]; then
    signatures=true
    shift
fi

if [ "$#" -lt 1 ]; then
    echo "用法: $0 [--signatures] <staging 目录> [zip 搜索目录]" >&2
    exit 2
fi

staging_dir="$1"
zip_dir="${2:-flydb-cli/target}"

if [ ! -d "$staging_dir" ]; then
    echo "找不到 staging 目录: $staging_dir" >&2
    exit 1
fi

require_artifact() {
    pattern="$1"
    description="$2"
    if ! find "$staging_dir" -type f -name "$pattern" -print -quit | grep -q .; then
        echo "缺少 $description: $pattern" >&2
        exit 1
    fi
}

require_artifact '*.jar' '发布 JAR'
require_artifact '*-sources.jar' 'sources JAR'
require_artifact '*-javadoc.jar' 'javadoc JAR'

if ! find "$zip_dir" -maxdepth 1 -type f -name 'flydb-cli-*.zip' -print -quit 2>/dev/null | grep -q .; then
    echo "缺少 CLI 发行 ZIP: $zip_dir/flydb-cli-*.zip" >&2
    exit 1
fi

if [ "$signatures" = true ]; then
    for artifact in $(find "$staging_dir" -type f \( -name '*.jar' -o -name '*.zip' \) | sort); do
        if [ ! -f "$artifact.asc" ]; then
            echo "缺少签名: $artifact.asc" >&2
            exit 1
        fi
        if ! gpg --verify "$artifact.asc" "$artifact" >/dev/null 2>&1; then
            echo "签名校验失败: $artifact" >&2
            exit 1
        fi
    done
    echo "发布产物门禁通过: JAR + sources + javadoc + CLI ZIP + GPG 签名"
else
    echo "发布产物门禁通过: JAR + sources + javadoc + CLI ZIP"
fi
