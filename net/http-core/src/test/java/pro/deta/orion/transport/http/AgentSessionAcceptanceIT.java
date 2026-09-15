package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.agent.protocol.SessionCommandOutcome;
import pro.deta.orion.agentd.session.JsonSessionManifestReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionCommandSource;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionEventType;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.agent.server.command.SessionCommandService;
import pro.deta.orion.agentd.journal.FileSystemSessionJournalReader;
import pro.deta.orion.agentd.journal.JournalReadLimits;
import pro.deta.orion.agentd.session.ControlCommand;
import pro.deta.orion.agentd.session.ControlEndpoint;
import pro.deta.orion.agentd.session.ControlResult;
import pro.deta.orion.agentd.session.SessionControlClient;
import pro.deta.orion.schema.orion.OrionHttpsConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs({OS.LINUX, OS.MAC})
@Timeout(90)
class AgentSessionAcceptanceIT {
    private static final AgentLabel LABEL = new AgentLabel("acceptance");
    private static final SessionId SESSION = new SessionId("one");
    private static final Path HOST = Path.of("../../session-host/target/cargo/debug/session-host")
            .toAbsolutePath().normalize();
    private static final Path UI = Path.of("../frontend/ui").toAbsolutePath().normalize();
    @TempDir Path directory;

