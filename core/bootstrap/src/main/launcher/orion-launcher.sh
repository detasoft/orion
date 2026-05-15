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

JAVA_OPTS=${JAVA_OPTS:-}
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

exec "$java" $JAVA_OPTS -jar "$SELF" "$@"
