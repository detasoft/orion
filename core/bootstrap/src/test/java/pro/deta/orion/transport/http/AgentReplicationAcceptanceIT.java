package pro.deta.orion.transport.http;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentAuthentication;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentMessageRecord;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolDecoder;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.AgentProtocolVersion;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.JournalFormatVersion;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SequenceDecodeResult;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.transport.http.AgentSessionAcceptanceIT.await;

@EnabledOnOs({OS.LINUX, OS.MAC})
class AgentReplicationAcceptanceIT {
    private static final AgentLabel LABEL = new AgentLabel("acceptance");
    private static final SessionId SESSION = new SessionId("wire");
    private static final AgentProtocolCodec CODEC = new AgentProtocolCodec(AgentProtocolLimits.journalDefaults());
    private static final SessionEventCodec EVENTS = new SessionEventCodec(AgentProtocolLimits.journalDefaults());
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bindsAuthenticationToPhysicalConnectionAndPreservesRawIdempotentHistory(boolean allowUnsecure) throws Exception {
        try (var fixture = new AgentSessionAcceptanceIT.Fixture(directory, allowUnsecure);
             var client = client(fixture);
             var borrowed = client(fixture);
             var foreign = client(fixture)) {
            authenticate(fixture, client, LABEL, List.of(SESSION));
            AgentLabel other = new AgentLabel("other");
            SessionId foreignSession = new SessionId("foreign");
            fixture.owner.registerAgent(other, "other");
            authenticate(fixture, foreign, other, List.of(foreignSession));
            assertThat(new Exchange(borrowed, "/agent/session/wire").status()).isEqualTo(401);
            Exchange duplicateControl = new Exchange(client, "/agent/control");
            assertThat(duplicateControl.status()).isEqualTo(409);
            Exchange unauthorized = new Exchange(client, "/agent/session/foreign");
            assertThat(unauthorized.status()).isEqualTo(200);
            unauthorized.send(CODEC.encode(open(foreignSession)));
            unauthorized.closed.get(5, TimeUnit.SECONDS);
            assertThat(fixture.records(foreignSession)).isEmpty();

            Exchange stream = new Exchange(client, "/agent/session/wire");
            stream.open(SESSION, Optional.empty());
            SessionEventRecord first = event(10, "first");
            SessionEventRecord unknown = EVENTS.decode(new byte[]{(byte) 0x83, 0x19, 0x03, (byte) 0xe8,
                    0x19, 0x7f, (byte) 0xff, (byte) 0xa1, 0x61, 0x78, 0x41, 0x55});
            byte[] bytes = encoded(List.of(first, unknown));
            stream.send(bytes);
            stream.sync(SESSION, Optional.of(unknown.eventId()));
            stream.send(bytes);
            stream.sync(SESSION, Optional.of(unknown.eventId()));
            assertThat(fixture.history(SESSION, Optional.empty())).isEqualTo(bytes);
            stream.send(event(10, "conflict").encodedRecord().toByteArray());
            stream.closed.get(5, TimeUnit.SECONDS);
            assertThat(fixture.history(SESSION, Optional.empty())).isEqualTo(bytes);
            Exchange resumed = new Exchange(client, "/agent/session/wire");
            resumed.open(SESSION, Optional.of(unknown.eventId()));
            SessionEventRecord last = event(2000, "last");
            resumed.send(last.encodedRecord().toByteArray());
            resumed.sync(SESSION, Optional.of(last.eventId()));
            assertThat(fixture.records(SESSION)).containsExactly(first, unknown, last);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fencesAlreadyOpenStreamsForReconnectTakeoverAndFreshInstanceReplacement(boolean allowUnsecure) throws Exception {
        try (var fixture = new AgentSessionAcceptanceIT.Fixture(directory, allowUnsecure);
             var old = client(fixture);
             var takeover = client(fixture);
             var replacement = client(fixture);
             var revoked = client(fixture)) {
            Identity identity = authenticate(fixture, old, LABEL, List.of(SESSION));
            Exchange oldStream = new Exchange(old, "/agent/session/wire");
            oldStream.open(SESSION, Optional.empty());
            SessionEventRecord first = event(10, "committed");
            oldStream.send(first.encodedRecord().toByteArray());
            oldStream.sync(SESSION, Optional.of(first.eventId()));
            AgentMessage.Hello hello = identity.hello();
            AgentAuthentication previous = hello.authentication().orElseThrow();
            AgentMessage.Hello reconnect = new AgentMessage.Hello(hello.protocolVersion(), hello.journalFormatVersion(),
                    hello.agentLabel(), hello.instanceId(), hello.agentVersion(), hello.machine(), hello.capabilities(),
                    Optional.of(new AgentAuthentication(previous.generation(), previous.launchId(),
                            AgentAuthentication.Kind.RECONNECT_TOKEN, identity.welcome().reconnectToken().orElseThrow())));
            handshake(takeover, reconnect);
            oldStream.closed.get(5, TimeUnit.SECONDS);
            assertThat(old.stream.isClosed()).isTrue();
            Exchange taken = new Exchange(takeover, "/agent/session/wire");
            taken.open(SESSION, Optional.of(first.eventId()));
            authenticate(fixture, replacement, LABEL, List.of(SESSION));
            taken.closed.get(5, TimeUnit.SECONDS);
            assertThat(takeover.stream.isClosed()).isTrue();
            revoked.send(CODEC.encode(reconnect));
            revoked.terminal.handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
            assertThat(revoked.replies).isEmpty();
            Exchange current = new Exchange(replacement, "/agent/session/wire");
            current.open(SESSION, Optional.of(first.eventId()));
            assertThat(fixture.records(SESSION)).containsExactly(first);
        }
    }

    @Test
    void heldSessionDoesNotBlockAnotherSessionOrControlAndShutdownSettlesStreams() throws Exception {
        try (var fixture = new AgentSessionAcceptanceIT.Fixture(directory);
             var client = client(fixture)) {
            SessionId heldId = new SessionId("held");
            authenticate(fixture, client, LABEL, List.of(SESSION, heldId));
            Exchange held = new Exchange(client, "/agent/session/held");
            assertThat(held.status()).isEqualTo(200);
            byte[] openBytes = CODEC.encode(open(heldId));
            held.send(new byte[]{openBytes[0]});
            Exchange flowing = new Exchange(client, "/agent/session/wire");
            flowing.open(SESSION, Optional.empty());
            List<SessionEventRecord> backlog = new ArrayList<>();
            for (int index = 1; index <= 600; index++) backlog.add(event(index * 10L, "record"));
            flowing.send(encoded(backlog));
            flowing.sync(SESSION, Optional.of(backlog.getLast().eventId()));
            assertThat(fixture.records(SESSION)).containsExactlyElementsOf(backlog);
            assertThat(fixture.records(heldId)).isEmpty();
            var command = new pro.deta.orion.agent.protocol.CommandId("while-held");
            fixture.owner.commandService().resize(LABEL, command, SESSION, 91, 31);
            assertThat(client.replies.poll(5, TimeUnit.SECONDS))
                    .isEqualTo(new AgentMessage.Resize(command, SESSION, 91, 31, 1));
            SessionId observed = new SessionId("control-progress");
            client.send(CODEC.encode(new AgentMessage.SessionStatus(descriptor(observed))));
            await(() -> fixture.owner.sessionOwner(observed).isPresent());
            long started = System.nanoTime();
            fixture.http.onStop();
            held.closed.get(5, TimeUnit.SECONDS);
            flowing.closed.get(5, TimeUnit.SECONDS);
            client.terminal.handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
            assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(5));
        }
    }

    private static JettyHTTPServerIT.TestAgentClient client(AgentSessionAcceptanceIT.Fixture fixture)
            throws Exception {
        var client = JettyHTTPServerIT.agentClient(fixture.http, fixture.material.serverCertificate());
        client.connect();
        return client;
    }

    private static Identity authenticate(AgentSessionAcceptanceIT.Fixture fixture,
                                        JettyHTTPServerIT.TestAgentClient client, AgentLabel label,
                                        List<SessionId> sessions) throws Exception {
        try (var attempt = fixture.owner.provisioningControl(label, fixture.uri(), "/tmp/acceptance", 1024 * 1024,
                "acceptance", "http".equals(fixture.uri().getScheme())).nextAttempt()) {
            AgentMessage.Hello hello = new AgentMessage.Hello(AgentProtocolVersion.CURRENT, JournalFormatVersion.CURRENT,
                    label, new AgentInstanceId(UUID.randomUUID()), "acceptance", new MachineInfo("host", "test", "test"),
                    Map.of(), Optional.of(new AgentAuthentication(attempt.request().generation(),
                            attempt.request().launchId(), AgentAuthentication.Kind.LAUNCH_PERMIT,
                            ProtocolBytes.copyOf(Base64.getUrlDecoder().decode(attempt.permit().copyBytes())))));
            AgentMessage.Welcome welcome = handshake(client, hello);
            List<SessionDescriptor> descriptors = new ArrayList<>();
            for (SessionId session : sessions) descriptors.add(descriptor(session));
            client.send(CODEC.encode(new AgentMessage.SessionList(descriptors)));
            for (SessionId session : sessions) await(() -> fixture.owner.sessionOwner(session).isPresent());
            return new Identity(hello, welcome);
        }
    }

    private static AgentMessage.Welcome handshake(JettyHTTPServerIT.TestAgentClient client, AgentMessage.Hello hello)
            throws Exception {
        client.send(CODEC.encode(hello));
        AgentMessage response = client.replies.poll(5, TimeUnit.SECONDS);
        assertThat(response).isInstanceOf(AgentMessage.Welcome.class);
        assertThat(client.replies.poll(5, TimeUnit.SECONDS)).isInstanceOf(AgentMessage.RequestSessionList.class);
        return (AgentMessage.Welcome) response;
    }

    private record Identity(AgentMessage.Hello hello, AgentMessage.Welcome welcome) {
    }

    private static SessionDescriptor descriptor(SessionId session) {
        return new SessionDescriptor(session, AgentMessage.SessionState.RUNNING, Optional.empty(), Optional.empty(), "");
    }

    private static AgentMessage.SessionOpen open(SessionId session) {
        return new AgentMessage.SessionOpen(session, Optional.empty(), Optional.empty(), AgentMessage.SessionState.RUNNING);
    }

    private static SessionEventRecord event(long id, String text) throws Exception {
        return EVENTS.decode(EVENTS.encode(new EventId(id), new SessionEventPayload.PtyOutput(
                ProtocolBytes.copyOf(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
    }

    private static byte[] encoded(List<SessionEventRecord> records) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (SessionEventRecord record : records) output.writeBytes(record.encodedRecord().toByteArray());
        return output.toByteArray();
    }

    private static final class Exchange implements Stream.Listener {
        private final CompletableFuture<Integer> status = new CompletableFuture<>();
        private final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final LinkedBlockingQueue<AgentMessage> responses = new LinkedBlockingQueue<>();
        private final AgentProtocolDecoder decoder = new AgentProtocolDecoder(AgentProtocolLimits.journalDefaults());
        private final Stream stream;

        private Exchange(JettyHTTPServerIT.TestAgentClient client, String path) throws Exception {
            stream = client.stream.getSession().newStream(new HeadersFrame(new MetaData.Request("POST",
                    HttpURI.from(client.endpoint.resolve(path)), HttpVersion.HTTP_2, HttpFields.EMPTY), null, false),
                    this).get(5, TimeUnit.SECONDS);
        }

        private int status() throws Exception {
            return status.get(5, TimeUnit.SECONDS);
        }

        private void open(SessionId session, Optional<EventId> cursor) throws Exception {
            assertThat(status()).isEqualTo(200);
            send(CODEC.encode(AgentReplicationAcceptanceIT.open(session)));
            sync(session, cursor);
        }

        private void sync(SessionId session, Optional<EventId> cursor) throws Exception {
            assertThat(responses.poll(5, TimeUnit.SECONDS)).isEqualTo(new AgentMessage.SessionSync(session, cursor));
        }

        private void send(byte[] bytes) throws Exception {
            CompletableFuture<Void> sent = new CompletableFuture<>();
            stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(bytes), false),
                    Callback.from(() -> sent.complete(null), sent::completeExceptionally));
            sent.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void onHeaders(Stream stream, HeadersFrame frame) {
            status.complete(((MetaData.Response) frame.getMetaData()).getStatus());
            if (frame.isEndStream()) closed.complete(null);
            else stream.demand();
        }

        @Override
        public void onDataAvailable(Stream stream) {
            Stream.Data data;
            while ((data = stream.readData()) != null) {
                try {
                    var decoded = decoder.accept(data.frame().getByteBuffer());
                    for (var outcome : decoded.outcomes()) {
                        if (outcome instanceof SequenceDecodeResult.Decoded<AgentMessageRecord> record) {
                            responses.add(record.value().message());
                        }
                    }
                    if (data.frame().isEndStream()) closed.complete(null);
                } finally {
                    data.release();
                }
            }
            if (!closed.isDone()) stream.demand();
        }

        @Override
        public void onReset(Stream stream, ResetFrame frame, Callback callback) {
            closed.complete(null);
            callback.succeeded();
        }

        @Override
        public void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback) {
            closed.complete(null);
            callback.succeeded();
        }

        @Override
        public void onClosed(Stream stream) {
            closed.complete(null);
        }
    }
}
