package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SessionEventsLiveTest {
    private static final SessionId SESSION = new SessionId("live-session");
    private static final SessionEventCodec CODEC = new SessionEventCodec(AgentProtocolLimits.journalDefaults());

    @TempDir
    Path root;

    @Test
    void replaysThenFollowsRawEventsAndReconnectsAtTheLastReceivedCursor() throws Exception {
        SessionEventRecord first = event(10);
        SessionEventRecord unknown = CODEC.decode(CODEC.encodeOpaque(new EventId(20), 50_000,
                ProtocolBytes.copyOf(new byte[]{(byte) 0xf6}),
                List.of(ProtocolBytes.copyOf(new byte[]{(byte) 0xf6}))));
        SessionEventRecord third = event(30);
        try (Peer peer = new Peer(root)) {
            peer.append(first);
            HttpURLConnection initial = peer.request("?follow=true", true);
            try (var body = initial.getInputStream()) {
                assertThat(initial.getContentType()).isEqualTo("application/cbor-seq");
                assertThat(body.readNBytes(first.encodedRecord().size()))
                        .containsExactly(first.encodedRecord().toByteArray());
                peer.append(unknown);
                assertThat(body.readNBytes(unknown.encodedRecord().size()))
                        .containsExactly(unknown.encodedRecord().toByteArray());
            } finally {
                initial.disconnect();
            }
            HttpURLConnection resumed = peer.request("?after=20&follow=true", true);
            try (var body = resumed.getInputStream()) {
                peer.append(third);
                assertThat(body.readNBytes(third.encodedRecord().size()))
                        .containsExactly(third.encodedRecord().toByteArray());
            } finally {
                resumed.disconnect();
            }
        }
    }

    @Test
    void deniesUnauthorizedLiveAccessAndRejectsInvalidFollowMode() throws Exception {
        try (Peer peer = new Peer(root)) {
            HttpURLConnection denied = peer.request("?follow=true", false);
            try {
                assertThat(denied.getResponseCode()).isEqualTo(403);
            } finally {
                denied.disconnect();
            }
            HttpURLConnection invalid = peer.request("?follow=invalid", true);
            try {
                assertThat(invalid.getResponseCode()).isEqualTo(400);
            } finally {
                invalid.disconnect();
            }
        }
    }

    @Test
    void serverRestartClosesOldSubscriptionsAndReplaysTheDurableJournal() throws Exception {
        SessionEventRecord first = event(10);
        try (Peer peer = new Peer(root)) {
            peer.append(first);
            HttpURLConnection live = peer.request("?after=10&follow=true", true);
            try (var body = live.getInputStream()) {
                peer.sessions.onStop();
                assertClosed(body);
            } finally {
                live.disconnect();
            }
            peer.sessions.onStart();
            HttpURLConnection history = peer.request("", true);
            try (var body = history.getInputStream()) {
                assertThat(body.readAllBytes()).containsExactly(first.encodedRecord().toByteArray());
            } finally {
                history.disconnect();
            }
        }
    }

    @Test
    void httpShutdownClosesAnIdleLiveResponse() throws Exception {
        try (Peer peer = new Peer(root)) {
            HttpURLConnection live = peer.request("?follow=true", true);
            try (var body = live.getInputStream()) {
                peer.http.stop();
                assertClosed(body);
            } finally {
                live.disconnect();
            }
        }
    }

    @Test
    void closesAStalledWriteWhileReplicationAndOtherReadersContinue() throws Exception {
        SessionEventRecord large = CODEC.decode(CODEC.encode(new EventId(10),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(new byte[4 * 1024 * 1024]))));
        try (Peer peer = new Peer(root, Duration.ofMillis(250)); Socket slow = new Socket()) {
            slow.setReceiveBufferSize(1024);
            slow.connect(new InetSocketAddress("127.0.0.1", peer.connector.getLocalPort()), 5_000);
            slow.setSoTimeout(5_000);
            slow.getOutputStream().write(("GET /api/admin/sessions/" + SESSION.value()
                    + "/events?follow=true HTTP/1.1\r\nHost: localhost\r\nAuthorization: admin\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            StringBuilder headers = new StringBuilder();
            while (!headers.toString().endsWith("\r\n\r\n")) {
                int next = slow.getInputStream().read();
                assertThat(next).isNotEqualTo(-1);
                headers.append((char) next);
            }
            assertThat(headers.toString()).startsWith("HTTP/1.1 200");
            peer.append(large);
            Thread.sleep(750);
            SessionEventRecord next = event(20);
            peer.append(next);
            HttpURLConnection healthy = peer.request("?after=10", true);
            try (var body = healthy.getInputStream()) {
                assertThat(body.readAllBytes()).containsExactly(next.encodedRecord().toByteArray());
            } finally {
                healthy.disconnect();
            }
            long delivered = 0;
            byte[] bytes = new byte[8192];
            try {
                int count;
                while ((count = slow.getInputStream().read(bytes)) >= 0) {
                    delivered += count;
                }
            } catch (SocketTimeoutException failure) {
                throw failure;
            } catch (IOException connectionAborted) {
                // A reset is also a terminal result for an aborted HTTP response.
            }
            assertThat(delivered).isLessThan(large.encodedRecord().size());
        }
    }

    private static void assertClosed(InputStream body) throws IOException {
        try {
            assertThat(body.read()).isEqualTo(-1);
        } catch (SocketTimeoutException failure) {
            throw failure;
        } catch (IOException connectionAborted) {
            // Jetty may terminate an unfinished chunked response with a reset.
        }
    }

    private static SessionEventRecord event(long id) throws Exception {
        return CODEC.decode(CODEC.encode(new EventId(id),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(new byte[]{(byte) id}))));
    }

    private static final class Peer implements AutoCloseable {
        private final AgentSessionServer sessions;
        private final Server http = new Server();
        private final ServerConnector connector = new ServerConnector(http);

        private Peer(Path root) throws Exception {
            this(root, Duration.ofSeconds(30));
        }

        private Peer(Path root, Duration writeTimeout) throws Exception {
            sessions = new AgentSessionServer(root);
            sessions.onStart();
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            http.addConnector(connector);
            OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
                    new OrionHttpRouteRegistry(Set.of(
                            SessionEventsRoute.withWriteTimeout(sessions, writeTimeout))),
                    new OrionHttpResponseWriter(new ObjectMapper())) {
                @Override
                public void service(HttpServletRequest request, HttpServletResponse response)
                        throws IOException, ServletException {
                    if ("admin".equals(request.getHeader("Authorization"))) {
                        AccessControl.Grant grant = new AccessControlDraft.Grant("admin", new ArrayList<>())
                                .addKey(AccessControl.GrantKey.ADMIN, AccessControl.TRUE_STRING)
                                .toAccessControl();
                        request.setAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE,
                                SecurityContext.createContext()
                                        .withUserIdentity(new InternalUserImpl("admin", List.of(grant))));
                    }
                    super.service(request, response);
                }
            };
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            ServletHolder holder = new ServletHolder(servlet);
            holder.setAsyncSupported(true);
            context.addServlet(holder, "/*");
            http.setHandler(context);
            http.start();
        }

        private HttpURLConnection request(String query, boolean admin) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:"
                    + connector.getLocalPort() + "/api/admin/sessions/" + SESSION.value() + "/events" + query)
                    .toURL().openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            if (admin) {
                connection.setRequestProperty("Authorization", "admin");
            }
            return connection;
        }

        private void append(SessionEventRecord event) throws Exception {
            sessions.replicationService().append(SESSION, List.of(event));
        }

        @Override
        public void close() throws Exception {
            try {
                http.stop();
            } finally {
                sessions.onStop();
            }
        }
    }
}
