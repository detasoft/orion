#!/bin/sh
### BEGIN INIT INFO
# Provides:          orion
# Required-Start:    $remote_fs $network
# Required-Stop:     $remote_fs $network
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: Orion Git hosting service
# Description:       Starts and stops the Orion Git hosting service.
### END INIT INFO

set -u

APP_NAME=${APP_NAME:-orion}
JAVA_OPTS=${JAVA_OPTS:-}
ORION_STOP_TIMEOUT=${ORION_STOP_TIMEOUT:-30}
java=${JAVA_CMD:-java}

PRG=$0
while [ -h "$PRG" ]; do
  ls_output=$(ls -ld "$PRG")
  link=$(expr "$ls_output" : '.*-> \(.*\)$')
  case "$link" in
    /*) PRG=$link ;;
    *) PRG=$(dirname "$PRG")/$link ;;
  esac
done

SELF_DIR=$(cd "$(dirname "$PRG")" >/dev/null 2>&1 && pwd -P)
SELF=$SELF_DIR/$(basename "$PRG")
ORION_HOME=${ORION_HOME:-$SELF_DIR}
PID_FILE=${ORION_PID_FILE:-$ORION_HOME/$APP_NAME.pid}
LOG_FILE=${ORION_LOG_FILE:-$ORION_HOME/$APP_NAME.log}

get_pid() {
  [ -f "$PID_FILE" ] || return 1
  IFS= read -r pid < "$PID_FILE" || return 1
  [ -n "$pid" ] || return 1
  printf '%s\n' "$pid"
}

is_running() {
  pid=$(get_pid) || return 1
  kill -0 "$pid" 2>/dev/null
}

run_app() {
  exec "$java" $JAVA_OPTS -jar "$SELF" "$@"
}

start_app() {
  if is_running; then
    pid=$(get_pid)
    echo "$APP_NAME is already running with PID $pid"
    return 0
  fi

  mkdir -p "$(dirname "$PID_FILE")" "$(dirname "$LOG_FILE")"
  nohup "$java" $JAVA_OPTS -jar "$SELF" "$@" >> "$LOG_FILE" 2>&1 &
  pid=$!
  echo "$pid" > "$PID_FILE"
  echo "$APP_NAME started with PID $pid"
}

stop_app() {
  pid=$(get_pid) || {
    echo "$APP_NAME is not running"
    return 0
  }

  if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$PID_FILE"
    echo "$APP_NAME is not running"
    return 0
  fi

  kill "$pid" 2>/dev/null || true
  elapsed=0
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$elapsed" -ge "$ORION_STOP_TIMEOUT" ]; then
      kill -KILL "$pid" 2>/dev/null || true
      break
    fi
    elapsed=$((elapsed + 1))
    sleep 1
  done

  rm -f "$PID_FILE"
  echo "$APP_NAME stopped"
}

status_app() {
  if is_running; then
    pid=$(get_pid)
    echo "$APP_NAME is running with PID $pid"
    return 0
  fi

  echo "$APP_NAME is not running"
  return 3
}

usage() {
  echo "Usage: $0 {run|start|stop|status|restart} [orion arguments]"
}

command=${1:-run}
if [ "$#" -gt 0 ]; then
  shift
fi

case "$command" in
  run)
    run_app "$@"
    ;;
  start)
    start_app "$@"
    ;;
  stop)
    stop_app
    ;;
  status)
    status_app
    ;;
  restart)
    stop_app
    start_app "$@"
    ;;
  *)
    usage
    exit 2
    ;;
esac

exit $?
