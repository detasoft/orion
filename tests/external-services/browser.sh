#!/bin/sh
exec /usr/bin/chromium-browser --no-sandbox --user-data-dir=/state/chromium \
    --window-size=1440,900 --start-maximized --no-first-run \
    --host-resolver-rules='MAP orion.test host.docker.internal' \
    https://fixture.orion.test:8443
