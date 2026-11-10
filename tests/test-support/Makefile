# Read-only test-history queries. These goals compile only the two JDK-only analyzer sources.
TEST_ANALYSIS_ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../..)
N ?= 1
BY ?= module
RUN ?=
BASE ?=
NAME ?=
STATUS ?=
TOP ?= 20
MIN ?= 0
AT ?=
DATA ?= $(TEST_ANALYSIS_ROOT)/.test-durations/runs
JAVA ?= java
JAVAC ?= javac
TEST_ANALYSIS_SOURCE := $(TEST_ANALYSIS_ROOT)/tests/test-duration-recorder/src/main/java/pro/deta/orion/test/duration
TEST_ANALYSIS_CLASSES := $(TEST_ANALYSIS_ROOT)/target/test-analysis
TEST_ANALYSIS_CLASS := $(TEST_ANALYSIS_CLASSES)/pro/deta/orion/test/duration/TestRunAnalysis.class

export ORION_TEST_ANALYSIS_N := $(N)
export ORION_TEST_ANALYSIS_BY := $(BY)
export ORION_TEST_ANALYSIS_RUN := $(RUN)
export ORION_TEST_ANALYSIS_BASE := $(BASE)
export ORION_TEST_ANALYSIS_NAME := $(NAME)
export ORION_TEST_ANALYSIS_MODULE := $(MODULE)
export ORION_TEST_ANALYSIS_STATUS := $(STATUS)
export ORION_TEST_ANALYSIS_TOP := $(TOP)
export ORION_TEST_ANALYSIS_MIN := $(MIN)
export ORION_TEST_ANALYSIS_AT := $(AT)
export ORION_TEST_ANALYSIS_DATA := $(DATA)

.PHONY: test-runs test-summary test-find test-concurrency test-compare

test-runs: ## List recent recorded runs; N=10 selects the last ten
test-summary: ## Aggregate test history; N=10 BY=module|class|method
test-find: ## Find recorded tests; NAME=... MODULE=... STATUS=FAILED TOP=20
test-concurrency: ## Analyze overlapping tests; RUN=... AT=<ISO-instant> is optional
test-compare: ## Compare two runs; defaults to the latest two, or BASE=... RUN=...
test-runs test-summary test-find test-concurrency test-compare: $(TEST_ANALYSIS_CLASS)
	@$(JAVA) -cp "$(TEST_ANALYSIS_CLASSES)" pro.deta.orion.test.duration.TestRunAnalysis $(patsubst test-%,%,$@)

$(TEST_ANALYSIS_CLASS): $(TEST_ANALYSIS_SOURCE)/TestRunAnalysis.java $(TEST_ANALYSIS_SOURCE)/TestDurationData.java
	@mkdir -p "$(TEST_ANALYSIS_CLASSES)"
	@$(JAVAC) -d "$(TEST_ANALYSIS_CLASSES)" $^
