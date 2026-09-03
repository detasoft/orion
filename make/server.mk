ORION_ROOT ?= $(CURDIR)/orion_root
ORION_SSH_HOST ?= localhost
ORION_SSH_PORT ?= 8022
ORION_HTTP_HOST ?= localhost
ORION_HTTP_PORT ?= 8000
ORION_SSH_USER ?= root
ORION_SSH_KEY ?= $(ORION_ROOT)/admin-identity.pem
ORION_SSH_OPTIONS ?= -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
ORION_TOKEN_TTL_SECONDS ?= 3600
ORION_GIT_USER ?= $(ORION_SSH_USER)
ORION_GIT_KEY ?= $(ORION_SSH_KEY)
ORION_REPOSITORY ?= project.git
ORION_REPOSITORY_NAME ?= $(patsubst %.git,%,$(ORION_REPOSITORY))
ORION_CLONE_DIR ?= $(CURDIR)/target/orion-clone
ORION_CHECK_RUN_ID := $(shell date -u +%Y%m%dT%H%M%SZ)
ORION_CHECK_REPOSITORY ?= orion-check-$(ORION_CHECK_RUN_ID).git
ORION_CHECK_PUSH_DIR ?= $(CURDIR)/target/orion-check-ssh-push
ORION_CHECK_SSH_CLONE_DIR ?= $(CURDIR)/target/orion-check-ssh-clone
ORION_CHECK_REPOSITORY_NAME ?= $(patsubst %.git,%,$(ORION_CHECK_REPOSITORY))
ORION_GIT_URL = ssh://$(ORION_GIT_USER)@$(ORION_SSH_HOST):$(ORION_SSH_PORT)/$(ORION_REPOSITORY)
ORION_HTTP_GIT_URL = http://$(ORION_HTTP_HOST):$(ORION_HTTP_PORT)/r/$(ORION_REPOSITORY)
ORION_CHECK_GIT_URL = ssh://$(ORION_GIT_USER)@$(ORION_SSH_HOST):$(ORION_SSH_PORT)/$(ORION_CHECK_REPOSITORY)
ORION_CHECK_HTTP_GIT_URL = http://$(ORION_HTTP_HOST):$(ORION_HTTP_PORT)/r/$(ORION_CHECK_REPOSITORY)
ISSUE_TOKEN_COMMAND = ssh $(ORION_SSH_OPTIONS) -i $(ORION_SSH_KEY) \
	-p $(ORION_SSH_PORT) -l root $(ORION_SSH_HOST) issue-token $(ORION_TOKEN_TTL_SECONDS)

.PHONY: run-server admin-key enroll-admin-key require-key-material-password issue-token issue-token-raw
.PHONY: ssh-state ssh-status list-repos clone-repository clone-repo clone-http-repo
.PHONY: admin-acl admin-acl-with-token
.PHONY: check-git-all check-jetty-git check-ssh-git check-ssh-git-clone check-ssh-git-push-create

ifneq ($(filter clone-http-repo,$(MAKECMDGOALS)),)
CLONE_HTTP_REPO_ARGS := $(filter-out clone-http-repo,$(MAKECMDGOALS))
ifneq ($(strip $(CLONE_HTTP_REPO_ARGS)),)
.PHONY: $(CLONE_HTTP_REPO_ARGS)
$(CLONE_HTTP_REPO_ARGS):
	@:
endif
endif

require-key-material-password:
	@test -n "$${ORION_KEY_MATERIAL_PASSWORD}" || { \
		echo 'ORION_KEY_MATERIAL_PASSWORD is required for the protected key-material store.' >&2; \
		exit 2; \
	}

admin-key:
	@if [ ! -f "$(ORION_SSH_KEY)" ]; then \
		mkdir -p "$(dir $(ORION_SSH_KEY))"; \
		ssh-keygen -q -t ed25519 -N '' -f "$(ORION_SSH_KEY)"; \
	fi

