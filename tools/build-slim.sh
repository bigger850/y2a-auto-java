#!/usr/bin/env bash
# 构建精简镜像。
#
# 背景：此前用 Dockerfile.hotfix（FROM 上次镜像再 COPY jar）增量更新，
# 每次都会叠加一层 248MB 的 jar，累积 11 层后镜像涨到 8.95GB。
#
# 本脚本用 export/import 把运行容器压平成单层，再从零补回配置，
# 产出约 3GB 的镜像。
#
# 用法：
#   ./tools/build-slim.sh
set -euo pipefail

cd "$(dirname "$0")/.."

CURRENT="${CURRENT_IMAGE:-y2a-java-server:latest}"
FLAT="y2a-java-server:flat"
SLIM="y2a-java-server:slim"
TMP_TAR="y2a-flat-$$.tar"

echo "==> 1. 打包应用 jar（如已构建可跳过：SKIP_BUILD=1）"
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  mvn -q -DskipTests package
fi

echo "==> 2. 启动临时容器（从当前镜像）"
CID=$(docker create "$CURRENT" true)

echo "==> 3. 导出文件系统压平所有历史层"
docker export "$CID" -o "$TMP_TAR"
docker rm -f "$CID" >/dev/null

echo "==> 4. 导入为单层镜像"
docker import "$TMP_TAR" "$FLAT" >/dev/null
rm -f "$TMP_TAR"

echo "==> 5. 补回 ENV / WORKDIR / ENTRYPOINT"
docker build -f Dockerfile.flat -t "$SLIM" . >/dev/null

echo "==> 6. 替换 latest"
docker tag "$SLIM" "$CURRENT"

echo "==> 完成"
docker images --format '{{.Repository}}:{{.Tag}}\t{{.Size}}' | grep y2a-java-server
