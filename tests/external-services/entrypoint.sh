#!/bin/sh
set -eu
umask 077
mkdir -p /state/step /state/tls /state/gitea /state/weed /run/sshd
if [ ! -f /state/step/config/ca.json ]; then
    head -c 32 /dev/urandom | base64 > /state/step/password
    step ca init --deployment-type standalone --name 'Orion test CA' \
        --dns fixture.orion.test --address ':9000' --provisioner admin \
        --password-file /state/step/password --acme
fi
if [ ! -f /state/tls/fixture.crt ] || \
    ! openssl x509 -checkend 604800 -noout -in /state/tls/fixture.crt >/dev/null 2>&1; then
    step certificate create fixture.orion.test \
        /state/tls/fixture.new.crt /state/tls/fixture.new.key \
        --profile leaf --san fixture.orion.test \
        --not-after 2160h \
        --ca /state/step/certs/intermediate_ca.crt \
        --ca-key /state/step/secrets/intermediate_ca_key \
        --ca-password-file /state/step/password --no-password --insecure
    cat /state/step/certs/intermediate_ca.crt >> /state/tls/fixture.new.crt
    mv /state/tls/fixture.new.key /state/tls/fixture.key
    mv /state/tls/fixture.new.crt /state/tls/fixture.crt
fi
mkdir -p /root/.pki/nssdb
if [ ! -f /root/.pki/nssdb/cert9.db ]; then
    certutil -N --empty-password -d sql:/root/.pki/nssdb
fi
certutil -D -d sql:/root/.pki/nssdb -n 'Orion test CA' >/dev/null 2>&1 || true
certutil -A -d sql:/root/.pki/nssdb -n 'Orion test CA' -t 'C,,' \
    -i /state/step/certs/root_ca.crt
if [ ! -f /state/ssh/ssh_host_ed25519_key ]; then
    mkdir -p /state/ssh
    ssh-keygen -q -t ed25519 -N '' -f /state/ssh/ssh_host_ed25519_key
fi
cat > /etc/ssh/sshd_config <<'EOF'
Port 2222
HostKey /state/ssh/ssh_host_ed25519_key
PasswordAuthentication yes
PubkeyAuthentication yes
PermitRootLogin no
UsePAM no
AuthorizedKeysFile .ssh/authorized_keys
Subsystem sftp internal-sftp
EOF
if ! id fixture >/dev/null 2>&1; then adduser -D -h /state/fixture -s /bin/sh fixture; fi
mkdir -p /state/fixture/.ssh
chown -R fixture:fixture /state/fixture
chmod 700 /state/fixture/.ssh
echo "fixture:${SSH_PASSWORD:?set SSH_PASSWORD}" | chpasswd
if [ ! -f /state/weed/s3.json ]; then
    cat > /state/weed/s3.json <<EOF
{"identities":[{"name":"fixture","credentials":[{"accessKey":"${S3_ACCESS_KEY:?}",
"secretKey":"${S3_SECRET_KEY:?}"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
EOF
fi
if [ ! -f /state/gitea/app.ini ]; then
    mkdir -p /state/gitea/data
    chown -R git:git /state/gitea
    cat > /state/gitea/app.ini <<'EOF'
APP_NAME = Orion external fixture
RUN_MODE = prod
[server]
PROTOCOL = http
HTTP_ADDR = 127.0.0.1
HTTP_PORT = 3000
ROOT_URL = https://fixture.orion.test:8443/
DOMAIN = fixture.orion.test
SSH_DOMAIN = fixture.orion.test
SSH_PORT = 2223
START_SSH_SERVER = true
SSH_LISTEN_PORT = 2223
[database]
DB_TYPE = sqlite3
PATH = /state/gitea/data/gitea.db
[security]
INSTALL_LOCK = true
[service]
DISABLE_REGISTRATION = true
[oauth2]
ENABLED = true
[repository]
ROOT = /state/gitea/data/repositories
EOF
fi
chown -R git:git /state/gitea
exec /usr/bin/supervisord -c /etc/supervisord.conf