enroll-admin-key: admin-key
	@test -n "$${ORION_SSH_ENROLLMENT_TOKEN}" || { \
		echo 'ORION_SSH_ENROLLMENT_TOKEN is required; copy it from first-start output.' >&2; \
		exit 2; \
	}
	@DISPLAY=orion SSH_ASKPASS="$(CURDIR)/make/ssh-enrollment-askpass.sh" SSH_ASKPASS_REQUIRE=force \
		ssh $(ORION_SSH_OPTIONS) -o PreferredAuthentications=publickey,keyboard-interactive \
		-o PasswordAuthentication=no -i "$(ORION_SSH_KEY)" -p $(ORION_SSH_PORT) \
		-l root $(ORION_SSH_HOST) state >/dev/null 2>&1 || true
	@ssh $(ORION_SSH_OPTIONS) -i "$(ORION_SSH_KEY)" -p $(ORION_SSH_PORT) \
		-l root $(ORION_SSH_HOST) state >/dev/null
	@printf 'Admin SSH key enrolled: %s\n' "$(ORION_SSH_KEY)"

run-server: require-key-material-password admin-key
	$(MAVEN) -pl core/bootstrap -am -Prun-server process-classes

# Scenario:
# 1. Export ORION_KEY_MATERIAL_PASSWORD, then start the server: make run-server
# 2. Enroll the generated admin key with the token printed on first start.
# 3. Issue a temporary admin token and export it into the current shell:
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

# List native repositories through the Orion SSH admin command:
#   make list-repos
list-repos:
	@ssh $(ORION_SSH_OPTIONS) -i $(ORION_SSH_KEY) -p $(ORION_SSH_PORT) \
		-l $(ORION_SSH_USER) $(ORION_SSH_HOST) repositories

# Clone a repository over Orion SSH:
#   make clone-repository ORION_GIT_USER=e2e ORION_GIT_KEY=/path/to/id_rsa ORION_REPOSITORY=project.git ORION_CLONE_DIR=target/project
clone-repository:
	GIT_SSH_COMMAND='ssh $(ORION_SSH_OPTIONS) -i $(ORION_GIT_KEY)' git clone $(ORION_GIT_URL) $(ORION_CLONE_DIR)

clone-repo: clone-repository

# Clone a repository over Orion HTTP with an ephemeral bearer token:
#   make clone-http-repo project
clone-http-repo:
	@if [ "$(words $(CLONE_HTTP_REPO_ARGS))" -ne 1 ]; then \
		echo 'Usage: make clone-http-repo <repository>' >&2; \
		exit 2; \
	fi
	@token="$$($(ISSUE_TOKEN_COMMAND))" || exit $$?; \
	ORION_AUTH_HEADER="Authorization: Bearer $$token" \
		git --config-env=http.extraHeader=ORION_AUTH_HEADER clone \
		"http://$(ORION_HTTP_HOST):$(ORION_HTTP_PORT)/r/$(firstword $(CLONE_HTTP_REPO_ARGS))"

admin-acl:
	@test -n "$$ORION_TOKEN" || (echo 'ORION_TOKEN is required. Run: eval "$$(make -s issue-token)"' >&2; exit 1)
	curl -v http://$(ORION_HTTP_HOST):$(ORION_HTTP_PORT)/api/admin/acl -H "Authorization: Bearer $$ORION_TOKEN"

admin-acl-with-token:
	@ORION_TOKEN="$$($(ISSUE_TOKEN_COMMAND))" || exit $$?; \
	curl -v http://$(ORION_HTTP_HOST):$(ORION_HTTP_PORT)/api/admin/acl -H "Authorization: Bearer $$ORION_TOKEN"

# Check HTTP Git discovery and SSH Git operations exposed by make run-server.
#   make check-git-all
check-git-all: check-ssh-git-push-create check-jetty-git check-ssh-git check-ssh-git-clone

