#!/usr/bin/env bash
# ECS 上的发布脚本：拉代码 → 构建 api/web 镜像 → 起容器 → 健康检查。
# 云效「主机部署」任务的执行脚本调它：bash /root/wx-platform/deploy/scripts/deploy.sh
[ -n "${BASH_VERSION:-}" ] || exec bash "$0" "$@"
set -euo pipefail

SELF="${BASH_SOURCE[0]:-$0}"
REPO_DIR="$(cd "$(dirname "$SELF")/../.." && pwd)"
DEPLOY_DIR="$REPO_DIR/deploy"
BRANCH="${DEPLOY_BRANCH:-main}"
# 探 web 容器的反代而不是 api 本身：顺带证明 nginx 的 /wx/ 透传也是通的
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8088/wx/health}"
HEALTH_TRIES="${HEALTH_TRIES:-36}" # ×5s ≈ 3 分钟，够 Spring Boot 冷启动 + Flyway 迁移跑完
WAIT_SECONDS=$((HEALTH_TRIES * 5))

# 云效的失败重试和手工触发容易叠在一起，两个 compose build 抢同一个构建器会留下半截镜像
exec 9>/tmp/platform-deploy.lock
flock -w 900 9 || { echo "已有部署在跑，等满 15 分钟没拿到锁" >&2; exit 1; }

cd "$REPO_DIR"
PREV_COMMIT="$(git rev-parse --short HEAD)"
git fetch origin "$BRANCH"
git checkout "$BRANCH"
# --ff-only：服务器上手改过就直接失败，别让 git 在跑着生产的那份代码里合出一个提交
git merge --ff-only "origin/$BRANCH"
COMMIT="$(git rev-parse --short HEAD)"
echo "发布 $COMMIT $(git log -1 --pretty=%s)"

cd "$DEPLOY_DIR"
# 先 build 再 up：api/web 只声明了 build: 没有 image:，up 只在缺镜像时才构建，
# 单跑 `up -d` 会拿上一版镜像起容器，代码更新了但界面没变，最难查
docker compose build api web
docker compose up -d api web

healthy=0
for _ in $(seq 1 "$HEALTH_TRIES"); do
  if curl -fsS -o /dev/null "$HEALTH_URL"; then
    healthy=1
    break
  fi
  sleep 5
done

if [ "$healthy" != 1 ]; then
  echo "健康检查 ${WAIT_SECONDS}s 内未通过：$HEALTH_URL" >&2
  echo "容器没起来就直接看下面的日志；确认是代码问题要回滚：cd $REPO_DIR && git checkout $PREV_COMMIT && 重跑本脚本" >&2
  docker compose ps
  docker compose logs --tail=200 api web
  exit 1
fi
echo "健康检查通过：$HEALTH_URL（$COMMIT）"

# 每次 build 都把上一版镜像甩成 dangling，系统盘不清几次就满；
# 正在被容器使用的镜像不会被删，但同机其它项目构建出的无名镜像也会被一起清掉
docker image prune -f >/dev/null
echo "完成：$COMMIT"
