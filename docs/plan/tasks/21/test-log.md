# test — Maven log

```text
Waiting for the frontend build lock...

added 130 packages in 3s

> orion-admin-ui@0.1.0 build
> vite build --outDir /private/tmp/orion-task21-20261028/net/frontend/ui/target/classes/META-INF/orion/frontend

vite v8.2.2 building client environment for production...
transforming...
✓ 70 modules transformed.
rendering chunks...
computing gzip size...
target/classes/META-INF/orion/frontend/index.html                                     0.52 kB │ gzip:  0.30 kB
target/classes/META-INF/orion/frontend/assets/OrganizationInvitations-B38ktWVB.css    0.31 kB │ gzip:  0.19 kB
target/classes/META-INF/orion/frontend/assets/OrganizationOidc-SbtAM3ZS.css           0.31 kB │ gzip:  0.19 kB
target/classes/META-INF/orion/frontend/assets/OrganizationSignIn-a2qRA3cW.css         0.32 kB │ gzip:  0.19 kB
target/classes/META-INF/orion/frontend/assets/SystemOidcBindings-bN_owTR0.css         0.44 kB │ gzip:  0.22 kB
target/classes/META-INF/orion/frontend/assets/RecurringTasks-BzJYDtON.css             0.59 kB │ gzip:  0.32 kB
target/classes/META-INF/orion/frontend/assets/ScopedLogs-DFUhVokU.css                 0.62 kB │ gzip:  0.31 kB
target/classes/META-INF/orion/frontend/assets/KeyMaterial-BEnZoRvZ.css                0.76 kB │ gzip:  0.32 kB
target/classes/META-INF/orion/frontend/assets/PendingDecisions-B6a-OVBj.css           0.83 kB │ gzip:  0.35 kB
target/classes/META-INF/orion/frontend/assets/RemoteAliases-B-ThLHck.css              1.29 kB │ gzip:  0.46 kB
target/classes/META-INF/orion/frontend/assets/SessionTerminal-BDaKMtsc.css            4.36 kB │ gzip:  1.16 kB
target/classes/META-INF/orion/frontend/assets/index-xjaC8wia.css                     20.72 kB │ gzip:  5.36 kB
target/classes/META-INF/orion/frontend/assets/SystemOidcBindings-Cwo7JVXN.js          3.21 kB │ gzip:  1.46 kB
target/classes/META-INF/orion/frontend/assets/OrganizationSignIn-CZkiUQAa.js          3.77 kB │ gzip:  1.55 kB
target/classes/META-INF/orion/frontend/assets/PendingDecisions-DsAB0tLo.js            3.84 kB │ gzip:  1.62 kB
target/classes/META-INF/orion/frontend/assets/OrganizationInvitations-BjjopM93.js     4.32 kB │ gzip:  1.86 kB
target/classes/META-INF/orion/frontend/assets/ScopedLogs-C_WcI10r.js                  4.36 kB │ gzip:  2.03 kB
target/classes/META-INF/orion/frontend/assets/RecurringTasks-BMTTqzLW.js              4.68 kB │ gzip:  1.72 kB
target/classes/META-INF/orion/frontend/assets/OrganizationOidc-DsQx_aE5.js            5.22 kB │ gzip:  2.01 kB
target/classes/META-INF/orion/frontend/assets/RemoteAliases-1ffOBfjP.js               8.46 kB │ gzip:  3.23 kB
target/classes/META-INF/orion/frontend/assets/KeyMaterial-CJE8LdXv.js                12.17 kB │ gzip:  4.26 kB
target/classes/META-INF/orion/frontend/assets/index-CC_xBT1g.js                     118.98 kB │ gzip: 42.93 kB
target/classes/META-INF/orion/frontend/assets/SessionTerminal-DSKgjA-X.js           380.52 kB │ gzip: 99.69 kB

✓ built in 1.50s
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::loadLibrary has been called by com.github.luben.zstd.util.Native$1 in an unnamed module (file:/Users/vi/.m2/repository/com/github/luben/zstd-jni/1.5.7-11/zstd-jni-1.5.7-11.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled

WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::loadLibrary has been called by com.github.luben.zstd.util.Native$1 in an unnamed module (file:/private/tmp/orion-task21-20261028/agent/agentd/target/agentd-1.0-SNAPSHOT.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled

22:35:33 [ThreadedStreamConsumer] [ERROR] Tests run: 14, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 58.65 s <<< FAILURE! -- in pro.deta.orion.agent.server.journal.SegmentCompressionTest
22:35:33 [ThreadedStreamConsumer] [ERROR] pro.deta.orion.agent.server.journal.SegmentCompressionTest.aNewCompressedSnapshotDoesNotKeepAnOldSourceLeaseAlive -- Time elapsed: 22.74 s <<< FAILURE!
org.opentest4j.AssertionFailedError:

Expecting value to be true but was false
	at pro.deta.orion.agent.server.journal.SegmentCompressionTest.aNewCompressedSnapshotDoesNotKeepAnOldSourceLeaseAlive(SegmentCompressionTest.java:965)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base/java.util.concurrent.RecursiveAction.exec(RecursiveAction.java:194)
	at java.base/java.util.concurrent.ForkJoinTask.doExec(ForkJoinTask.java:511)
	at java.base/java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(ForkJoinPool.java:1450)
	at java.base/java.util.concurrent.ForkJoinPool.runWorker(ForkJoinPool.java:2019)
	at java.base/java.util.concurrent.ForkJoinWorkerThread.run(ForkJoinWorkerThread.java:187)

22:35:33 [mvn-builder-agent-session-server] [ERROR] Failures:
22:35:33 [mvn-builder-agent-session-server] [ERROR]   SegmentCompressionTest.aNewCompressedSnapshotDoesNotKeepAnOldSourceLeaseAlive:965
Expecting value to be true but was false
22:35:33 [mvn-builder-agent-session-server] [ERROR] Tests run: 307, Failures: 1, Errors: 0, Skipped: 0
22:35:44 [ThreadedStreamConsumer] [ERROR] Tests run: 6, Failures: 1, Errors: 1, Skipped: 0, Time elapsed: 55.69 s <<< FAILURE! -- in pro.deta.orion.git.s3.S3GitStorageTest
22:35:44 [ThreadedStreamConsumer] [ERROR] pro.deta.orion.git.s3.S3GitStorageTest.failedMultipartUploadAbortsAndCloseReleasesTheHandle(boolean)[1] -- Time elapsed: 34.39 s <<< ERROR!
java.util.concurrent.TimeoutException: failedMultipartUploadAbortsAndCloseReleasesTheHandle(boolean) timed out after 30 seconds
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.ReferencePipeline$2$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.stream.ReferencePipeline$7$1FlatMap.accept(ReferencePipeline.java:293)
	at java.base/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1716)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.stream.ReferencePipeline$7$1FlatMap.accept(ReferencePipeline.java:293)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1716)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.stream.ReferencePipeline$7$1FlatMap.accept(ReferencePipeline.java:293)
	at java.base/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1716)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)

22:35:44 [ThreadedStreamConsumer] [ERROR] pro.deta.orion.git.s3.S3GitStorageTest.failedMultipartUploadAbortsAndCloseReleasesTheHandle(boolean)[2] -- Time elapsed: 20.61 s <<< FAILURE!
java.lang.AssertionError:

Expected size: 1 but was: 2 in:
[software.amazon.awssdk.core.exception.SdkClientException: Request attempt 1 failure: HTTP request execution did not complete before the specified timeout configuration: 15000 millis
,
    software.amazon.awssdk.services.s3.model.S3Exception: (Service: S3, Status Code: 403, Request ID: null)
	at software.amazon.awssdk.protocols.xml.internal.unmarshall.AwsXmlPredicatedResponseHandler.handleErrorResponse(AwsXmlPredicatedResponseHandler.java:155)
	at software.amazon.awssdk.protocols.xml.internal.unmarshall.AwsXmlPredicatedResponseHandler.handleResponse(AwsXmlPredicatedResponseHandler.java:107)
	at software.amazon.awssdk.protocols.xml.internal.unmarshall.AwsXmlPredicatedResponseHandler.handle(AwsXmlPredicatedResponseHandler.java:84)
	...(175 remaining lines not displayed - this can be changed with Assertions.setMaxStackTraceElementsDisplayed)]
	at pro.deta.orion.git.s3.S3GitStorageTest.failedMultipartUploadAbortsAndCloseReleasesTheHandle(S3GitStorageTest.java:61)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.ReferencePipeline$2$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:186)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.stream.ReferencePipeline$7$1FlatMap.accept(ReferencePipeline.java:293)
	at java.base/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1716)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.stream.ReferencePipeline$7$1FlatMap.accept(ReferencePipeline.java:293)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:214)
	at java.base/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1716)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.stream.ReferencePipeline$7$1FlatMap.accept(ReferencePipeline.java:293)
	at java.base/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1716)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:570)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:560)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:153)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:176)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:265)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:632)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)

22:35:45 [mvn-builder-git-s3-storage] [ERROR] Failures:
22:35:45 [mvn-builder-git-s3-storage] [ERROR]   S3GitStorageTest.failedMultipartUploadAbortsAndCloseReleasesTheHandle:61
Expected size: 1 but was: 2 in:
[software.amazon.awssdk.core.exception.SdkClientException: Request attempt 1 failure: HTTP request execution did not complete before the specified timeout configuration: 15000 millis
,
    software.amazon.awssdk.services.s3.model.S3Exception: (Service: S3, Status Code: 403, Request ID: null)
	at software.amazon.awssdk.protocols.xml.internal.unmarshall.AwsXmlPredicatedResponseHandler.handleErrorResponse(AwsXmlPredicatedResponseHandler.java:155)
	at software.amazon.awssdk.protocols.xml.internal.unmarshall.AwsXmlPredicatedResponseHandler.handleResponse(AwsXmlPredicatedResponseHandler.java:107)
	at software.amazon.awssdk.protocols.xml.internal.unmarshall.AwsXmlPredicatedResponseHandler.handle(AwsXmlPredicatedResponseHandler.java:84)
	...(175 remaining lines not displayed - this can be changed with Assertions.setMaxStackTraceElementsDisplayed)]
22:35:45 [mvn-builder-git-s3-storage] [ERROR] Errors:
22:35:45 [mvn-builder-git-s3-storage] [ERROR]   S3GitStorageTest.failedMultipartUploadAbortsAndCloseReleasesTheHandle(boolean)[1] » Timeout failedMultipartUploadAbortsAndCloseReleasesTheHandle(boolean) timed out after 30 seconds
22:35:45 [mvn-builder-git-s3-storage] [ERROR] Tests run: 46, Failures: 1, Errors: 1, Skipped: 0
22:35:45 [main] [ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.3.0:test (default-test) on project agent-session-server: There are test failures.
22:35:45 [main] [ERROR]
22:35:45 [main] [ERROR] Please refer to /private/tmp/orion-task21-20261028/agent/agent-session-server/target/surefire-reports for the individual test results.
22:35:45 [main] [ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
22:35:45 [main] [ERROR] -> [Help 1]
22:35:45 [main] [ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.3.0:test (default-test) on project git-s3-storage: There are test failures.
22:35:45 [main] [ERROR]
22:35:45 [main] [ERROR] Please refer to /private/tmp/orion-task21-20261028/git/git-s3-storage/target/surefire-reports for the individual test results.
22:35:45 [main] [ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
22:35:45 [main] [ERROR] -> [Help 1]
22:35:45 [main] [ERROR]
22:35:45 [main] [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
22:35:45 [main] [ERROR] Re-run Maven using the -X switch to enable full debug logging.
22:35:45 [main] [ERROR]
22:35:45 [main] [ERROR] For more information about the errors and possible solutions, please read the following articles:
22:35:45 [main] [ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
22:35:45 [main] [ERROR]
22:35:45 [main] [ERROR] After correcting the problems, you can resume the build with the command
22:35:45 [main] [ERROR]   mvn <args> -rf :agent-session-server

```
