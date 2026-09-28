#!/bin/sh
profile=$(mktemp -d /tmp/orion-chromium.XXXXXX)
exec /usr/bin/chromium-browser --no-sandbox --user-data-dir="$profile" \
    --window-size=1440,900 --start-maximized --no-first-run \
    --remote-debugging-address=0.0.0.0 --remote-debugging-port=9222 \
    --host-resolver-rules='MAP orion.test host.docker.internal' \
    https://fixture.orion.test:8443
