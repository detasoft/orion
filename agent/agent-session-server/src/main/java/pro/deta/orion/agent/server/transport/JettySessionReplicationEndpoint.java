package pro.deta.orion.agent.server.transport;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import pro.deta.orion.agent.server.auth.AuthenticatedConnectionContext;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.replication.SessionReplicationService;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Low-level Jetty HTTP/2 endpoint for disposable session replication streams. */
public final class JettySessionReplicationEndpoint implements ServerSessionListener, AutoCloseable {
    private static final String SESSION_PATH_PREFIX = "/agent/session/";

    private final Supplier<SessionReplicationService> replication;
    private final EstablishedAgentContextProvider contexts;
    private final AgentProtocolLimits limits;
    private final ExecutorService executor;
    private final Set<JettySessionReplicationStream> streams = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public JettySessionReplicationEndpoint(
            Supplier<SessionReplicationService> replication,
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
        Optional<AuthenticatedConnectionContext> context;
        try {
            context = Objects.requireNonNull(
                    contexts.contextFor(stream.getSession()), "established context result");
        } catch (RuntimeException failure) {
            return reject(stream, 500);
        }
        if (context.isEmpty()) {
            return reject(stream, 401);
        }
        SessionReplicationService service;
        try {
            service = Objects.requireNonNull(replication.get(), "replication service");
        } catch (RuntimeException failure) {
            return reject(stream, 503);
        }
        JettySessionReplicationStream listener = new JettySessionReplicationStream(
                stream,
                sessionId,
                context.orElseThrow(),
                service,
                limits,
                executor,
                streams::remove);
        streams.add(listener);
        if (closed.get()) {
            listener.close();
            return listener;
        }
        MetaData.Response response = new MetaData.Response(
                200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
        try {
            stream.headers(
                    new HeadersFrame(stream.getId(), response, null, false),
                    Callback.from(
                            () -> listener.accepted(frame.isEndStream()),
                            listener::admissionFailed));
        } catch (RuntimeException failure) {
            listener.close();
        }
        return listener;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (JettySessionReplicationStream stream : streams) {
                stream.close();
            }
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Session replication operations did not stop");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted stopping session replication", failure);
            }
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
