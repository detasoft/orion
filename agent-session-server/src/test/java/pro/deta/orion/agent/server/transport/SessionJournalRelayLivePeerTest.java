package pro.deta.orion.agent.server.transport;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.ConnectionId;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.journal.FileSystemSessionJournalStorage;
import pro.deta.orion.agent.server.journal.JournalStorageConfig;
import pro.deta.orion.agent.server.replication.SessionReplicationService;
import pro.deta.orion.agentd.journal.SessionJournalRelay;
import pro.deta.orion.agentd.session.ControlHostProbe;
import pro.deta.orion.agentd.session.FileSystemJournalProbe;
import pro.deta.orion.agentd.session.JsonSessionManifestReader;
import pro.deta.orion.agentd.session.SessionControlClient;
import pro.deta.orion.agentd.session.SessionDiscovery;
import pro.deta.orion.agentd.session.SessionRegistry;
import pro.deta.orion.agentd.transport.JettyHttp2Transport;
import pro.deta.orion.util.CertUtils;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs({OS.LINUX, OS.MAC})
class SessionJournalRelayLivePeerTest {
    private static final AgentProtocolLimits LIMITS = AgentProtocolLimits.journalDefaults();
    private static final SessionId SESSION = new SessionId("n");
    @TempDir Path root;

    @Test
    void relaysNativeRecordsOverTlsAndRecoversServerCursorAfterAgentRestart() throws Exception {
        Path sessions = Files.createDirectory(root.resolve("s"));
        Path directory = Files.createDirectory(sessions.resolve(SESSION.value()));
        Path log = root.resolve("host.log");
        Path releaseChild = root.resolve("release-child");
        Path executable = Path.of("../session-host/target/cargo/debug/session-host").toAbsolutePath();
        assertThat(executable).isExecutable();
        Process host = new ProcessBuilder(executable.toString(), "--session-id", SESSION.value(),
                "--start-command-id", "command.start", "--session-dir", directory.toString(),
                "--cwd", root.toString(), "--cols", "80", "--rows", "24", "--term", "xterm",
                "--", "/bin/sh", "-c", "printf relay-ready; while [ ! -f release-child ]; do sleep 0.02; done; "
                        + "printf relay-finished")
                .redirectOutput(log.toFile()).redirectError(log.toFile()).start();
        try (FileSystemSessionJournalStorage storage = new FileSystemSessionJournalStorage(
                root.resolve("server-journals"), new JournalStorageConfig(LIMITS));
             Peer peer = new Peer(storage)) {
            SessionControlClient control = new SessionControlClient(Duration.ofSeconds(1));
            SessionRegistry registry = new SessionRegistry();
            SessionDiscovery discovery = new SessionDiscovery(sessions, new JsonSessionManifestReader(),
                    new ControlHostProbe(control), new FileSystemJournalProbe(), registry);
            await(() -> {
                assertThat(host.isAlive()).as(Files.readString(log)).isTrue();
                discovery.reconcile();
                return registry.snapshot().sessions().containsKey(SESSION.value());
            });
            AtomicReference<Optional<ConnectionId>> online =
                    new AtomicReference<>(Optional.of(new ConnectionId("first")));
            EventId cursor;
            try (JettyHttp2Transport transport = peer.transport();
                 SessionJournalRelay relay = new SessionJournalRelay(transport, registry, online::get,
                         peer.uri(), LIMITS, control)) {
                transport.connect().toCompletableFuture().get(5, TimeUnit.SECONDS);
                relay.start();
                await(() -> storage.lastEventId(SESSION).isPresent()
                        && Files.exists(directory.resolve("control-retention-state")));
                cursor = storage.lastEventId(SESSION).orElseThrow();
                await(() -> Files.readString(directory.resolve("control-retention-state"))
                        .contains("\"acknowledgedEventId\":" + cursor));
                online.set(Optional.empty());
            }
            assertThat(host.isAlive()).isTrue();
            long recordsBeforeExit = storage.readAfter(SESSION, Optional.empty()).records().size();
            Files.createFile(releaseChild);
            assertThat(host.waitFor(5, TimeUnit.SECONDS)).as(Files.readString(log)).isTrue();
            assertThat(host.exitValue()).as(Files.readString(log)).isZero();
            discovery.reconcile();
            online.set(Optional.of(new ConnectionId("second")));
            try (JettyHttp2Transport transport = peer.transport();
                 SessionJournalRelay relay = new SessionJournalRelay(transport, registry, online::get,
                         peer.uri(), LIMITS, control)) {
                transport.connect().toCompletableFuture().get(5, TimeUnit.SECONDS);
                relay.start();
                await(() -> storage.readAfter(SESSION, Optional.empty()).records().size() > recordsBeforeExit);
                EventId nativeTail = new FileSystemJournalProbe().probe(directory)
                        .lastAvailableEventId().orElseThrow();
                await(() -> storage.lastEventId(SESSION).orElseThrow().equals(nativeTail));
                assertThat(storage.readAfter(SESSION, Optional.of(cursor)).records()).isNotEmpty();
                var reader = new pro.deta.orion.agentd.journal.FileSystemSessionJournalReader();
                var local = reader.readPage(directory, Optional.empty(), Optional.empty(),
                        new pro.deta.orion.agentd.journal.JournalReadLimits(256,
                                AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES));
                assertThat(storage.readAfter(SESSION, Optional.empty()).records())
                        .extracting(record -> record.encodedRecord().toByteArray())
                        .containsExactlyElementsOf(local.records().stream()
                                .map(record -> record.encodedRecord().toByteArray()).toList());
            }
        } finally {
            Files.writeString(releaseChild, "exit");
            if (!host.waitFor(5, TimeUnit.SECONDS)) {
                host.destroyForcibly();
                host.waitFor();
            }
        }
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!check.get() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(check.get()).isTrue();
    }

