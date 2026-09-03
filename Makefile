MAVEN ?= mvn
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
RUN_TEST_RESERVED_GOALS = dist test run-test test-jfr test-jfr-report \
	run-server issue-token issue-token-raw ssh-state ssh-status list-repos \
	clone-repository clone-repo clone-http-repo admin-acl admin-acl-with-token \
	check-git-all check-jetty-git check-ssh-git check-ssh-git-clone check-ssh-git-push-create \
	session-host-build session-host-fixtures session-host-prepare session-host-test
RUN_TEST_POSITIONAL_ARGUMENTS :=
RUN_TEST_POSITIONAL_CONFLICT = $(filter $(RUN_TEST_RESERVED_GOALS),$(RUN_TEST_POSITIONAL_ARGUMENTS))
RUN_TEST_MODULE = $(value MODULE)
RUN_TEST_LOCATOR = $(value TEST)

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

.PHONY: dist test run-test test-jfr test-jfr-report

dist:
	$(MAVEN) package -Pdist -pl core/bootstrap -am

test:
	$(MAVEN) test -Pdev -T 4

run-test:
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

include make/server.mk
include session-host/Makefile
