MAVEN ?= mvn
UV ?= uv
TEST_ANALYTICS_RUN_ID ?= $(shell date -u +%Y%m%dT%H%M%SZ)
TEST_ANALYTICS_ROOT ?= $(CURDIR)/target/test-analytics
TEST_ANALYTICS_DIR ?= $(TEST_ANALYTICS_ROOT)/$(TEST_ANALYTICS_RUN_ID)
TEST_ANALYTICS_TOP ?= 50
TEST_ANALYTICS_REPORT_ARGS ?=
TEST_ANALYTICS_MAIN = pro.deta.orion.test.duration.TestAnalyticsReport
TEST_JFR_MAVEN_ARGS ?=
RUN_TEST_NAMED_USAGE = Usage: make run-test MODULE=<module> TEST='<test-locator>'
RUN_TEST_POSITIONAL_USAGE =    or: make run-test <module> '<test-locator>'
RUN_TEST_CONFLICT_USAGE = Positional arguments cannot match Make goals; use MODULE=... TEST=... instead
RUN_TEST_RESERVED_GOALS = dist test run-test test-jfr test-jfr-report xml-schema \
	help skill-check skills-check \
	run-server issue-token issue-token-raw ssh-state ssh-status list-repos \
	clone-repository clone-repo clone-http-repo admin-acl admin-acl-with-token \
	check-git-all check-jetty-git check-ssh-git check-ssh-git-clone check-ssh-git-push-create \
	cargo-init rust-install session-host session-host-test session-host-linux-test
