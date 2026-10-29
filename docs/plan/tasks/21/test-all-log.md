# test-all — Maven log

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::loadLibrary has been called by com.github.luben.zstd.util.Native$1 in an unnamed module (file:/private/tmp/orion-task21-20261028/agent/agentd/target/agentd-1.0-SNAPSHOT.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled

22:38:31 [ThreadedStreamConsumer] [ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).
22:38:59 [ThreadedStreamConsumer] [ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 15.11 s <<< FAILURE! -- in pro.deta.orion.git.s3.S3NativeGitRepositoryProviderIT
22:38:59 [ThreadedStreamConsumer] [ERROR] pro.deta.orion.git.s3.S3NativeGitRepositoryProviderIT.listsAllPagesAndRejectsCorruptOrMisplacedMetadata -- Time elapsed: 11.23 s <<< FAILURE!
java.lang.AssertionError:

Expecting actual:
  Success[value=pro.deta.orion.git.nativestorage.NativeGitRepository@3c8fe8ad]
to be an instance of:
  pro.deta.orion.util.Result.Failure
but was instance of:
  pro.deta.orion.util.Result.Success
	at pro.deta.orion.git.s3.S3NativeGitRepositoryProviderIT.assertFailure(S3NativeGitRepositoryProviderIT.java:226)
	at pro.deta.orion.git.s3.S3NativeGitRepositoryProviderIT.listsAllPagesAndRejectsCorruptOrMisplacedMetadata(S3NativeGitRepositoryProviderIT.java:142)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)

22:38:59 [mvn-builder-git-s3-storage] [ERROR] Failures:
22:38:59 [mvn-builder-git-s3-storage] [ERROR]   S3NativeGitRepositoryProviderIT.listsAllPagesAndRejectsCorruptOrMisplacedMetadata:142->assertFailure:226
Expecting actual:
  Success[value=pro.deta.orion.git.nativestorage.NativeGitRepository@3c8fe8ad]
to be an instance of:
  pro.deta.orion.util.Result.Failure
but was instance of:
  pro.deta.orion.util.Result.Success
22:38:59 [mvn-builder-git-s3-storage] [ERROR] Tests run: 9, Failures: 1, Errors: 0, Skipped: 0
22:39:00 [main] [ERROR] Failed to execute goal org.apache.maven.plugins:maven-failsafe-plugin:3.3.0:verify (default) on project git-s3-storage: There are test failures.
22:39:00 [main] [ERROR]
22:39:00 [main] [ERROR] Please refer to /private/tmp/orion-task21-20261028/git/git-s3-storage/target/failsafe-reports for the individual test results.
22:39:00 [main] [ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
22:39:00 [main] [ERROR] -> [Help 1]
22:39:00 [main] [ERROR]
22:39:00 [main] [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
22:39:00 [main] [ERROR] Re-run Maven using the -X switch to enable full debug logging.
22:39:00 [main] [ERROR]
22:39:00 [main] [ERROR] For more information about the errors and possible solutions, please read the following articles:
22:39:00 [main] [ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
22:39:00 [main] [ERROR]
22:39:00 [main] [ERROR] After correcting the problems, you can resume the build with the command
22:39:00 [main] [ERROR]   mvn <args> -rf :git-s3-storage

```
