# Module Review: `communication`

## 1. Endpoint equality disagrees with the session-map hash

**Problem.** Equal remote addresses with different local addresses compare equal but can have different
hashes. The engine registry can create separate engines for equal keys or share an engine across local endpoints.

**Sources.** [Equality/hash](src/main/java/pro/deta/orion/comm/common/DtlsSessionEndpoint.java#L19),
[registry and lookup](src/main/java/pro/deta/orion/comm/v3/OrionDTLSAsyncHandler.java#L25), and
[endpoint tests](src/test/java/pro/deta/orion/comm/handler/OrionDTLSSessionTest.java#L129).

**Documented behavior and contract.** No endpoint-identity specification was found. Both addresses enter
through the channel boundary. Equal keys must hash equally; remote-only versus pair identity needs a decision.

**Minimal repair.** Choose identity fields and derive both operations from them. Remove the custom-hash
constructor if ordinary fields suffice; all repository callers use the ordinary constructor. Test map lookup
and distinct local endpoints.

**Alternatives and consequences.** Pair identity preserves local separation; remote-only deliberately shares
engines across local endpoints. Constructor deletion changes internal Java API, with no established external
compatibility requirement. Neither option needs new state.

**Confidence.** High on the defect; intended identity remains uncertain.

**Priority signals.** Importance: medium, incorrect standalone DTLS association. Repair ease: high after the
identity decision. No default Orion runtime exposure was established.

## 2. ByteBuf indices are confused with NIO positions

**Problem.** For an ordinary buffer with reader index `r > 0`, the NIO position becomes `r + consumed`, then
the source reader index adds `r` again. This skips bytes or throws. Resetting a derived or pooled NIO view's
position/limit also discards its backing-memory coordinate system.

**Sources.** [Conversion](src/main/java/pro/deta/orion/comm/v3/OrionSSLEngine.java#L112),
[application input](src/main/java/pro/deta/orion/comm/DtlsApplication.java#L18), and
[Netty input](src/main/java/pro/deta/orion/comm/v3/netty/OrionV3DtlsChannelInboundHandler.java#L29).
Installed Netty 4.2.1.Final sources confirm the different view coordinates.

**Documented behavior and contract.** Inputs are `ByteBuf`; no unpooled, unsliced or zero-index restriction
was found. Consume the readable region and advance by actual consumed/produced byte counts.

**Minimal repair.** Use bounded NIO views, preserve their coordinates and update ByteBuf indices through
`SSLEngineResult.bytesConsumed()`/`bytesProduced()`. Cover consumed prefixes, derived and pooled buffers.

**Alternatives and consequences.** Copying into a fresh zero-based buffer works but adds allocation.
Restricting input forms narrows the current API. The local repair preserves ownership and wire behavior.

**Confidence.** High from source and Netty implementation; no fresh runtime reproduction. A zero-index
unpooled slice itself is valid, so the issue does not affect every slice.

**Priority signals.** Importance: high, valid bytes fail or the wrong region is processed. Repair ease: high,
local accounting with meaningful buffer cases.

## 3. Output-buffer overflow retries with stale positions

**Problem.** Sustained traffic exhausts remaining reusable output space. Overflow compacts the buffer but
retains the old writer index and NIO view. A reset can make the next limit exceed capacity; no reclaimed
space can instead produce an endless unchanged retry on the sole processing worker.

**Sources.** [Allocation](src/main/java/pro/deta/orion/comm/v3/OrionSSLEngine.java#L35),
[retained slices](src/main/java/pro/deta/orion/comm/v3/OrionSSLEngine.java#L59),
[retry loop](src/main/java/pro/deta/orion/comm/v3/OrionSSLEngine.java#L112), and
[executor](src/main/java/pro/deta/orion/comm/v3/OrionDTLSAsyncHandler.java#L36).

**Documented behavior and contract.** No lifetime traffic cap was found. Sessions must continue after earlier
output is consumed; handed-out retained slices must stay valid. Initial buffer size is incidental.

**Minimal repair.** Recompute indices/views after reclamation or growth and ensure each retry progresses.
Choose buffer reuse through the existing owner while preserving asynchronous slices. Cover sustained traffic
and an outstanding retained slice.

**Alternatives and consequences.** Fresh bounded output per operation simplifies ownership at allocation
cost. Larger initial capacity only postpones failure. Reusing retained backing storage can corrupt output.

**Confidence.** High from the loop and installed Netty compaction implementation; the traffic threshold
depends on negotiated sizes. No runtime test was run.

**Priority signals.** Importance: high, sustained-session failure or worker spin. Repair ease: medium,
retention and progress require behavioral coverage.

## 4. Application writes are released after one partial wrap

**Problem.** `internalWrap` releases plaintext after one successful wrap without checking remaining bytes.
The 1200-byte maximum DTLS packet permits partial consumption, so larger accepted writes lose their suffix.

**Sources.** [Packet limit](src/main/java/pro/deta/orion/comm/v3/OrionDTLSAsyncHandler.java#L65),
[application queue](src/main/java/pro/deta/orion/comm/v3/OrionDTLSAsyncHandler.java#L178),
[release](src/main/java/pro/deta/orion/comm/v3/OrionDTLSAsyncHandler.java#L252),
[tiny test messages](src/test/java/pro/deta/orion/comm/app/HelloDtlsApplication.java#L37), and
[nominal MTU test](src/test/java/pro/deta/orion/comm/handler/OrionDTLSSessionTest.java#L36), which awaits and
asserts no result. Installed JDK 21 `DTLSOutputRecord` limits each encode to the packet fragment.

**Documented behavior and contract.** No write-size restriction or truncation permission was found.
Accepted bytes must not silently disappear. Splitting or explicit rejection is a behavior choice.

**Minimal repair.** Consume all accepted plaintext while honoring handshake and progress transitions,
releasing once after completion/failure. Fix finding 2 first for repeated wrapping of the same buffer.
Cover boundary sizes and a multi-record payload end to end.

**Alternatives and consequences.** A documented limit is viable only with explicit rejection agreed by
callers. Silent truncation is invalid for either contract. No new protocol is needed for consumption repair.

**Confidence.** High from current ownership and JDK fragmentation; no fresh runtime reproduction.

**Priority signals.** Importance: high, silent data loss. Repair ease: medium, consumption/handshake loop.

## 5. DTLS resources have no terminal owner operation

**Problem.** Convenience constructors create private scheduled executors with no shutdown API; default
workers are non-daemon. Terminal engines remain registered and retain base buffers and queued packets.
The real OpenSSL consumer closes Netty groups but cannot close the handler.

**Sources.** [Construction](src/main/java/pro/deta/orion/comm/v3/OrionDTLSAsyncHandler.java#L25),
[closed processing](src/main/java/pro/deta/orion/comm/v3/OrionDTLSAsyncHandler.java#L268),
[owned buffers/queues](src/main/java/pro/deta/orion/comm/v3/OrionSSLEngine.java#L22),
[adapter](src/main/java/pro/deta/orion/comm/v3/netty/OrionV3DtlsChannelInboundHandler.java), and
[OpenSSL consumer](../../tests/integration-test/src/integration-test/java/pro/deta/orion/comm/handler/OrionDTLSOpenSSLIT.java#L37).

**Documented behavior and contract.** `afterEndpointClosed` exposes termination, but teardown ownership is
unspecified. Owned resources require a release boundary; injected workers may remain caller-owned.

**Minimal repair.** Place terminal cleanup on existing handler/engine owners: remove sessions, release owned
queues/buffers and stop internally owned workers. Decide adapter/application teardown ownership and update
every real consumer. Verify close races, queued data and caller-owned executor preservation.

**Alternatives and consequences.** Removing allocating constructors and requiring injected workers removes
hidden executor ownership, but still requires session cleanup. Daemon workers alone do not release resources.
Lifecycle coordination must preserve active slices and cannot race unsafely with processing.

**Confidence.** High on missing cleanup; lifetime ownership needs a decision.

**Priority signals.** Importance: high for repeated standalone use and deterministic shutdown. Repair ease:
medium/low, lifecycle crosses application and adapter. No default-runtime exposure was established.

## 7. Typed DTLS metadata has no consumer

**Problem.** `TypedMap` and its sole `DtlsCommonKeys` constant are referenced only within that unused model.

**Sources.** [Map](src/main/java/pro/deta/orion/comm/common/TypedMap.java) and
[key](src/main/java/pro/deta/orion/comm/common/DtlsCommonKeys.java). No real repository, reflective,
serialization or service-loader consumer was found.

**Documented behavior and contract.** No requirement for typed endpoint metadata was found. Real packet,
endpoint and handshake contracts do not use it.

**Minimal repair.** Delete these two types without adding replacement storage.

**Alternatives and consequences.** Retaining hypothetical metadata preserves unnecessary API. Deletion
changes unused public Java types, with no established external compatibility requirement or wire effect.

**Confidence.** High for repository use; undocumented external consumers are unknown.

**Priority signals.** Importance: low, unused concept. Repair ease: very high, two files.

## 8. A test indication callback is never invoked

**Problem.** `onNeedUnwrap` is assigned only by the test bridge and is never invoked, so its promised
indication cannot occur.

**Sources.** [Field](src/main/java/pro/deta/orion/comm/v3/OrionDTLSAsyncHandler.java#L32) and
[only setter consumer](src/test/java/pro/deta/orion/comm/DtlsHandlerVirtualNetworkBridge.java#L32).

**Documented behavior and contract.** The field comment promises a test indication; no implementation or
real requirement was found. Actual packet exchange and handshake coverage must remain.

**Minimal repair.** Delete the inert field/setter and test assignment.

**Alternatives and consequences.** Adding callback invocations invents behavior without a verified consumer.
Deletion changes an unused public setter but no packet or callback delivery currently occurs.

**Confidence.** High from all references and control flow; no external commitment was established.

**Priority signals.** Importance: low, misleading extension point. Repair ease: high, mechanical deletion.

## 9. Debug output slices add an equivalent allocation path

**Problem.** Debug mode wraps a retained slice only to override `release` with `super.release`, without any
observable change. Normal mode returns the same retained slice directly.

**Sources.** [Slice creation](src/main/java/pro/deta/orion/comm/v3/OrionSSLEngine.java#L59) and
[delivery](src/main/java/pro/deta/orion/comm/v3/OrionDTLSAsyncHandler.java#L264).

**Documented behavior and contract.** No debug-wrapper requirement was found. Reference counting and
readable output ownership remain required; wrapper identity has no actual consumer.

**Minimal repair.** Return the existing retained slice directly and remove unused imports.

**Alternatives and consequences.** Retaining both paths adds allocation and policy branching. Deletion
preserves retention/release behavior; use existing behavior coverage, not reflection assertions.

**Confidence.** High from the forwarding override and callers.

**Priority signals.** Importance: low, redundant path. Repair ease: very high, local simplification.

## 10. An unreachable overflow helper remains

**Problem.** Private `throwIfOverflow` has no calls; actual overflow processing is in `commandInternal`.

**Sources.** [Helper](src/main/java/pro/deta/orion/comm/v3/OrionSSLEngine.java#L143) and
[live path](src/main/java/pro/deta/orion/comm/v3/OrionSSLEngine.java#L112).

**Documented behavior and contract.** No separate helper requirement was found. Finding 3 describes the
live overflow defect; deleting this unused method neither repairs nor changes that behavior.

**Minimal repair.** Delete only this method. Preserve the exception utility used elsewhere.

**Alternatives and consequences.** Calling it in place of the live loop would change behavior and belongs
to finding 3's repair. Local deletion changes no observable contract.

**Confidence.** High from private visibility and complete caller search.

**Priority signals.** Importance: low, dead code. Repair ease: very high, one deletion.

## 11. Session tracking retains histories nobody can consume

**Problem.** Endpoint history is initialized once and never updated; command history retains ten timestamped
results while production reads only the latest. Two ring utility types expose index CAS operations that have
only their own test callers.

**Sources.** [Fields and initialization](src/main/java/pro/deta/orion/comm/v3/OrionSSLEngine.java#L31),
[latest reads](src/main/java/pro/deta/orion/comm/v3/OrionSSLEngine.java#L85),
[diagnostics](src/main/java/pro/deta/orion/comm/v3/OrionSSLEngine.java#L172),
[ring](src/main/java/pro/deta/orion/comm/util/RecentValueBuffer.java),
[timestamp wrapper](src/main/java/pro/deta/orion/comm/util/RecentTimestampedValueBuffer.java), and
[index tests](src/test/java/pro/deta/orion/comm/v3/RecentValueBufferTest.java#L17).

**Documented behavior and contract.** No history retrieval or concurrent multi-producer history requirement
was found. Preserve the current endpoint, latest command result and status diagnostics, including safe
publication to unsynchronized status readers. Historical retention and CAS API are incidental.

**Minimal repair.** Keep the immutable endpoint directly and one safely published latest timestamped result.
Migrate owner behavior and delete ring utilities and tests specific to their unused implementation.

**Alternatives and consequences.** A retained diagnostic history needs an actual retrieval consumer, which
is absent. Public utility deletion affects internal Java references; preserve meaningful handshake and status
coverage through the existing owner. Do not discard synchronization without checking readers.

**Confidence.** High on repository consumers; external API commitments are not established.

**Priority signals.** Importance: medium structurally, removes retained state, atomics and support types.
Repair ease: medium, publication and latest-result behavior need verification.

## 12. The packet-reordering test leaves an immortal simulator thread

**Problem.** Each `fakeClientServerReversePairsPackets` run starts a non-daemon infinite loop with no retained
handle or `finally` cleanup. It retains both handlers and wakes/logs after test completion or failure.

**Sources.** [Thread and loop](src/test/java/pro/deta/orion/comm/handler/OrionDTLSSessionTest.java#L123) and
[completion](src/test/java/pro/deta/orion/comm/handler/OrionDTLSSessionTest.java#L153).

**Documented behavior and contract.** Reordered packet exchange is the test purpose. Activity after
completion is unnecessary; the test must release its own resources.

**Minimal repair.** Retain the thread handle, support interruption/stop, stop and join in `finally`, and
release undelivered queued buffers. Handler cleanup follows finding 5.

**Alternatives and consequences.** A bounded test-owned delivery loop is viable. Making the thread daemon
alone still leaks during the test JVM's lifetime. Preserve the existing reordering scenario.

**Confidence.** High from the unconditional loop; no new test run was performed.

**Priority signals.** Importance: medium, repeat-run accumulation and standalone JVM termination.
Repair ease: high for simulator cleanup, with broader handler teardown tracked separately.
