package pro.deta.orion.agent.server.transport;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.replication.SessionReplicationService;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Low-level Jetty HTTP/2 endpoint for disposable session replication streams. */
public final class JettySessionReplicationEndpoint implements ServerSessionListener, AutoCloseable {
    private static final String SESSION_PATH_PREFIX = "/agent/session/";

    private final SessionReplicationService replication;
    private final EstablishedAgentContextProvider contexts;
    private final AgentProtocolLimits limits;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public JettySessionReplicationEndpoint(
            SessionReplicationService replication,
            EstablishedAgentContextProvider contexts,
            AgentProtocolLimits limits) {
        this.replication = Objects.requireNonNull(replication, "replication");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.limits = Objects.requireNonNull(limits, "limits");
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
        Objects.requireNonNull(stream, "stream");
        if (!(frame.getMetaData() instanceof MetaData.Request request)) {
            return reject(stream, 400);
        }
        SessionId sessionId = sessionId(request);
        if (sessionId == null) {
            return reject(stream, routeStatus(request));
        }
        if (!"POST".equals(request.getMethod())) {
            return reject(stream, 405);
        }
        if (closed.get()) {
            return reject(stream, 503);
        }
        Optional<AgentId> context;
        try {
            context = Objects.requireNonNull(
                    contexts.agentIdFor(stream.getSession()), "established context result");
        } catch (RuntimeException failure) {
            return reject(stream, 500);
        }
        if (context.isEmpty()) {
            return reject(stream, 401);
        }
        JettySessionReplicationStream listener = new JettySessionReplicationStream(
                stream,
                sessionId,
                context.orElseThrow(),
                replication,
                limits,
                executor);
        MetaData.Response response = new MetaData.Response(
                200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
        stream.headers(
                new HeadersFrame(stream.getId(), response, null, false),
                Callback.from(
                        () -> listener.accepted(frame.isEndStream()),
                        listener::admissionFailed));
        return listener;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.close();
        }
    }

    private SessionId sessionId(MetaData.Request request) {
        String path = request.getHttpURI().getDecodedPath();
        if (path == null || !path.startsWith(SESSION_PATH_PREFIX)) {
            return null;
        }
        try {
            return new SessionId(path.substring(SESSION_PATH_PREFIX.length()));
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private int routeStatus(MetaData.Request request) {
        String path = request.getHttpURI().getDecodedPath();
        return path != null && path.startsWith(SESSION_PATH_PREFIX) ? 400 : 404;
    }

    private Stream.Listener reject(Stream stream, int status) {
        MetaData.Response response = new MetaData.Response(
                status, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
        stream.headers(
                new HeadersFrame(stream.getId(), response, null, true),
                Callback.NOOP);
        return Stream.Listener.AUTO_DISCARD;
    }
}
