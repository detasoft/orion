package pro.deta.orion.agentd.transport;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;

import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.SessionId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JettyHttp2TransportTest {
    @Test
    void keepsControlCapacityWhenOfflineSessionCapacityIsExhausted() {
        JettyHttp2Transport transport = transport(1, 1);
        byte[] heartbeat = {1};
        SessionId session = new SessionId("session");
        try {
            transport.sendSessionCbor(session, heartbeat);
            assertThatThrownBy(() -> transport.sendSessionCbor(session, heartbeat)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            transport.sendControlCbor(heartbeat);
        } finally {
            transport.close();
        }
    }

    @Test
    void rejectsUnencryptedEndpointsBeforeStartingJetty() {
        assertThatThrownBy(() -> new JettyHttp2Transport(URI.create("http://localhost"),
                new SslContextFactory.Client(), AgentProtocolLimits.defaults(), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSessionStreamOpeningBeforeTheControlConnectionIsAccepted() {
        JettyHttp2Transport transport = transport(1, 1);
        try {
            assertThatThrownBy(() -> transport.openSession(new SessionId("session"), id -> null)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
        } finally {
            transport.close();
        }
    }

    @Test
    void rejectsSendsAfterCloseWithoutLeavingAnIncompleteFuture() {
        JettyHttp2Transport transport = transport(1, 1);
        transport.close();

        assertThatThrownBy(() -> transport.sendControlCbor(new byte[]{1})
                .toCompletableFuture().get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void reportsTlsFactoryStartFailureThroughTheConnectionFuture() {
        SslContextFactory.Client tls = new SslContextFactory.Client() {
            @Override
            protected void doStart() throws Exception {
                throw new Exception("test TLS start failure");
            }
        };
        JettyHttp2Transport transport = new JettyHttp2Transport(URI.create("https://localhost"), tls,
                AgentProtocolLimits.defaults(), 1, 1);
        try {
            assertThatThrownBy(() -> transport.connect().toCompletableFuture()
                    .get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("test TLS start failure");
        } finally {
            transport.close();
        }
    }

    private JettyHttp2Transport transport(int controlCapacity, int sessionCapacity) {
        return new JettyHttp2Transport(URI.create("https://localhost"), new SslContextFactory.Client(),
                AgentProtocolLimits.defaults(), controlCapacity, sessionCapacity);
    }

}
