# make dist — ошибки

Полный лог: `/tmp/orion-task21-dist.log`. Ниже строки ERROR и итог сборки.

```text
22:42:36 [ThreadedStreamConsumer] [ERROR] Tests run: 16, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 15.33 s <<< FAILURE! -- in pro.deta.orion.agent.server.auth.SessionCommandServiceTest
22:42:36 [ThreadedStreamConsumer] [ERROR] pro.deta.orion.agent.server.journal.SegmentCompressionTest.finalContentVerificationDoesNotHoldTheSessionLock -- Time elapsed: 10.40 s <<< FAILURE!
22:42:56 [main] [ERROR] Failures:
22:42:56 [main] [ERROR]   SegmentCompressionTest.finalContentVerificationDoesNotHoldTheSessionLock:474
22:42:56 [main] [ERROR] Tests run: 307, Failures: 1, Errors: 0, Skipped: 0
22:42:56 [main] [INFO] agent-session-server ............................... FAILURE [01:15 min]
22:42:56 [main] [INFO] BUILD FAILURE
22:42:56 [main] [INFO] Total time:  02:55 min
22:42:56 [main] [ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.3.0:test (default-test) on project agent-session-server: There are test failures.
22:42:56 [main] [ERROR]
22:42:56 [main] [ERROR] Please refer to /private/tmp/orion-task21-20261028/agent/agent-session-server/target/surefire-reports for the individual test results.
22:42:56 [main] [ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
22:42:56 [main] [ERROR] -> [Help 1]
22:42:56 [main] [ERROR]
22:42:56 [main] [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
22:42:56 [main] [ERROR] Re-run Maven using the -X switch to enable full debug logging.
22:42:56 [main] [ERROR]
22:42:56 [main] [ERROR] For more information about the errors and possible solutions, please read the following articles:
22:42:56 [main] [ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
22:42:56 [main] [ERROR]
22:42:56 [main] [ERROR] After correcting the problems, you can resume the build with the command
22:42:56 [main] [ERROR]   mvn <args> -rf :agent-session-server
```
