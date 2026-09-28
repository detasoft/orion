FROM docker.gitea.com/gitea:1.24.6

RUN apk add --no-cache ca-certificates curl openssh nginx openssl step-cli \
    chromium xvfb x11vnc openbox novnc websockify supervisor bash jq git \
    python3 py3-boto3 sshpass nss-tools

ARG TARGETARCH
COPY .cache/${TARGETARCH}/step-ca /usr/local/bin/step-ca
COPY .cache/${TARGETARCH}/weed /usr/local/bin/weed
RUN chmod +x /usr/local/bin/step-ca /usr/local/bin/weed

COPY entrypoint.sh /usr/local/bin/external-entrypoint
COPY check.sh /usr/local/bin/external-check
COPY browser.sh /usr/local/bin/external-browser
COPY chromium-policy.json /etc/chromium/policies/managed/orion.json
COPY nginx.conf /etc/nginx/http.d/default.conf
COPY supervisord.conf /etc/supervisord.conf
RUN chmod +x /usr/local/bin/external-entrypoint /usr/local/bin/external-check \
    /usr/local/bin/external-browser

ENV STEPPATH=/state/step
VOLUME /state
EXPOSE 80 8443 8333 9000 2222 2223 6080 9223
ENTRYPOINT ["/usr/local/bin/external-entrypoint"]
