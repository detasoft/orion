# Module review: tests/test-support

## 1. Pipe scenario participant failures are lost

- **Problem and trigger.** A client assertion or unchecked failure terminates the background thread; client IOException and server Exception are only logged. TERMINATED is treated as success. A wrong response of the same length in the existing PingPongStreamTest raises AssertionError without reaching JUnit; the transcript round trip does not establish response correctness.
- **Sources and owners.** [Client catch](src/main/java/pro/deta/orion/util/stream/IOTestStreamUtils.java#L45), [server catch/join](src/main/java/pro/deta/orion/util/stream/IOTestStreamUtils.java#L53), [outcome](src/main/java/pro/deta/orion/util/stream/IOTestStreamUtils.java#L64), the real [assertion](src/test/java/pro/deta/orion/util/stream/PingPongStreamTest.java#L37), [caller/round trip](src/test/java/pro/deta/orion/util/stream/PingPongStreamTest.java#L41), and [transcript owner](../../core/common/src/main/java/pro/deta/orion/util/stream/RecordingStandardStreams.java#L117).
- **Documented behavior.** The helper's class comment describes a client/server scenario; the test requires a Hello response. No permission to ignore callback failures was found.
- **Contract.** Any participant failure must fail the calling test. TERMINATED does not mean success. Preserve one worker and transcript recording.
- **Minimal repair.** Propagate worker IOException, RuntimeException, and AssertionError to the caller after join, and propagate server failures. Close the pipe endpoints on failure so the other participant can finish. Keep the existing thread boundary; test server IOException and client AssertionError, including their causes and cleanup.
- **Alternatives and consequences.** Logging and thread-state checks do not preserve failures; shared SoftAssertions do not catch IO or unchecked failures. FutureTask on the existing worker or a small local result holder is sufficient; no new executor, service, or thread per I/O call is needed.
- **Confidence.** High from code and the real assertion caller; runtime reproduction was not run. Some server failures may already fail the thread-state check if the client remains blocked.
- **Importance / ease.** High importance: tests can pass incorrectly. Medium ease: propagation and cleanup need verification.

## 2. Transcript replay can skip response comparison

- **Problem and trigger.** testPingPongStream2 passes null SoftAssertions. AssertiveIOClient compares a response only with non-null assertions, so wrong bytes of the same length pass. Initial EOF also skips the expected server chunk before comparison, even when assertions are provided.
- **Sources and owners.** [Comparison](src/main/java/pro/deta/orion/util/stream/AssertiveIOClient.java#L57), [read and initial EOF](src/main/java/pro/deta/orion/util/stream/AssertiveIOClient.java#L40), and the [replay scenario passing null](src/test/java/pro/deta/orion/util/stream/PingPongStreamTest.java#L48). The direct [EOF regression](src/test/java/pro/deta/orion/util/stream/AssertiveIOClientTest.java#L20) supplies assertions and covers a truncated response after bytes have arrived; it does not fix the scenario's nullable comparison or initial EOF policy.
- **Documented behavior.** The class comment promises comparison of server chunks with recorded bytes and executable assertions.
- **Contract.** Replay must assert the expected response, including when no bytes arrive. Preserve valid transcripts and send/receive order.
- **Minimal repair.** Remove nullable comparison, always assert the received bytes, and update the scenario caller; first ensure failure propagation from finding #1. Compare an empty response on initial EOF instead of skipping it. Test mismatches and both empty and truncated responses through the scenario boundary.
- **Alternatives and consequences.** Non-null SoftAssertions with mandatory assertAll repairs the caller but retains an optional oracle. A hard assertion without finding #1 would be lost in the worker. Changing comparison policy is separate from correctly terminating reads at EOF.
- **Confidence.** High from the current caller and conditional comparison. The direct regression verifies partial EOF termination and comparison with non-null assertions; runtime reproduction of the remaining scenario failure was not run.
- **Importance / ease.** Medium importance: the current replay scenario does not verify its response. High ease after finding #1.
