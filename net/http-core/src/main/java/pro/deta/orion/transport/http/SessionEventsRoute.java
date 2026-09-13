package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.ee10.servlet.ServletContextResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.agent.server.journal.JournalReadResult;
import pro.deta.orion.agent.server.journal.JournalStorageException;
import pro.deta.orion.agent.server.replication.LiveEventBroker;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static jakarta.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.GET;

/**
 * Serves raw CBOR sequence history to application administrators. The optional unsigned decimal
 * {@code after} cursor is exclusive; {@code follow=true} continues with committed live records.
 * Clients resume after the last complete record they consumed, discarding any truncated final item.
 * Live writes have a 30-second deadline; each subscription coalesces pending journal notifications.
 */
public final class SessionEventsRoute extends BaseAdminRoute {
    private static final String PREFIX = OrionAdminPaths.SESSIONS + "/";
    private static final String SUFFIX = "/events";
    private static final String CONTENT_TYPE = "application/cbor-seq";
    private final AgentSessionServer server;
    private final long writeTimeoutMillis;

    @Inject
    public SessionEventsRoute(AgentSessionServer server) {
        this(server, Duration.ofSeconds(30));
    }

    @TestOnly
    static SessionEventsRoute withWriteTimeout(AgentSessionServer server, Duration timeout) {
        return new SessionEventsRoute(server, timeout);
    }

    private SessionEventsRoute(AgentSessionServer server, Duration timeout) {
        super(PREFIX + "*" + SUFFIX, GET);
        this.server = Objects.requireNonNull(server, "server");
        writeTimeoutMillis = timeout.toMillis();
        if (writeTimeoutMillis < 1) {
            throw new IllegalArgumentException("write timeout must be positive");
        }
    }

    @Override
    public void handle(OrionHttpExchange exchange) throws IOException {
        HttpServletRequest request = exchange.request();
        SessionId sessionId;
        Optional<EventId> after;
        boolean follow;
        try {
            sessionId = sessionId(routePath(request));
            after = after(request.getParameter("after"));
            String mode = request.getParameter("follow");
            if (mode != null && !"true".equals(mode) && !"false".equals(mode)) {
                throw new IllegalArgumentException("Invalid follow mode");
            }
            follow = "true".equals(mode);
        } catch (IllegalArgumentException failure) {
            exchange.sendError(SC_BAD_REQUEST);
            return;
        }
        JournalReadResult page;
        try {
            if (follow) {
                follow(exchange, sessionId, after);
                return;
            }
            page = server.readSessionEvents(sessionId, after);
        } catch (JournalStorageException failure) {
            exchange.sendError(failure.reason() == JournalStorageException.Reason.CLOSED
                    ? SC_SERVICE_UNAVAILABLE : SC_INTERNAL_SERVER_ERROR);
            return;
        } catch (IllegalStateException failure) {
            exchange.sendError(SC_SERVICE_UNAVAILABLE);
            return;
        }
        OutputStream output = exchange.openResponseBody(OrionHttpResponse.stream(SC_OK, CONTENT_TYPE)
                .withHeader("Cache-Control", "no-store"));
        for (var record : page.records()) {
            output.write(record.encodedRecord().toByteArray());
        }
    }

    private void follow(OrionHttpExchange exchange, SessionId sessionId, Optional<EventId> after) {
        LiveEventBroker.Subscription subscription = server.replicationService().subscribe(sessionId);
        try {
            AsyncContext async = exchange.request().startAsync();
            async.setTimeout(0);
            exchange.servletResponse().setStatus(SC_OK);
            exchange.servletResponse().setContentType(CONTENT_TYPE);
            exchange.servletResponse().setHeader("Cache-Control", "no-store");
            new LiveStream(async, exchange, sessionId, after, subscription).start();
        } catch (RuntimeException failure) {
            subscription.close();
            throw failure;
        }
    }

    private final class LiveStream implements AsyncListener, LifeCycle.Listener {
        private final AsyncContext async;
        private final ServletContextRequest request;
        private final ServletContextResponse response;
        private final Server httpServer;
        private final SessionId sessionId;
        private final Optional<EventId> initialCursor;
        private final LiveEventBroker.Subscription subscription;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread worker;

        private LiveStream(AsyncContext async, OrionHttpExchange exchange, SessionId sessionId,
                           Optional<EventId> after, LiveEventBroker.Subscription subscription) {
            this.async = async;
            request = ServletContextRequest.getServletContextRequest(exchange.request());
            response = ServletContextResponse.getServletContextResponse(exchange.servletResponse());
            httpServer = request.getConnectionMetaData().getConnector().getServer();
            this.sessionId = sessionId;
            initialCursor = after;
            this.subscription = subscription;
            worker = Thread.ofVirtual().name("session-events").unstarted(this::run);
        }

        private void start() {
            async.addListener(this);
            httpServer.addEventListener(this);
            if (!httpServer.isRunning()) {
                finish(new IOException("HTTP server stopping"));
                return;
            }
            worker.start();
        }

        private void run() {
            try {
                write(ByteBuffer.allocate(0));
                Optional<EventId> cursor = initialCursor;
                while (!closed.get() && !subscription.isClosed()) {
                    cursor = replay(cursor);
                    subscription.awaitChange(Duration.ofSeconds(30));
                }
                finish(new IOException("Session event subscription closed"));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                finish(failure);
            } catch (Exception failure) {
                finish(failure);
            }
        }

        private Optional<EventId> replay(Optional<EventId> after)
                throws JournalStorageException, IOException, InterruptedException, ExecutionException {
            Optional<EventId> cursor = after;
            for (var record : server.readSessionEvents(sessionId, after).records()) {
                write(ByteBuffer.wrap(record.encodedRecord().toByteArray()));
                cursor = Optional.of(record.eventId());
            }
            return cursor;
        }

        private void write(ByteBuffer bytes) throws IOException, InterruptedException, ExecutionException {
            if (closed.get()) {
                throw new IOException("Session event stream is closed");
            }
            CompletableFuture<Void> sent = new CompletableFuture<>();
            Scheduler.Task deadline = request.getComponents().getScheduler().schedule(
                    () -> finish(new IOException("Session event write timed out")),
                    writeTimeoutMillis, TimeUnit.MILLISECONDS);
            try {
                response.write(false, bytes,
                        Callback.from(() -> sent.complete(null), sent::completeExceptionally));
                sent.get();
            } finally {
                deadline.cancel();
            }
        }

        private void finish(Throwable failure) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            subscription.close();
            httpServer.removeEventListener(this);
            worker.interrupt();
            if (failure != null) {
                request.getServletChannel().abort(failure);
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            finish(null);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            finish(new IOException("Session event request timed out"));
        }

        @Override
        public void onError(AsyncEvent event) {
            finish(event.getThrowable());
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
        }

        @Override
        public void lifeCycleStopping(LifeCycle event) {
            finish(new IOException("HTTP server stopping"));
        }
    }

    private static SessionId sessionId(String path) {
        if (path == null || !path.startsWith(PREFIX) || !path.endsWith(SUFFIX)) {
            throw new IllegalArgumentException("Invalid session event path");
        }
        return new SessionId(path.substring(PREFIX.length(), path.length() - SUFFIX.length()));
    }

    private static Optional<EventId> after(String value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Invalid session event cursor");
        }
        return Optional.of(EventId.fromUnsigned(new BigInteger(value)));
    }

    private static String routePath(HttpServletRequest request) {
        String path = request.getPathInfo();
        return path != null && !path.isBlank() ? path : request.getRequestURI();
    }
}
