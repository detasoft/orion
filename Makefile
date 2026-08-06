MAVEN ?= mvn
TEST_ANALYTICS_RUN_ID ?= $(shell date -u +%Y%m%dT%H%M%SZ)
TEST_ANALYTICS_ROOT ?= $(CURDIR)/target/test-analytics
TEST_ANALYTICS_DIR ?= $(TEST_ANALYTICS_ROOT)/$(TEST_ANALYTICS_RUN_ID)
TEST_ANALYTICS_TOP ?= 50
TEST_ANALYTICS_REPORT_ARGS ?=
TEST_ANALYTICS_MAIN = pro.deta.orion.test.duration.TestAnalyticsReport
TEST_JFR_MAVEN_ARGS ?=
ORION_ROOT ?= $(CURDIR)/orion_root
ORION_SSH_HOST ?= localhost
ORION_SSH_PORT ?= 8022
ORION_HTTP_HOST ?= localhost
ORION_HTTP_PORT ?= 8000
ORION_SERVER_IDENTITY_KEY ?= $(ORION_ROOT)/server-identity/signing-rsa.pem
ORION_SSH_USER ?= root
ORION_SSH_KEY ?= $(ORION_SERVER_IDENTITY_KEY)
ORION_SSH_OPTIONS ?= -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
ORION_TOKEN_TTL_SECONDS ?= 3600
ORION_GIT_USER ?= $(ORION_SSH_USER)
ORION_GIT_KEY ?= $(ORION_SSH_KEY)
ORION_REPOSITORY ?= project.git
ORION_CLONE_DIR ?= $(CURDIR)/target/orion-clone
ORION_GIT_URL = ssh://$(ORION_GIT_USER)@$(ORION_SSH_HOST):$(ORION_SSH_PORT)/$(ORION_REPOSITORY)
ISSUE_TOKEN_COMMAND = ssh $(ORION_SSH_OPTIONS) -i $(ORION_SERVER_IDENTITY_KEY) -p $(ORION_SSH_PORT) -l root $(ORION_SSH_HOST) issue-token $(ORION_TOKEN_TTL_SECONDS)

.PHONY: test test-jfr test-jfr-report run-server issue-token issue-token-raw
.PHONY: ssh-state ssh-status clone-repository clone-repo admin-acl admin-acl-with-token

test:
	$(MAVEN) test -Pdev -T 4

test-jfr:
	@mkdir -p "$(TEST_ANALYTICS_DIR)/jfr"
	@status=0; \
	$(MAVEN) test -Pdev,test-jfr -T 4 -fae \
		-Dorion.test.analytics.runId="$(TEST_ANALYTICS_RUN_ID)" \
		-Dorion.test.analytics.dir="$(TEST_ANALYTICS_ROOT)" \
		-Dorion.test.jfr.directory="$(TEST_ANALYTICS_DIR)/jfr" \
		$(TEST_JFR_MAVEN_ARGS) || status=$$?; \
	$(MAVEN) -q -pl tests/test-duration-recorder -am -DskipTests compile || exit $$?; \
	$(MAVEN) -q -pl tests/test-duration-recorder \
		org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
		-Dexec.mainClass=$(TEST_ANALYTICS_MAIN) \
		-Dexec.args="$(TEST_ANALYTICS_DIR) $(TEST_ANALYTICS_TOP)" || exit $$?; \
	exit $$status

test-jfr-report:
	$(MAVEN) -q -pl tests/test-duration-recorder -am -DskipTests compile
	$(MAVEN) -q -pl tests/test-duration-recorder \
		org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
		-Dexec.mainClass=$(TEST_ANALYTICS_MAIN) \
		-Dexec.args="$(TEST_ANALYTICS_REPORT_ARGS)"

run-server:
	$(MAVEN) -pl core/bootstrap -am -Prun-server process-classes

# Scenario:
# 1. Start the server: make run-server
# 2. Issue a temporary admin token and export it into the current shell:
#      eval "$$(make -s issue-token)"
# 3. Use that token for the HTTP admin API:
#      make admin-acl
# Or run both token issue and ACL request in one command:
#      make admin-acl-with-token
issue-token:
	@token="$$($(ISSUE_TOKEN_COMMAND))" || exit $$?; \
	printf 'export ORION_TOKEN=%s\n' "$$token"

issue-token-raw:
	@$(ISSUE_TOKEN_COMMAND)

# SSH admin status:
#   make ssh-state
ssh-state:
	ssh $(ORION_SSH_OPTIONS) -i $(ORION_SSH_KEY) -p $(ORION_SSH_PORT) -l $(ORION_SSH_USER) $(ORION_SSH_HOST) state

ssh-status: ssh-state

# Clone a repository over Orion SSH:
#   make clone-repository ORION_GIT_USER=e2e ORION_GIT_KEY=/path/to/id_rsa ORION_REPOSITORY=project.git ORION_CLONE_DIR=target/project
clone-repository:
	GIT_SSH_COMMAND='ssh $(ORION_SSH_OPTIONS) -i $(ORION_GIT_KEY)' git clone $(ORION_GIT_URL) $(ORION_CLONE_DIR)

clone-repo: clone-repository

admin-acl:
	@test -n "$$ORION_TOKEN" || (echo 'ORION_TOKEN is required. Run: eval "$$(make -s issue-token)"' >&2; exit 1)
	curl -v http://$(ORION_HTTP_HOST):$(ORION_HTTP_PORT)/api/admin/acl -H "Authorization: Bearer $$ORION_TOKEN"

admin-acl-with-token:
	@ORION_TOKEN="$$($(ISSUE_TOKEN_COMMAND))" || exit $$?; \
	curl -v http://$(ORION_HTTP_HOST):$(ORION_HTTP_PORT)/api/admin/acl -H "Authorization: Bearer $$ORION_TOKEN"
