#!/bin/sh
set -eu
root=/state/step/certs/root_ca.crt
curl -fsS --cacert "$root" https://fixture.orion.test:8443/api/v1/version \
    | jq -e '.version' >/dev/null
curl -fsS --cacert "$root" https://fixture.orion.test:8443/.well-known/openid-configuration \
    | jq -e '.issuer == "https://fixture.orion.test:8443/"' >/dev/null
curl -fsS --cacert "$root" https://fixture.orion.test:9000/acme/acme/directory \
    | jq -e '.newOrder' >/dev/null
curl -fsS http://localhost:6080/vnc.html >/dev/null
if curl -fsS https://fixture.orion.test:8443/api/v1/version >/dev/null 2>&1; then
    echo 'HTTPS unexpectedly trusted without the fixture CA' >&2
    exit 1
fi
chromium-browser --headless --no-sandbox --disable-gpu --disable-dev-shm-usage \
    --log-level=3 --user-data-dir=/tmp/chromium-cert-check \
    --dump-dom https://fixture.orion.test:8443/api/v1/version 2>/dev/null \
    | grep -F '"version":"1.24.6"' >/dev/null
echo 'HTTPS, OIDC, ACME directory and noVNC reachable'

printf '[fixture.orion.test]:2222 %s\n' \
    "$(cat /state/ssh/ssh_host_ed25519_key.pub)" > /state/ssh_known_hosts_normal
sshpass -p "$SSH_PASSWORD" ssh -p 2222 -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile=/state/ssh_known_hosts_normal fixture@fixture.orion.test true
if sshpass -p wrong-password ssh -p 2222 -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile=/state/ssh_known_hosts_normal -o PreferredAuthentications=password \
    -o NumberOfPasswordPrompts=1 fixture@fixture.orion.test true 2>/dev/null; then
    echo 'Invalid SSH password was accepted' >&2
    exit 1
fi
echo 'SSH password and rejection verified'

if [ ! -f /state/fixture-client ]; then
    ssh-keygen -q -t ed25519 -N '' -f /state/fixture-client
fi
if ! grep -q -F "$(cat /state/fixture-client.pub)" /state/fixture/.ssh/authorized_keys 2>/dev/null; then
    sshpass -p "$SSH_PASSWORD" ssh -p 2222 -o StrictHostKeyChecking=yes \
        -o UserKnownHostsFile=/state/ssh_known_hosts_normal fixture@fixture.orion.test \
        'umask 077; mkdir -p ~/.ssh; cat >> ~/.ssh/authorized_keys' < /state/fixture-client.pub
fi
ssh -i /state/fixture-client -p 2222 -o IdentitiesOnly=yes \
    -o StrictHostKeyChecking=yes -o UserKnownHostsFile=/state/ssh_known_hosts_normal \
    fixture@fixture.orion.test true
if [ ! -f /state/wrong-client ]; then ssh-keygen -q -t ed25519 -N '' -f /state/wrong-client; fi
if ssh -i /state/wrong-client -p 2222 -o IdentitiesOnly=yes -o BatchMode=yes \
    -o StrictHostKeyChecking=yes -o UserKnownHostsFile=/state/ssh_known_hosts_normal \
    fixture@fixture.orion.test true 2>/dev/null; then
    echo 'Invalid SSH key was accepted' >&2
    exit 1
fi
echo 'SSH key and rejection verified'

api=https://fixture.orion.test:8443/api/v1
if ! curl -fsS --cacert "$root" -u "fixture:$GITEA_PASSWORD" \
    "$api/repos/fixture/fixture" >/dev/null 2>&1; then
    curl -fsS --cacert "$root" -u "fixture:$GITEA_PASSWORD" \
        -H 'Content-Type: application/json' -d '{"name":"fixture","private":true}' \
        "$api/user/repos" >/dev/null
fi
if ! curl -fsS --cacert "$root" -u "fixture:$GITEA_PASSWORD" "$api/user/keys" \
    | jq -e --arg key "$(cat /state/fixture-client.pub)" '.[] | select(.key == $key)' >/dev/null; then
    jq -n --arg key "$(cat /state/fixture-client.pub)" '{title:"fixture",key:$key}' \
        | curl -fsS --cacert "$root" -u "fixture:$GITEA_PASSWORD" \
            -H 'Content-Type: application/json' -d @- "$api/user/keys" >/dev/null
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git init -q "$work/repo"
git -C "$work/repo" config user.name Fixture
git -C "$work/repo" config user.email fixture@example.test
echo fixture > "$work/repo/check.txt"
git -C "$work/repo" add check.txt
git -C "$work/repo" commit -qm fixture
branch="fixture-check-$(date +%s)"
GIT_SSL_CAINFO="$root" git -C "$work/repo" push -q \
    "https://fixture:$GITEA_PASSWORD@fixture.orion.test:8443/fixture/fixture.git" \
    "HEAD:refs/heads/$branch"
expected=$(git -C "$work/repo" rev-parse HEAD)
printf '[fixture.orion.test]:2223 %s\n' \
    "$(cat /state/gitea/data/ssh/gitea.rsa.pub)" > /state/ssh_known_hosts
ssh_options='-o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes'
ssh_options="$ssh_options -o UserKnownHostsFile=/state/ssh_known_hosts"
actual=$(GIT_SSH_COMMAND="ssh -i /state/fixture-client $ssh_options" \
    git ls-remote ssh://git@fixture.orion.test:2223/fixture/fixture.git \
    "refs/heads/$branch" | cut -f1)
[ "$actual" = "$expected" ]
if GIT_SSL_CAINFO="$root" GIT_TERMINAL_PROMPT=0 git ls-remote \
    https://fixture:wrong-password@fixture.orion.test:8443/fixture/fixture.git \
    >/dev/null 2>&1; then
    echo 'Invalid Git HTTPS password was accepted' >&2
    exit 1
fi
if GIT_SSH_COMMAND="ssh -i /state/wrong-client $ssh_options" \
    git ls-remote ssh://git@fixture.orion.test:2223/fixture/fixture.git >/dev/null 2>&1; then
    echo 'Invalid Git SSH key was accepted' >&2
    exit 1
fi
GIT_SSL_CAINFO="$root" git -C "$work/repo" push -q \
    "https://fixture:$GITEA_PASSWORD@fixture.orion.test:8443/fixture/fixture.git" \
    ":refs/heads/$branch"
echo 'Git HTTPS push, SSH fetch, and rejected credentials verified'

python3 - <<'PY'
import boto3
from botocore.exceptions import ClientError
import os

def client(secret):
    return boto3.client('s3', endpoint_url='https://fixture.orion.test:8333',
        aws_access_key_id=os.environ['S3_ACCESS_KEY'], aws_secret_access_key=secret,
        region_name='us-east-1', verify='/state/step/certs/root_ca.crt')

s3 = client(os.environ['S3_SECRET_KEY'])
bucket = 'orion-fixture'
if bucket not in [item['Name'] for item in s3.list_buckets()['Buckets']]:
    s3.create_bucket(Bucket=bucket)
s3.put_object(Bucket=bucket, Key='check', Body=b'fixture')
assert s3.get_object(Bucket=bucket, Key='check')['Body'].read() == b'fixture'
try:
    client('wrong-secret').list_buckets()
except ClientError as error:
    assert error.response['ResponseMetadata']['HTTPStatusCode'] in (401, 403)
else:
    raise AssertionError('invalid S3 credentials were accepted')
print('S3 read/write and rejection verified')
PY
