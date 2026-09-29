# Module review: tests/test-support

## 2. Transcript replay can skip response comparison

- **Problem and trigger.** testPingPongStream2 passes null SoftAssertions. AssertiveIOClient compares a response only with non-null assertions, so wrong bytes of the same length pass. Initial EOF also skips the expected server chunk before comparison, even when assertions are provided.
- **Sources and owners.** [Comparison](src/main/java/pro/deta/orion/util/stream/AssertiveIOClient.java#L57), [read and initial EOF](src/main/java/pro/deta/orion/util/stream/AssertiveIOClient.java#L40), and the [replay scenario passing null](src/test/java/pro/deta/orion/util/stream/PingPongStreamTest.java#L48). The direct [EOF regression](src/test/java/pro/deta/orion/util/stream/AssertiveIOClientTest.java#L20) supplies assertions and covers a truncated response after bytes have arrived; it does not fix the scenario's nullable comparison or initial EOF policy.
- **Documented behavior.** The class comment promises comparison of server chunks with recorded bytes and executable assertions.
- **Contract.** Replay must assert the expected response, including when no bytes arrive. Preserve valid transcripts and send/receive order.
- **Minimal repair.** Remove nullable comparison, always assert the received bytes, and update the scenario caller. Compare an empty response on initial EOF instead of skipping it. Test mismatches and both empty and truncated responses through the scenario boundary.
- **Alternatives and consequences.** Non-null SoftAssertions with mandatory assertAll repairs the caller but retains an optional oracle. The existing scenario boundary now propagates worker assertions to the calling test, so direct assertions need no new result mechanism. Changing comparison policy is separate from correctly terminating reads at EOF.
- **Confidence.** High from the current caller and conditional comparison. The direct regression verifies partial EOF termination and comparison with non-null assertions; runtime reproduction of the remaining scenario failure was not run.
- **Importance / ease.** Medium importance: the current replay scenario does not verify its response. High ease: comparison is local and scenario failures already reach the calling test.
