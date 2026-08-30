MAVEN ?= mvn
TEST_ANALYTICS_RUN_ID ?= $(shell date -u +%Y%m%dT%H%M%SZ)
TEST_ANALYTICS_ROOT ?= $(CURDIR)/target/test-analytics
TEST_ANALYTICS_DIR ?= $(TEST_ANALYTICS_ROOT)/$(TEST_ANALYTICS_RUN_ID)
TEST_ANALYTICS_TOP ?= 50
TEST_ANALYTICS_REPORT_ARGS ?=
TEST_ANALYTICS_MAIN = pro.deta.orion.test.duration.TestAnalyticsReport
TEST_JFR_MAVEN_ARGS ?=

.PHONY: test test-jfr test-jfr-report

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

include make/server.mk
