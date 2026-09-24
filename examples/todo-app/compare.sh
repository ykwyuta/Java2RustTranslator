#!/usr/bin/env bash
# Spring Boot 版と Rust 版をそれぞれ空のデータベースで起動し、compare.py で応答を比べる。
#
#   PG_ADMIN_URL  データベースを作り直せる接続 URL（既定: postgres://todo:todo@localhost:5432/postgres。CREATEDB 権限が必要）
#   SPRING_PORT / RUST_PORT  起動するポート（既定: 18080 / 18081）
set -euo pipefail

cd "$(dirname "$0")"
PG_ADMIN_URL=${PG_ADMIN_URL:-postgres://todo:todo@localhost:5432/postgres}
SPRING_PORT=${SPRING_PORT:-18080}
RUST_PORT=${RUST_PORT:-18081}
PG_HOST_PORT_USER=$(python3 -c "
import sys, urllib.parse as u
p = u.urlparse(sys.argv[1])
print(p.hostname, p.port or 5432, p.username, p.password or '')" "$PG_ADMIN_URL")
read -r PG_HOST PG_PORT PG_USER PG_PASSWORD <<< "$PG_HOST_PORT_USER"

for db in todo_compare_spring todo_compare_rust; do
    psql "$PG_ADMIN_URL" -q -c "SET client_min_messages = warning" -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
done

(cd spring-boot && ./gradlew bootJar -q)
(cd rust && cargo build --release -q)

pids=()
trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT

DATABASE_URL="jdbc:postgresql://$PG_HOST:$PG_PORT/todo_compare_spring" DATABASE_USERNAME="$PG_USER" \
DATABASE_PASSWORD="$PG_PASSWORD" PORT=$SPRING_PORT \
    java -jar spring-boot/build/libs/todo-spring-boot-0.0.1-SNAPSHOT.jar > spring-boot/build/compare.log 2>&1 &
pids+=($!)
(cd rust && DATABASE_URL="postgres://$PG_USER:$PG_PASSWORD@$PG_HOST:$PG_PORT/todo_compare_rust" PORT=$RUST_PORT \
    exec target/release/todo > target/compare.log 2>&1) &
pids+=($!)

for port in "$SPRING_PORT" "$RUST_PORT"; do
    for _ in $(seq 60); do
        curl -sf -o /dev/null "http://localhost:$port/todos" && break
        sleep 1
    done
done

python3 compare.py "http://localhost:$SPRING_PORT" "http://localhost:$RUST_PORT"