# Check the Jetty HTTP Git smart discovery endpoint exposed by make run-server.
#   make check-jetty-git
check-jetty-git:
	@token="$$($(ISSUE_TOKEN_COMMAND))" || exit $$?; \
	printf 'ORION_TOKEN=%s\n' "$$token"; \
	printf 'Checking Jetty HTTP Git discovery: %s/info/refs?service=git-upload-pack\n' "$(ORION_CHECK_HTTP_GIT_URL)"; \
	response="$$(curl -fsSi \
		-H "Authorization: Bearer $$token" \
		-H "Git-Protocol: version=2" \
		"$(ORION_CHECK_HTTP_GIT_URL)/info/refs?service=git-upload-pack" 2>&1)" || { \
			printf '%s\n' "$$response"; \
			echo "HTTP Git discovery failed. Make sure the native repository exists." >&2; \
			exit 1; \
		}; \
	printf '%s\n' "$$response"; \
	printf '%s\n' "$$response" | grep -qi '^content-type: application/x-git-upload-pack-advertisement' || \
		{ echo "Missing git-upload-pack advertisement content type" >&2; exit 1; }; \
	printf '%s\n' "$$response" | grep -a 'version 2' >/dev/null || \
		{ echo "Missing Git protocol v2 advertisement" >&2; exit 1; }; \
	printf '%s\n' "$$response" | grep -a 'fetch=' >/dev/null || \
		{ echo "Missing fetch capability advertisement" >&2; exit 1; }; \
	printf 'Jetty HTTP Git discovery OK: %s/info/refs?service=git-upload-pack\n' "$(ORION_CHECK_HTTP_GIT_URL)"

# Check Git upload-pack over Orion SSH exposed by make run-server.
#   make check-ssh-git
check-ssh-git:
	@token="$$($(ISSUE_TOKEN_COMMAND))" || exit $$?; \
	printf 'ORION_TOKEN=%s\n' "$$token"; \
	printf 'Checking SSH Git upload-pack: %s\n' "$(ORION_CHECK_GIT_URL)"; \
	response="$$(GIT_TRACE_PACKET=1 GIT_SSH_COMMAND='ssh $(ORION_SSH_OPTIONS) -i $(ORION_GIT_KEY)' \
		git -c protocol.version=2 ls-remote "$(ORION_CHECK_GIT_URL)" 2>&1)" || { \
			printf '%s\n' "$$response"; \
			echo "SSH Git upload-pack failed. Make sure the native repository exists." >&2; \
			exit 1; \
		}; \
	printf '%s\n' "$$response"; \
	printf '%s\n' "$$response" | grep -a 'version 2' >/dev/null || \
		{ echo "Missing Git protocol v2 SSH advertisement" >&2; exit 1; }; \
	printf '%s\n' "$$response" | grep -a 'ls-refs' >/dev/null || \
		{ echo "Missing ls-refs capability advertisement" >&2; exit 1; }; \
	printf '%s\n' "$$response" | grep -a 'fetch=' >/dev/null || \
		{ echo "Missing fetch capability advertisement" >&2; exit 1; }; \
	printf 'SSH Git upload-pack OK: %s\n' "$(ORION_CHECK_GIT_URL)"

# Check that SSH push creates a missing native Git repository when authorized.
#   make check-ssh-git-push-create
check-ssh-git-push-create:
	rm -rf $(ORION_CHECK_PUSH_DIR)
	printf 'Checking SSH Git push auto-create: %s\n' "$(ORION_CHECK_GIT_URL)"
	git init $(ORION_CHECK_PUSH_DIR)
	printf 'orion git check\n' > $(ORION_CHECK_PUSH_DIR)/README.md
	git -C $(ORION_CHECK_PUSH_DIR) add README.md
	git -C $(ORION_CHECK_PUSH_DIR) \
		-c user.name='Orion Check' \
		-c user.email='orion-check@example.test' \
		commit -m 'orion check'
	GIT_SSH_COMMAND='ssh $(ORION_SSH_OPTIONS) -i $(ORION_GIT_KEY)' \
		git -C $(ORION_CHECK_PUSH_DIR) \
		-c protocol.version=2 \
		push $(ORION_CHECK_GIT_URL) HEAD:refs/heads/main
	printf 'SSH Git push auto-create OK: %s\n' "$(ORION_CHECK_GIT_URL)"

# Check full Git clone over Orion SSH exposed by make run-server.
#   make check-ssh-git-clone
check-ssh-git-clone:
	rm -rf $(ORION_CHECK_SSH_CLONE_DIR)
	printf 'Checking SSH Git clone: %s\n' "$(ORION_CHECK_GIT_URL)"
	GIT_SSH_COMMAND='ssh $(ORION_SSH_OPTIONS) -i $(ORION_GIT_KEY)' \
		git -c protocol.version=2 clone $(ORION_CHECK_GIT_URL) $(ORION_CHECK_SSH_CLONE_DIR)
	test -d $(ORION_CHECK_SSH_CLONE_DIR)/.git
	printf 'SSH Git clone OK: %s\n' "$(ORION_CHECK_GIT_URL)"
