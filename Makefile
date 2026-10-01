MAVEN ?= mvn
UV ?= uv
INTEGRATION_TEST_ARGS ?=
TEST_ANALYTICS_RUN_ID ?= $(shell date -u +%Y%m%dT%H%M%SZ)
TEST_ANALYTICS_ROOT ?= $(CURDIR)/target/test-analytics
TEST_ANALYTICS_DIR ?= $(TEST_ANALYTICS_ROOT)/$(TEST_ANALYTICS_RUN_ID)
TEST_ANALYTICS_TOP ?= 50
TEST_ANALYTICS_REPORT_ARGS ?=
TEST_ANALYTICS_MAIN = pro.deta.orion.test.duration.TestAnalyticsReport
TEST_JFR_MAVEN_ARGS ?=
SKILL_DIRS := $(sort $(dir $(wildcard .agents/skills/*/SKILL.md)))
ORION_MAVEN_LOCK_RUN = $(if $(filter Darwin,$(shell uname -s)),lockf -k,flock) \
	'$(shell git rev-parse --path-format=absolute --git-common-dir)/orion-maven.lock'

.DEFAULT_GOAL := help

.PHONY: help dist clean test test-all integration-test test-jfr test-jfr-report xml-schema dependency-audit \
	dependency-minimize \
	skill-check skills-check docker-exec build-processes \
	cargo-init rust-install rust-maven-plugin-install session-host session-host-test session-host-linux-test

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

docker-exec: ## Run CMD in orion-external-services; e.g. make docker-exec CMD='ps -ef'
	docker exec orion-external-services $(CMD)

build-processes: ## Show running Maven builds and Make test processes
	@ps -axo pid,ppid,etime,state,command | \
		rg '^[[:space:]]*PID|[o]rg[.]codehaus[.]plexus.*[.]Launcher|[m]ake test|[m]vn .*clean'

dist: ## Package the bootstrap distribution
	$(MAVEN) package -Pdist -pl core/bootstrap -am

clean: ## Clean Maven outputs and this repository's build cache
	$(ORION_MAVEN_LOCK_RUN) $(MAVEN) clean -Pdev,clean-build-cache -T 4 -q

test: ## Run package with unit tests; MODULE+TEST select tests, LOG sets a log file
test-all: ## Run verify with unit and integration tests, including the Git matrix; supports MODULE+TEST and LOG
test test-all:
	@if { [ -n '$(strip $(value MODULE))' ] && [ -z '$(strip $(value TEST))' ]; } \
		|| { [ -z '$(strip $(value MODULE))' ] && [ -n '$(strip $(value TEST))' ]; }; then \
		echo 'Set both MODULE and TEST for focused tests.' >&2; exit 2; \
	fi
	$(ORION_MAVEN_LOCK_RUN) \
	$(MAVEN) $(if $(filter test-all,$@),verify,package) -Pdev -T 4 -q \
		$(if $(strip $(value MODULE)),-pl '$(value MODULE)' -am \
		$(if $(filter test-all,$@),-Dit.test='$(value TEST)' -Dfailsafe.failIfNoSpecifiedTests=false,\
		-Dtest='$(value TEST)' -Dsurefire.failIfNoSpecifiedTests=false)) \
		$(if $(strip $(value LOG)),-l '$(value LOG)')

dependency-audit: ## Report explicit Maven dependencies that also have transitive paths
	$(MAVEN) org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree -Pdev -T 4 -q \
		-DoutputType=json -DoutputFile=target/dependency-audit.json -Dverbose=true
	python3 build-tools/orion-dependency-cleanup-audit.py

## Propose moving Maven dependencies to their actual consumers; set APPLY=1 to write the patch
dependency-minimize: dependency-audit
	$(MAVEN) org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom \
		-Pdev -q -Doutput=target/dependency-effective-pom.xml
	$(MAVEN) package org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath \
		-Pdev -T 4 -q -DskipTests -Dmdep.outputFile=target/dependency-classpath.txt
	python3 -B build-tools/orion-dependency-minimize.py $(if $(filter 1,$(APPLY)),--apply)

integration-test: ## Start external services and run integration tests; show the browser URL first
	@printf '%s\n' 'Browser (noVNC): http://localhost:6080/vnc.html?autoconnect=1'
	$(MAVEN) verify -Pdev,external-services -T 4 -pl tests/integration-test -am $(INTEGRATION_TEST_ARGS)

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

rust-maven-plugin-install: ## Install Rust Maven Plugin 0.1.0 locally from its tag without changing the checkout
	@set -eu; \
		temporary=$$(mktemp -d "$${TMPDIR:-/tmp}/orion-rust-maven-plugin.XXXXXX"); \
		trap 'rm -rf "$$temporary"' EXIT; \
		trap 'exit 129' HUP; \
		trap 'exit 130' INT; \
		trap 'exit 143' TERM; \
		git archive --format=tar --output="$$temporary/source.tar" rust-maven-plugin-0.1.0 \
			build-tools/rust-maven-plugin; \
		tar -xf "$$temporary/source.tar" -C "$$temporary"; \
		$(MAVEN) install -f "$$temporary/build-tools/rust-maven-plugin/pom.xml"

rust-install: cargo-init ## Install the pinned Rust toolchain
	@cd agent/session-host && $(HOME)/.cargo/bin/rustc --version

session-host: rust-install ## Build the session host release binary
	cd agent/session-host && $(HOME)/.cargo/bin/cargo build --release

session-host-test: rust-install ## Run session-host Rust tests
	cd agent/session-host && $(HOME)/.cargo/bin/cargo test --locked

COMMAND ?= $(SHELL)

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

test-jfr: ## Run Maven tests with JFR analytics
	@mkdir -p "$(TEST_ANALYTICS_DIR)/jfr"
	@status=0; \
	$(MAVEN) package -Pdev,test-jfr -T 4 -fae \
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
