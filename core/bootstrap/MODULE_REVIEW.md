# Module review: core/bootstrap

## 4. Background startup does not use bundled Java

- **Problem and trigger.** In the self-contained distribution, `bin/orion start` with no host Java installation and no `JAVA_CMD` override runs the manager using the bundled runtime, but the manager launches its child through `java` from PATH. If host Java is missing or too old, the service does not start.
- **Sources and owners.** The [distribution launcher](src/main/dist/bin/orion#L19) selects bundled Java without exporting it; [settingsFrom](src/main/java/pro/deta/orion/OrionServiceManager.java#L169) defaults to `java`; [commandFor](src/main/java/pro/deta/orion/OrionServiceManager.java#L118) uses that value. The [test](src/test/java/pro/deta/orion/OrionServiceManagerTest.java#L27) supplies settings manually.
- **Documented behavior.** The [README](../../README.md#L114) describes the distribution as self-contained and [lists the bundled runtime](../../README.md#L123).
- **Contract.** A bundled installation starts the service without a separate Java installation; an explicit `JAVA_CMD` override remains supported.
- **Smallest fix.** Without an override, derive the Java executable from the current runtime, `java.home/bin/java`; test default selection and packaged startup without host Java.
- **Alternatives and consequences.** Exporting the selected `JAVA_CMD` from the launcher is a smaller shell change, but direct jar startup would still select a runtime from PATH. Neither option requires new configuration.
- **Confidence.** High based on the two production paths; a packaged smoke test has not been run.
- **Importance / ease.** Medium importance: a documented deployment mode is broken. High ease: change a local default or export an existing value.
