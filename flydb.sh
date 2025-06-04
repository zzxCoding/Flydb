#!/bin/bash

# FlyDB数据库迁移工具脚本

# 默认配置
HOST="localhost"
PORT="8080"
BASE_URL="http://${HOST}:${PORT}/api/flydb"
DATABASE="default"

# 帮助信息
show_help() {
    echo "FlyDB数据库迁移工具"
    echo "用法:"
    echo "  ./flydb.sh <命令> [参数]"
    echo ""
    echo "命令:"
    echo "  init [数据库名]     初始化数据库版本控制（可选指定数据库名）"
    echo "  version [数据库名]  查看当前数据库版本（可选指定数据库名）"
    echo "  migrate [版本号] [数据库名]   执行数据库迁移（可选指定目标版本号和数据库名）"
    echo "  rollback [版本号] [数据库名]  回退到上一个版本或指定版本（可选指定数据库名）"
    echo "  help              显示帮助信息"
    echo ""
    echo "示例:"
    echo "  ./flydb.sh init"
    echo "  ./flydb.sh init test"
    echo "  ./flydb.sh version"
    echo "  ./flydb.sh version test"
    echo "  ./flydb.sh migrate"
    echo "  ./flydb.sh migrate 5 test"
    echo "  ./flydb.sh rollback 1 test"
}

# 检查curl是否可用
if ! command -v curl &> /dev/null; then
    echo "错误: 需要安装curl命令行工具"
    exit 1
fi

# 如果没有参数，显示帮助信息
if [ $# -eq 0 ]; then
    show_help
    exit 1
fi

# 解析命令
case $1 in
    "init")
        if [ $# -eq 2 ]; then
            DATABASE=$2
        fi
        echo "正在初始化数据库 ${DATABASE}..."
        curl -X POST "${BASE_URL}/init?database=${DATABASE}"
        ;;
    "version")
        if [ $# -eq 2 ]; then
            DATABASE=$2
        fi
        echo "正在获取数据库 ${DATABASE} 的当前版本..."
        curl -X GET "${BASE_URL}/version?database=${DATABASE}"
        ;;
    "migrate")
        VERSION=""
        if [ $# -ge 2 ]; then
            VERSION=$2
            if [ $# -eq 3 ]; then
                DATABASE=$3
            fi
        fi
        echo "正在对数据库 ${DATABASE} 执行迁移..."
        if [ -n "$VERSION" ]; then
            curl -X POST "${BASE_URL}/migrate?targetVersion=${VERSION}&database=${DATABASE}"
        else
            curl -X POST "${BASE_URL}/migrate?database=${DATABASE}"
        fi
        ;;
    "rollback")
        if [ $# -ge 2 ]; then
            VERSION=$2
            if [ $# -eq 3 ]; then
                DATABASE=$3
            fi
            echo "正在将数据库 ${DATABASE} 回退到版本 ${VERSION}..."
            curl -X POST "${BASE_URL}/rollback/${VERSION}?database=${DATABASE}"
        else
            echo "错误: 回退命令需要指定目标版本号"
            exit 1
        fi
        ;;
    "help")
        show_help
        ;;
    *)
        echo "错误: 未知命令 '$1'"
        show_help
        exit 1
        ;;
esac

echo ""