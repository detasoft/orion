MAVEN ?= mvn

.PHONY: run-server

run-server:
	$(MAVEN) -pl core/bootstrap -am -Prun-server process-classes
