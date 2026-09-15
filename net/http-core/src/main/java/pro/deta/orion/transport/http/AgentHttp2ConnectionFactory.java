package pro.deta.orion.transport.http;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.server.replication.SessionReplicationService;
import pro.deta.orion.agent.server.transport.JettySessionReplicationEndpoint;

import java.util.Optional;
import java.util.function.Supplier;

/** Composes journal streams with ordinary HTTP on the same authenticated HTTP/2 connection. */
final class AgentHttp2ConnectionFactory extends HTTP2ServerConnectionFactory {
    private final JettySessionReplicationEndpoint replication;

    AgentHttp2ConnectionFactory(HttpConfiguration configuration, Supplier<SessionReplicationService> service) {
        super(configuration);
        replication = replicationEndpoint(service);
    }

    static HTTP2CServerConnectionFactory cleartext(
            HttpConfiguration configuration, Supplier<SessionReplicationService> service) {
        return new HTTP2CServerConnectionFactory(configuration) {
            private final JettySessionReplicationEndpoint replication = replicationEndpoint(service);

            @Override
            protected ServerSessionListener newSessionListener(Connector connector, EndPoint endpoint) {
                return new HTTPServerSessionListener(endpoint) {
                    @Override
                    public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
                        if (isReplication(frame)) {
                            return replication.onNewStream(stream, frame);
                        }
                        return super.onNewStream(stream, frame);
                    }
                };
            }

            @Override
            protected void doStop() throws Exception {
                try {
                    replication.close();
                } finally {
                    super.doStop();
                }
            }
        };
    }

    private static JettySessionReplicationEndpoint replicationEndpoint(Supplier<SessionReplicationService> service) {
        return new JettySessionReplicationEndpoint(service, session -> {
            if (session instanceof HTTP2Session http2
                    && http2.getEndPoint().getConnection() instanceof ConnectionMetaData connection) {
                return AgentControlRoute.contextFor(connection);
            }
            return Optional.empty();
        }, AgentProtocolLimits.journalDefaults());
    }

    @Override
    protected ServerSessionListener newSessionListener(Connector connector, EndPoint endpoint) {
        return new HTTPServerSessionListener(endpoint) {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
                if (isReplication(frame)) {
                    return replication.onNewStream(stream, frame);
                }
                return super.onNewStream(stream, frame);
            }
        };
    }

    private static boolean isReplication(HeadersFrame frame) {
        return frame.getMetaData() instanceof MetaData.Request request
                && request.getHttpURI().getDecodedPath() != null
                && request.getHttpURI().getDecodedPath().startsWith("/agent/session/");
    }

    @Override
    protected void doStop() throws Exception {
        try {
            replication.close();
        } finally {
            super.doStop();
        }
    }
}