    private interface Check {
        boolean get() throws Exception;
    }

    private static final class Peer implements AutoCloseable {
        private final Server server = new Server();
        private final KeyStore keys;
        private final ServerConnector connector;
        private final JettySessionReplicationEndpoint replication;

        private Peer(FileSystemSessionJournalStorage storage) throws Exception {
            keys = CertUtils.convertToKeyStore(CertUtils.generateSelfSignedCertificate(),
                    "test", "changeit".toCharArray());
            replication = new JettySessionReplicationEndpoint(new SessionReplicationService(storage),
                    ignored -> Optional.of(new AgentId("agent-1")), LIMITS);
            HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory() {
                @Override
                protected ServerSessionListener newSessionListener(Connector ignored, EndPoint endpoint) {
                    return new ServerSessionListener() {
                        @Override
                        public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
                            MetaData.Request request = (MetaData.Request) frame.getMetaData();
                            if (request.getHttpURI().getPath().equals("/agent/control")) {
                                stream.headers(new HeadersFrame(stream.getId(), new MetaData.Response(
                                        200, null, HttpVersion.HTTP_2, HttpFields.EMPTY), null, false), Callback.NOOP);
                                return Stream.Listener.AUTO_DISCARD;
                            }
                            return replication.onNewStream(stream, frame);
                        }
                    };
                }
            };
            SslContextFactory.Server tls = new SslContextFactory.Server();
            tls.setKeyStore(keys);
            tls.setKeyManagerPassword("changeit");
            tls.setCertAlias("test");
            ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory("h2");
            connector = new ServerConnector(server, new SslConnectionFactory(tls, alpn.getProtocol()), alpn, h2);
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            server.addConnector(connector);
            server.start();
        }

        private URI uri() {
            return URI.create("https://localhost:" + connector.getLocalPort());
        }

        private JettyHttp2Transport transport() {
            SslContextFactory.Client tls = new SslContextFactory.Client();
            tls.setTrustStore(keys);
            return new JettyHttp2Transport(uri(), tls, LIMITS, 8, 8);
        }

        @Override
        public void close() throws Exception {
            server.stop();
            replication.close();
        }
    }
}