    @Test
    void productionAgentStartsControlsAndExitsThroughHttpTerminalConsumer() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.launch();
            fixture.start(SESSION, "printf 'ready\\n'; read line; printf 'received:%s\\n' \"$line\"; "
                    + "while [ ! -f one.exit ]; do sleep 0.02; done; printf 'done\\n'");
            fixture.confirmed(new CommandId("start-one"));
            Path result = directory.resolve("terminal.json");
            Path following = directory.resolve("following");
            Process terminal = fixture.terminal(SESSION, result, following);
            await(() -> {
                String log = Files.readString(directory.resolve("terminal.log"));
                assertThat(terminal.isAlive()).as(log).isTrue();
                assertThat(log).doesNotContain("Error:");
                return Files.exists(following);
            });
            fixture.resize(SESSION, new CommandId("resize"), 101, 37);
            fixture.confirmed(new CommandId("resize"));
            fixture.owner.commandService().input(LABEL, new CommandId("input"), SESSION, UUID.randomUUID(),
                    ProtocolBytes.copyOf("hello\n".getBytes(StandardCharsets.UTF_8)));
            fixture.confirmed(new CommandId("input"));
            assertThat(fixture.owner.commandService().status(new CommandId("resize")).operationSequence())
                    .isEqualTo(1);
            assertThat(fixture.owner.commandService().status(new CommandId("input")).operationSequence())
                    .isEqualTo(2);
            Files.createFile(fixture.local.resolve("one.exit"));
            fixture.exited(SESSION);
            assertThat(terminal.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(terminal.exitValue()).as(Files.readString(directory.resolve("terminal.log"))).isZero();
            var rendered = new ObjectMapper().readTree(Files.readString(result));
            assertThat(rendered.path("output").asText()).contains("ready", "received:hello", "done");
            assertThat(rendered.path("sizes").toString()).contains("[101,37]");
            assertThat(rendered.path("statuses").toString()).contains("Following session", "Session exited (0)");
            assertThat(rendered.path("requests").size()).isGreaterThanOrEqualTo(2);
            assertThat(rendered.path("rawBytes").asInt()).isPositive();
            fixture.assertRawJournal(SESSION);
        }
    }

    @Test
    void existingAgentResumesDurableCursorAfterTransportLossAndServerRecreation() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            Process agent = fixture.launch();
            fixture.start(SESSION, "printf before; while [ ! -f offline ]; do sleep 0.02; done; "
                    + "printf after; while [ ! -f one.exit ]; do sleep 0.02; done");
            fixture.confirmed(new CommandId("start-one"));
            Path result = directory.resolve("terminal.json");
            Path following = directory.resolve("following");
            Process terminal = fixture.terminal(SESSION, result, following);
            await(() -> {
                String log = Files.readString(directory.resolve("terminal.log"));
                assertThat(terminal.isAlive()).as(log).isTrue();
                assertThat(log).doesNotContain("Error:");
                return Files.exists(following);
            });
            fixture.http.onStop();
            List<SessionEventRecord> committed = fixture.records(SESSION);
            EventId cursor = committed.getLast().eventId();
            Files.createFile(fixture.local.resolve("offline"));
            await(() -> fixture.localRecords(SESSION).size() > committed.size());
            assertThat(fixture.records(SESSION)).containsExactlyElementsOf(committed);
            fixture.owner.onStop();
            fixture.owner = new AgentSessionServer(directory.resolve("server"));
            fixture.owner.onStart();
            assertThat(fixture.records(SESSION)).containsExactlyElementsOf(committed);
            fixture.restartHttp();
            assertThat(agent.isAlive()).isTrue();
            await(() -> fixture.records(SESSION).size() > committed.size());
            assertThat(fixture.records(SESSION).subList(0, committed.size())).containsExactlyElementsOf(committed);
            assertThat(fixture.history(SESSION, Optional.of(cursor)))
                    .isEqualTo(encoded(fixture.owner.readSessionEvents(SESSION, Optional.of(cursor)).records()));
            Files.createFile(fixture.local.resolve("one.exit"));
            fixture.exited(SESSION);
            fixture.assertRawJournal(SESSION);
            assertThat(terminal.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(terminal.exitValue()).as(Files.readString(directory.resolve("terminal.log"))).isZero();
            var rendered = new ObjectMapper().readTree(Files.readString(result));
            String output = rendered.path("output").asText();
            assertThat(output.indexOf("before")).isEqualTo(output.lastIndexOf("before")).isNotNegative();
            assertThat(output.indexOf("after")).isEqualTo(output.lastIndexOf("after")).isNotNegative();
            assertThat(rendered.path("statuses").toString()).contains("Reconnecting", "Session exited (0)");
            assertThat(agent.isAlive()).isTrue();
        }
    }

    @Test
    void replacementCatchesUpExitedNativeHostAndBacklogBeyondOneRelayPage() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            Process first = fixture.launch();
            fixture.start(SESSION, "printf first; while [ ! -f one.exit ]; do sleep 0.02; done; printf last");
            SessionId second = new SessionId("two");
            fixture.start(second, "printf second; while [ ! -f two.exit ]; do sleep 0.02; done");
            fixture.confirmed(new CommandId("start-one"));
            fixture.confirmed(new CommandId("start-two"));
            stop(first);
            fixture.http.onStop();
            List<SessionEventRecord> committed = fixture.records(SESSION);
            fixture.restartHttp();
            Path socket = fixture.sessionDirectory(SESSION).resolve("control.sock");
            SessionControlClient control = new SessionControlClient(Duration.ofSeconds(2));
            ControlEndpoint endpoint = new ControlEndpoint(ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET,
                    "control.sock", socket);
            for (int sequence = 1; sequence <= 140; sequence++) {
                ControlResult result = control.send(endpoint, new ControlCommand.Resize(sequence,
                        SessionCommandSource.MANUAL, Optional.empty(), 80 + sequence % 2, 24));
                assertThat(result).isInstanceOf(ControlResult.Received.class);
            }
            await(() -> fixture.localRecords(SESSION).size() > 256);
            assertThat(fixture.records(SESSION)).containsExactlyElementsOf(committed);
            Files.createFile(fixture.local.resolve("one.exit"));
            await(() -> fixture.localRecords(SESSION).stream()
                    .anyMatch(record -> record.eventType() == SessionEventType.PROCESS_EXITED));
            Process replacement = fixture.launch();
            fixture.resize(second, new CommandId("second-resize"), 99, 30);
            fixture.confirmed(new CommandId("second-resize"));
            fixture.exited(SESSION);
            fixture.assertRawJournal(SESSION);
            assertThat(fixture.records(SESSION).size()).isGreaterThan(256);
            assertThat(fixture.records(SESSION).subList(0, committed.size())).containsExactlyElementsOf(committed);
            assertThat(replacement.isAlive()).isTrue();
            Files.createFile(fixture.local.resolve("two.exit"));
            fixture.exited(second);
            fixture.assertRawJournal(second);
        }
    }

    private static byte[] encoded(List<SessionEventRecord> records) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (SessionEventRecord record : records) {
            bytes.writeBytes(record.encodedRecord().toByteArray());
        }
        return bytes.toByteArray();
    }

    static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean ready = check.get();
        while (!ready && System.nanoTime() < deadline) {
            Thread.sleep(20);
            ready = check.get();
        }
        assertThat(ready).isTrue();
    }

    interface Check {
        boolean get() throws Exception;
    }

    private static void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    static final class Fixture implements AutoCloseable {
        private final Path directory;
        private final Path local = Files.createTempDirectory(Path.of("/tmp"), "orion-acceptance-");
        final JettyHTTPServerIT.MaterialFixture material = JettyHTTPServerIT.material();
        private final List<Process> processes = new ArrayList<>();
        private final List<SessionId> started = new ArrayList<>();
        private final Path trustStore;
        private final Path certificate;
        AgentSessionServer owner;
        JettyHTTPServer http;
        private int port = pro.deta.orion.util.NetworkUtils.findAvailablePort();

        Fixture(Path directory) throws Exception {
            this.directory = directory;
            assertThat(HOST).as("native host must be built; acceptance cannot be skipped").isExecutable();
            owner = new AgentSessionServer(directory.resolve("server"));
            owner.onStart();
            owner.registerAgent(LABEL, "Acceptance worker");
            restartHttp();
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            trust.setCertificateEntry("server", material.serverCertificate());
            trustStore = directory.resolve("trust.p12");
            try (var output = Files.newOutputStream(trustStore)) {
                trust.store(output, "acceptance".toCharArray());
            }
            certificate = directory.resolve("server.pem");
            Files.writeString(certificate, "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(material.serverRootCertificate().getEncoded())
                    + "\n-----END CERTIFICATE-----\n");
        }

        private void restartHttp() throws Exception {
            var routes = new OrionHttpRouteServlet(new OrionHttpRouteRegistry(Set.of(
                    new AgentControlRoute(owner), new SessionEventsRoute(owner))),
                    new OrionHttpResponseWriter(new ObjectMapper())) {
                @Override
                public void service(HttpServletRequest request, HttpServletResponse response)
                        throws IOException, ServletException {
                    if ("Bearer acceptance-admin".equals(request.getHeader("Authorization"))) {
                        AccessControl.Grant grant = new AccessControlDraft.Grant("admin", new ArrayList<>())
                                .addKey(AccessControl.GrantKey.ADMIN, AccessControl.TRUE_STRING).toAccessControl();
                        request.setAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE,
                                SecurityContext.createContext()
                                        .withUserIdentity(new InternalUserImpl("admin", List.of(grant))));
                    }
                    super.service(request, response);
                }
            };
            http = new JettyHTTPServer(JettyHTTPServerIT.httpConfiguration(false),
                    JettyHTTPServerIT.desiredState(OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                            List.of(), port), material.owner().tls(), routes, null, owner);
            http.onStart();
            port = http.boundHttpsPort();
        }

        URI uri() throws Exception {
            return URI.create(http.relativiseHttps("").toString());
        }

        private Process launch() throws Exception {
            var provisioning = owner.provisioningControl(LABEL, uri(), local.toString(), 1024 * 1024, "acceptance");
            try (var attempt = provisioning.nextAttempt()) {
                Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-Djavax.net.ssl.trustStore=" + trustStore,
                        "-Djavax.net.ssl.trustStorePassword=acceptance", "-cp", System.getProperty("java.class.path"),
                        "pro.deta.orion.agentd.AgentdMain", "--server", uri().toString(), "--state-dir", local.toString(),
                        "--agent-label", LABEL.value(), "--generation", Long.toString(attempt.request().generation().value()),
                        "--launch-id", attempt.request().launchId().value().toString(),
                        "--agent-version", "acceptance", "--session-host", HOST.toString())
                        .redirectErrorStream(true).redirectOutput(directory.resolve("agent-" + processes.size() + ".log")
                                .toFile()).start();
                processes.add(process);
                try (var input = process.getOutputStream()) {
                    input.write(attempt.permit().copyBytes());
                    input.write('\n');
                }
                assertThat(provisioning.awaitOnline(attempt.request().launchId(), Duration.ofSeconds(15)))
                        .as("AgentD log: %s", directory.resolve("agent-" + (processes.size() - 1) + ".log")).isTrue();
                return process;
            }
        }

        private Process terminal(SessionId session, Path result, Path following) throws Exception {
            Path script = Path.of("src/test/resources/session-terminal-acceptance.mjs").toAbsolutePath();
            ProcessBuilder builder = new ProcessBuilder(UI.resolve("target/node/node/node").toString(),
                    script.toString(), UI.resolve("src/lib/session-terminal.js").toString(),
                    uri().toString(), session.value(), result.toString(), following.toString());
            builder.environment().put("NODE_EXTRA_CA_CERTS", certificate.toString());
            Process process = builder.redirectErrorStream(true)
                    .redirectOutput(directory.resolve("terminal.log").toFile()).start();
            processes.add(process);
            return process;
        }

        private void start(SessionId session, String shell) throws Exception {
            started.add(session);
            owner.commandService().start(LABEL, new AgentMessage.StartSession(new CommandId("start-" + session.value()),
                    session, Optional.empty(), List.of("/bin/sh", "-c", shell), local.toString(), Map.of("TERM", "xterm"),
                    80, 24, "none", "native"));
        }

        private void confirmed(CommandId command) throws Exception {
            await(() -> owner.commandService().status(command).phase() == SessionCommandService.Phase.CONFIRMED);
            assertThat(owner.commandService().status(command).outcome()).contains(SessionCommandOutcome.SUCCEEDED);
        }

        private void resize(SessionId session, CommandId id, int columns, int rows) throws Exception {
            await(() -> {
                try {
                    owner.commandService().resize(LABEL, id, session, columns, rows);
                    return true;
                } catch (IllegalArgumentException pendingDiscovery) {
                    if (!pendingDiscovery.getMessage().equals("Session is not running")) {
                        throw pendingDiscovery;
                    }
                    return false;
                }
            });
        }

        private Path sessionDirectory(SessionId session) {
            return local.resolve("sessions").resolve(session.value());
        }

        private List<SessionEventRecord> localRecords(SessionId session) {
            var page = new FileSystemSessionJournalReader().readPage(sessionDirectory(session), Optional.empty(),
                    Optional.empty(), new JournalReadLimits(2048, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES));
            assertThat(page.issue()).isEmpty();
            return page.records();
        }

        List<SessionEventRecord> records(SessionId session) throws Exception {
            return owner.readSessionEvents(session, Optional.empty()).records();
        }

        private void exited(SessionId session) throws Exception {
            await(() -> records(session).stream().anyMatch(record -> record.eventType() == SessionEventType.PROCESS_EXITED));
        }

        byte[] history(SessionId session, Optional<EventId> cursor) throws Exception {
            var tls = JettyHTTPServerIT.agentdTls(material.serverCertificate());
            tls.start();
            try (HttpClient client = HttpClient.newBuilder().sslContext(tls.getSslContext())
                    .connectTimeout(Duration.ofSeconds(5)).build()) {
                URI url = uri().resolve("/api/admin/sessions/" + session.value() + "/events"
                        + cursor.map(id -> "?after=" + id).orElse(""));
                var response = client.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer acceptance-admin")
                        .timeout(Duration.ofSeconds(5)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("content-type")).contains("application/cbor-seq");
                return response.body();
            } finally {
                tls.stop();
            }
        }

        private void assertRawJournal(SessionId session) throws Exception {
            await(() -> encoded(records(session)).length == encoded(localRecords(session)).length);
            assertThat(history(session, Optional.empty())).isEqualTo(encoded(localRecords(session)));
            assertThat(records(session)).extracting(SessionEventRecord::eventId).doesNotHaveDuplicates();
        }

        @Override
        public void close() throws Exception {
            Exception failure = null;
            for (Process process : processes) {
                try {
                    stop(process);
                } catch (Exception error) {
                    failure = error;
                }
            }
            for (SessionId session : started) {
                try {
                    stopNative(session);
                } catch (Exception error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            try {
                http.onStop();
            } finally {
                try {
                    owner.onStop();
                } finally {
                    material.close();
                }
            }
            Path evidence = Files.createDirectories(Path.of("target/acceptance").resolve(directory.getFileName()));
            try (var artifacts = Files.list(directory)) {
                for (Path artifact : artifacts.toList()) {
                    String name = artifact.getFileName().toString();
                    if (name.endsWith(".log") || name.endsWith(".json")) {
                        Files.copy(artifact, evidence.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (failure != null) throw failure;
            try (var files = Files.walk(local)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }

        private void stopNative(SessionId session) throws Exception {
            Path sessionRoot = sessionDirectory(session);
            if (!Files.exists(sessionRoot.resolve("metadata"))) return;
            var manifest = new JsonSessionManifestReader().read(sessionRoot);
            Optional<ProcessHandle> host = ProcessHandle.of(manifest.hostPid());
            if (host.isEmpty() || !host.orElseThrow().isAlive()) return;
            if (!host.orElseThrow().info().commandLine().orElse("").contains(sessionRoot.toString())) return;
            try {
                new SessionControlClient(Duration.ofSeconds(2)).send(manifest.control(),
                        new ControlCommand.Terminate(1_000_000, SessionCommandSource.MANUAL, Optional.empty(),
                                AgentMessage.TerminationMode.FORCE));
            } catch (RuntimeException unavailableControl) {
                // The verified fixture process below remains the cleanup authority.
            }
            try {
                host.orElseThrow().onExit().get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                ProcessHandle ownedHost = host.orElseThrow();
                if (!ownedHost.info().commandLine().orElse("").contains(sessionRoot.toString())) throw timeout;
                for (ProcessHandle child : ownedHost.descendants().toList()) child.destroyForcibly();
                ownedHost.destroyForcibly();
                ownedHost.onExit().get(5, TimeUnit.SECONDS);
            }
        }
    }
}