RUN_TEST_POSITIONAL_ARGUMENTS :=
RUN_TEST_POSITIONAL_CONFLICT = $(filter $(RUN_TEST_RESERVED_GOALS),$(RUN_TEST_POSITIONAL_ARGUMENTS))
RUN_TEST_MODULE = $(value MODULE)
RUN_TEST_LOCATOR = $(value TEST)
SKILL_DIRS := $(sort $(dir $(wildcard .agents/skills/*/SKILL.md)))

.DEFAULT_GOAL := help

ifeq ($(firstword $(MAKECMDGOALS)),run-test)
RUN_TEST_POSITIONAL_ARGUMENTS := $(wordlist 2,$(words $(MAKECMDGOALS)),$(MAKECMDGOALS))
ifneq ($(strip $(RUN_TEST_POSITIONAL_ARGUMENTS)),)
%:
	@:
endif
ifeq ($(words $(RUN_TEST_POSITIONAL_ARGUMENTS)),2)
RUN_TEST_MODULE := $(word 1,$(RUN_TEST_POSITIONAL_ARGUMENTS))
RUN_TEST_LOCATOR := $(word 2,$(RUN_TEST_POSITIONAL_ARGUMENTS))
endif
endif

.PHONY: help dist test run-test test-jfr test-jfr-report xml-schema skill-check skills-check \
	cargo-init rust-install session-host session-host-test session-host-linux-test

help: ## Show available goals and their descriptions
	@awk '\
		BEGIN { print "Available goals:" } \
		/^## / { pending = substr($$0, 4); next } \
		/^[[:alnum:]_.-]+:[^=]/ { \
			target = $$1; \
			sub(/:$$/, "", target); \
			desc = ""; \
			if ($$0 ~ /## /) { \
				desc = $$0; \
				sub(/^[^#]*## /, "", desc); \
			} else if (pending != "") { \
				desc = pending; \
			} \
			if (desc != "") { \
				printf "  %-30s %s\n", target, desc; \
			} \
			pending = ""; \
			next; \
		} \
		{ pending = "" } \
		' $(MAKEFILE_LIST)

dist: ## Package the bootstrap distribution
	$(MAVEN) package -Pdist -pl core/bootstrap -am

test: ## Run the Maven/JVM test suite with the dev profile
	$(MAVEN) test -Pdev -T 4

xml-schema: ## Generate and compile the XML schema model
	$(MAVEN) compile -Pdev,xml-schema -q -pl core/schema -am -DskipTests

skill-check: ## Validate one skill; set SKILL=.agents/skills/<skill>
	@if [ -z "$(SKILL)" ]; then \
		printf '%s\n' "Usage: make skill-check SKILL=.agents/skills/<skill>" >&2; \
		exit 2; \
	fi
	$(UV) run --with pyyaml make/validate-skill.py "$(SKILL)"

skills-check: ## Validate all repository skills
	@for skill in $(SKILL_DIRS); do \
		$(UV) run --with pyyaml make/validate-skill.py "$$skill" || exit $$?; \
	done

cargo-init: ## Install rustup when Cargo is unavailable
	@if [ ! -x "$(HOME)/.cargo/bin/cargo" ]; then \
		curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
			| sh -s -- -y --profile minimal --no-modify-path --default-toolchain none; \
	fi

rust-install: cargo-init ## Install the pinned Rust toolchain
	@$(HOME)/.cargo/bin/rustup run 1.97.0 rustc --version >/dev/null 2>&1 \
		|| $(HOME)/.cargo/bin/rustup toolchain install 1.97.0 --profile minimal

session-host: rust-install ## Build the session host release binary
	cd session-host && $(HOME)/.cargo/bin/cargo build --release

session-host-test: rust-install ## Run session-host Rust tests
	cd session-host && $(HOME)/.cargo/bin/cargo test --locked

SESSION_HOST_LINUX_HOST ?= root@gw.ntechs.ru
SESSION_HOST_LINUX_PORT ?= 30022
SESSION_HOST_LINUX_REMOTE_ROOT ?= /root/orion-session-host-linux
SESSION_HOST_LINUX_CC ?= /usr/bin/cc
SESSION_HOST_LINUX_AR ?= /usr/bin/ar
SESSION_HOST_LINUX_TOOLCHAIN_BIN ?= /root/.cargo/bin
SESSION_HOST_LINUX_CFLAGS ?=
SESSION_HOST_LINUX_SSH ?= ssh
SESSION_HOST_LINUX_SCP ?= scp

session-host-linux-test: ## Run session-host tests on the configured Linux host
	@SESSION_HOST_LINUX_HOST="$(SESSION_HOST_LINUX_HOST)" \
		SESSION_HOST_LINUX_PORT="$(SESSION_HOST_LINUX_PORT)" \
		SESSION_HOST_LINUX_REMOTE_ROOT="$(SESSION_HOST_LINUX_REMOTE_ROOT)" \
		SESSION_HOST_LINUX_CC="$(SESSION_HOST_LINUX_CC)" \
		SESSION_HOST_LINUX_AR="$(SESSION_HOST_LINUX_AR)" \
		SESSION_HOST_LINUX_TOOLCHAIN_BIN="$(SESSION_HOST_LINUX_TOOLCHAIN_BIN)" \
		SESSION_HOST_LINUX_CFLAGS="$(SESSION_HOST_LINUX_CFLAGS)" \
		SESSION_HOST_LINUX_SSH="$(SESSION_HOST_LINUX_SSH)" \
		SESSION_HOST_LINUX_SCP="$(SESSION_HOST_LINUX_SCP)" \
		sh make/session-host-linux-test.sh

run-test: ## Run one focused Maven test; set MODULE and TEST
	@if [ "$(words $(RUN_TEST_POSITIONAL_ARGUMENTS))" -eq 0 ]; then \
		if [ -z '$(strip $(value MODULE))' ] || [ -z '$(strip $(value TEST))' ]; then \
			printf '%s\n' "$(RUN_TEST_NAMED_USAGE)" "$(RUN_TEST_POSITIONAL_USAGE)" >&2; \
			exit 2; \
		fi; \
	elif [ -n "$(RUN_TEST_POSITIONAL_CONFLICT)" ]; then \
		printf '%s\n' "$(RUN_TEST_CONFLICT_USAGE)" >&2; \
		exit 2; \
	elif [ "$(words $(RUN_TEST_POSITIONAL_ARGUMENTS))" -ne 2 ] \
			|| [ -n '$(strip $(value MODULE))' ] \
			|| [ -n '$(strip $(value TEST))' ]; then \
		printf '%s\n' "$(RUN_TEST_NAMED_USAGE)" "$(RUN_TEST_POSITIONAL_USAGE)" >&2; \
		exit 2; \
	fi
	$(MAVEN) test -Pdev -T 4 -q -pl '$(RUN_TEST_MODULE)' -am \
		-Dtest='$(RUN_TEST_LOCATOR)' \
		-Dsurefire.failIfNoSpecifiedTests=false

test-jfr: ## Run Maven tests with JFR analytics
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

test-jfr-report: ## Generate a report from existing JFR analytics
	$(MAVEN) -q -pl tests/test-duration-recorder -am -DskipTests compile
	$(MAVEN) -q -pl tests/test-duration-recorder \
		org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
		-Dexec.mainClass=$(TEST_ANALYTICS_MAIN) \
		-Dexec.args="$(TEST_ANALYTICS_REPORT_ARGS)"

include make/server.mk
