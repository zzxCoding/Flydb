#!/usr/bin/env sh
set -eu

if [ "$#" -lt 2 ]; then
    echo "用法: $0 <最高 class major version> <classes 目录>..." >&2
    exit 2
fi

max_version="$1"
shift

case "$max_version" in
    ''|*[!0-9]*)
        echo "最高 class major version 必须是数字: $max_version" >&2
        exit 2
        ;;
esac

for classes_dir in "$@"; do
    if [ ! -d "$classes_dir" ]; then
        echo "找不到 class 目录: $classes_dir" >&2
        exit 1
    fi

    find "$classes_dir" -type f -name '*.class' -print |
    while IFS= read -r class_file; do
        major_version="$(javap -verbose "$class_file" 2>/dev/null |
            awk '/major version:/ { print $3; exit }')"
        if [ -z "$major_version" ]; then
            echo "无法读取 class major version: $class_file" >&2
            exit 1
        fi
        if [ "$major_version" -gt "$max_version" ]; then
            echo "class 版本超出门禁: $class_file (major=$major_version, max=$max_version)" >&2
            exit 1
        fi
    done
done

echo "class 文件版本门禁通过: max major version=$max_version"
