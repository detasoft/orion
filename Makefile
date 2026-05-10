MAVEN ?= mvn
ORION_ROOT ?= $(CURDIR)/target/orion_root
ORION_SSH_HOST ?= localhost
ORION_SSH_PORT ?= 8022
ORION_HTTP_HOST ?= localhost
ORION_HTTP_PORT ?= 8000
ORION_SERVER_IDENTITY_KEY ?= $(ORION_ROOT)/server-identity/signing-rsa.pem
ORION_TOKEN_TTL_SECONDS ?= 3600

.PHONY: run-server issue-token admin-acl admin-acl-with-token

run-server:
	$(MAVEN) -pl core/bootstrap -am -Prun-server process-classes

# Scenario:
# 1. Start the server: make run-server
# 2. Issue a temporary admin token over SSH using the server identity key:
#      make issue-token
# 3. Use that token for the HTTP admin API:
#      ORION_TOKEN="$$(make -s issue-token)" make admin-acl
# Or run both token issue and ACL request in one command:
#      make admin-acl-with-token
issue-token:
	@ssh $(ORION_SSH_HOST) -p $(ORION_SSH_PORT) -i $(ORION_SERVER_IDENTITY_KEY) -l root issue-token $(ORION_TOKEN_TTL_SECONDS)

admin-acl:
	@test -n "$$ORION_TOKEN" || (echo 'ORION_TOKEN is required. Run: ORION_TOKEN="$$(make -s issue-token)" make admin-acl' >&2; exit 1)
	curl -v http://$(ORION_HTTP_HOST):$(ORION_HTTP_PORT)/api/admin/acl -H "Authorization: Bearer $$ORION_TOKEN"

admin-acl-with-token:
	@ORION_TOKEN="$$(ssh $(ORION_SSH_HOST) -p $(ORION_SSH_PORT) -i $(ORION_SERVER_IDENTITY_KEY) -l root issue-token $(ORION_TOKEN_TTL_SECONDS))"; \
	curl -v http://$(ORION_HTTP_HOST):$(ORION_HTTP_PORT)/api/admin/acl -H "Authorization: Bearer $$ORION_TOKEN"
