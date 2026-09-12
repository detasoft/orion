package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentMessageRecord;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionId;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EstablishedSessionCommandDeliveryTest {
    private static final SessionId SESSION_ID = new SessionId("session-1");
    private static final CommandId COMMAND_ID = new CommandId("command-1");
    private static final AgentProtocolCodec CODEC = new AgentProtocolCodec(AgentProtocolLimits.defaults());

    @Test
    void claimsBeforeDeliveringExactFutureExtendedEnvelopeWithServerSequence() throws Exception {
        AgentMessage.Resize message = new AgentMessage.Resize(COMMAND_ID, SESSION_ID, 80, 24, 17);
        byte[] canonical = CODEC.encode(message);
        byte[] encoded = Arrays.copyOf(canonical, canonical.length + 3);
        encoded[0] = (byte) 0x87;
        encoded[canonical.length] = 0x62;
        encoded[canonical.length + 1] = 'h';
        encoded[canonical.length + 2] = 'i';
        assertThat(CODEC.decode(encoded)).isEqualTo(message);
        List<byte[]> requests = new ArrayList<>();
        ControlTransport transport = (endpoint, request, deadline) -> {
            requests.add(request);
            int type = type(request);
            return new ControlTransport.Exchange.Response(NativeControlCodec.frame(
                    type == 9 ? 0x8005 : 0x8000, sequence(request), new byte[0]));
        };
        EstablishedSessionCommandDelivery delivery = delivery(readyRegistry(), transport);

        ControlResult result = delivery.deliver(new AgentMessageRecord(message, ProtocolBytes.copyOf(encoded)));

        assertThat(result).isEqualTo(new ControlResult.Received(17));
        assertThat(requests).hasSize(2);
        assertThat(type(requests.get(0))).isEqualTo(9);
        assertThat(type(requests.get(1))).isEqualTo(2);
        assertThat(sequence(requests.get(1))).isEqualTo(17);
        ByteBuffer nativePayload = ByteBuffer.wrap(requests.get(1)).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(Short.toUnsignedInt(nativePayload.getShort(32))).isEqualTo(1);
        int envelopeLength = nativePayload.getInt(36);
        assertThat(envelopeLength).isEqualTo(encoded.length);
        assertThat(Arrays.copyOfRange(requests.get(1), 40, 40 + envelopeLength)).containsExactly(encoded);
    }

    @Test
    void mapsEveryEstablishedEffectWithoutChangingItsSequence() throws Exception {
        List<Integer> types = new ArrayList<>();
        List<Long> sequences = new ArrayList<>();
        ControlTransport transport = (endpoint, request, deadline) -> {
            if (type(request) != 9) {
                types.add(type(request));
                sequences.add(sequence(request));
            }
            return new ControlTransport.Exchange.Response(NativeControlCodec.frame(
                    type(request) == 9 ? 0x8005 : 0x8000, sequence(request), new byte[0]));
        };
        EstablishedSessionCommandDelivery delivery = delivery(readyRegistry(), transport);
        List<AgentMessage> messages = List.of(
                new AgentMessage.Input(COMMAND_ID, SESSION_ID, UUID.randomUUID(),
                        ProtocolBytes.copyOf(new byte[]{1, 2}), 21),
                new AgentMessage.Signal(COMMAND_ID, SESSION_ID, AgentMessage.SignalKind.INTERRUPT, -1, 22),
                new AgentMessage.Terminate(COMMAND_ID, SESSION_ID,
                        AgentMessage.TerminationMode.GRACEFUL, 23));

        for (AgentMessage message : messages) {
            assertThat(delivery.deliver(record(message))).isInstanceOf(ControlResult.Received.class);
        }
        assertThat(types).containsExactly(1, 3, 4);
        assertThat(sequences).containsExactly(21L, 22L, 23L);
    }

    @Test
    void missingOrUnavailableSessionNeverOpensNativeControl() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        ControlTransport transport = (endpoint, request, deadline) -> {
            exchanges.incrementAndGet();
            throw new AssertionError("native control must not be called");
        };
        AgentMessage.Resize message = new AgentMessage.Resize(COMMAND_ID, SESSION_ID, 80, 24, 17);
        EstablishedSessionCommandDelivery missing = delivery(new SessionRegistry(), transport);
        SessionRegistry unavailableRegistry = new SessionRegistry();
        unavailableRegistry.replace(new DiscoverySnapshot(Map.of(
                SESSION_ID.value(), session(LocalSessionState.DEGRADED)), Map.of()));
        EstablishedSessionCommandDelivery unavailable = delivery(unavailableRegistry, transport);

        assertThat(missing.deliver(record(message))).isInstanceOf(ControlResult.Failed.class);
        assertThat(unavailable.deliver(record(message))).isInstanceOf(ControlResult.Failed.class);
        assertThat(exchanges).hasValue(0);
    }

    @Test
    void claimFailureAndAmbiguousOperationNeverTriggerARetry() throws Exception {
        AgentMessage.Resize message = new AgentMessage.Resize(COMMAND_ID, SESSION_ID, 80, 24, 17);
        AtomicInteger claimOnlyRequests = new AtomicInteger();
        ControlTransport claimFailure = (endpoint, request, deadline) -> {
            claimOnlyRequests.incrementAndGet();
            return new ControlTransport.Exchange.Failed(
                    ControlResult.FailureKind.CONNECTION, false, "unreachable");
        };
        EstablishedSessionCommandDelivery failedClaim = delivery(readyRegistry(), claimFailure);
        AtomicInteger ambiguousRequests = new AtomicInteger();
        ControlTransport ambiguousTransport = (endpoint, request, deadline) -> {
            ambiguousRequests.incrementAndGet();
            if (type(request) == 9) {
                return new ControlTransport.Exchange.Response(NativeControlCodec.frame(
                        0x8005, sequence(request), new byte[0]));
            }
            return new ControlTransport.Exchange.Failed(ControlResult.FailureKind.TIMEOUT, true, "timeout");
        };
        EstablishedSessionCommandDelivery ambiguous = delivery(readyRegistry(), ambiguousTransport);

        assertThat(failedClaim.deliver(record(message)))
                .isEqualTo(new ControlResult.Failed(OptionalLong.of(17),
                        ControlResult.FailureKind.CONNECTION, "server control claim failed: unreachable"));
        assertThat(claimOnlyRequests).hasValue(1);
        assertThat(ambiguous.deliver(record(message)))
                .isEqualTo(new ControlResult.Failed(OptionalLong.of(17),
                        ControlResult.FailureKind.AMBIGUOUS_DELIVERY, "timeout"));
        assertThat(ambiguousRequests).hasValue(2);
    }

    private static AgentMessageRecord record(AgentMessage message) throws Exception {
        return new AgentMessageRecord(message, ProtocolBytes.copyOf(CODEC.encode(message)));
    }

    private static EstablishedSessionCommandDelivery delivery(
            SessionRegistry registry, ControlTransport transport
    ) {
        SessionControlClient client = new SessionControlClient(Duration.ofSeconds(1),
                endpoint -> new ControlTransportFactory.Selection.Available(transport));
        return new EstablishedSessionCommandDelivery(registry, client);
    }

    private static SessionRegistry readyRegistry() {
        SessionRegistry registry = new SessionRegistry();
        registry.replace(new DiscoverySnapshot(
                Map.of(SESSION_ID.value(), session(LocalSessionState.LIVE)), Map.of()));
        return registry;
    }

    private static LocalSession session(LocalSessionState state) {
        SessionManifest manifest = new SessionManifest(
                1, 1, 1, SESSION_ID.value(), 1, 2, List.of("sh"), "/workspace", 42,
                OptionalLong.of(43), 80, 24, 80, 24, "xterm-256color",
                new SessionManifest.Sandbox(false, "none", "fail", List.of(), List.of()),
                new ControlEndpoint(ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET,
                        "control.sock", Path.of("session", "control.sock")));
        return new LocalSession(Path.of("session", SESSION_ID.value()), manifest,
                HostObservation.live(ChildState.LIVE), JournalObservation.READABLE, state);
    }

    private static int type(byte[] frame) {
        return Short.toUnsignedInt(ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getShort(8));
    }

    private static long sequence(byte[] frame) {
        return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getLong(16);
    }
}
