package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolDecoder;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.SequenceDecodeResult;
import pro.deta.orion.agent.server.connection.AgentControlHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static jakarta.servlet.http.HttpServletResponse.SC_HTTP_VERSION_NOT_SUPPORTED;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.AGENT_HANDSHAKE;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.POST;

@Singleton
public final class AgentControlRoute implements OrionHttpRoute {
    static final String PATH = "/agent/control";
    private static final int INPUT_CHUNK_BYTES = 8 * 1024;
    private static final int MAX_PENDING_MESSAGES = 64;
    private static final int MAX_PENDING_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);
    private static final OrionHttpRouteDefinition DEFINITION =
            new OrionHttpRouteDefinition(PATH, AGENT_HANDSHAKE, POST);

    private final AgentControlHandler handler;
    private final AgentProtocolLimits limits;
    private final long handshakeTimeoutMillis;

    @Inject
    public AgentControlRoute() {
        this(connection -> new AgentControlHandler.Session() {
                @Override
                public void onMessage(AgentMessage message) {
                    connection.close();
                }

                @Override
                public void onClosed(Throwable failure) {
                }
            }, AgentProtocolLimits.defaults(), HANDSHAKE_TIMEOUT);
    }

    AgentControlRoute(AgentControlHandler handler, AgentProtocolLimits limits, Duration handshakeTimeout) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.limits = Objects.requireNonNull(limits, "limits");
        handshakeTimeoutMillis = Objects.requireNonNull(handshakeTimeout, "handshakeTimeout").toMillis();
        if (handshakeTimeoutMillis < 1) {
            throw new IllegalArgumentException("handshakeTimeout must be positive");
        }
    }

    @Override
    public OrionHttpRouteDefinition definition() {
        return DEFINITION;
    }

    @Override
    public void handle(OrionHttpExchange exchange) throws IOException {
        HttpServletRequest request = exchange.request();
        HttpServletResponse response = exchange.servletResponse();
        if (!"HTTP/2.0".equals(request.getProtocol()) || !request.isSecure()) {
            exchange.sendError(SC_HTTP_VERSION_NOT_SUPPORTED);
            return;
        }

        AsyncContext async = request.startAsync();
        async.setTimeout(0);
        response.setStatus(SC_OK);
        new ControlStream(
                async,
                request.getInputStream(),
                ServletContextRequest.getServletContextRequest(request),
                ServletContextResponse.getServletContextResponse(response),
                handler,
                limits,
                handshakeTimeoutMillis).start();
    }

    private static final class ControlStream
            implements AgentControlHandler.Connection, ReadListener, AsyncListener, LifeCycle.Listener {
        private static final Object END = new Object();

        private final AsyncContext async;
        private final ServletInputStream input;
        private final ServletContextRequest request;
        private final ServletContextResponse response;
        private final Server server;
        private final AgentControlHandler handler;
        private final AgentProtocolDecoder decoder;
        private final AgentProtocolCodec codec;
        private final long handshakeTimeoutMillis;
        private final LinkedBlockingQueue<Object> inbound = new LinkedBlockingQueue<>(MAX_PENDING_MESSAGES);
        private final Queue<PendingWrite> outbound = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean handshakePending = new AtomicBoolean(true);
        private final Object decoderLock = new Object();
        private final Object outputLock = new Object();
        private volatile Scheduler.Task handshakeDeadline;
        private AgentControlHandler.Session session;
        private int queuedBytes;
        private boolean writing;
        private volatile Throwable terminalFailure;

        private ControlStream(AsyncContext async, ServletInputStream input, ServletContextRequest request,
                              ServletContextResponse response, AgentControlHandler handler,
                              AgentProtocolLimits limits, long handshakeTimeoutMillis) {
            this.async = async;
            this.input = input;
            this.request = request;
            this.response = response;
            server = request.getConnectionMetaData().getConnector().getServer();
            this.handler = handler;
            decoder = new AgentProtocolDecoder(limits);
            codec = new AgentProtocolCodec(limits);
            this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        }

        private void start() throws IOException {
            async.addListener(this);
            server.addEventListener(this);
            if (!server.isRunning()) {
                fail(new IOException("HTTP server stopping"));
                return;
            }
            handshakeDeadline = request.getComponents().getScheduler().schedule(
                    this::timeoutHandshake, handshakeTimeoutMillis, TimeUnit.MILLISECONDS);
            input.setReadListener(this);
            response.write(false, ByteBuffer.allocate(0), Callback.from(
                    () -> Thread.ofVirtual().name("agent-control-stream").start(this::runApplication),
                    this::fail));
        }

        private void runApplication() {
            try {
                session = Objects.requireNonNull(handler.open(this), "handler session");
                while (true) {
                    Object next = inbound.take();
                    if (next == END) {
                        return;
                    }
                    session.onMessage((AgentMessage) next);
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                fail(failure);
            } catch (Throwable failure) {
                fail(failure);
            } finally {
                AgentControlHandler.Session current = session;
                if (current != null) {
                    try {
                        current.onClosed(terminalFailure);
                    } catch (Throwable ignored) {
                        // Application cleanup cannot prevent transport cleanup.
                    }
                }
            }
        }

        @Override
        public CompletionStage<Void> send(AgentMessage message) {
            byte[] encoded;
            try {
                encoded = codec.encode(Objects.requireNonNull(message, "message"));
            } catch (AgentProtocolException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            PendingWrite pending = new PendingWrite(encoded);
            synchronized (outputLock) {
                if (closed.get()) {
                    return CompletableFuture.failedFuture(new IOException("control stream is closed"));
                }
                if (outbound.size() >= MAX_PENDING_MESSAGES
                        || queuedBytes > MAX_PENDING_OUTPUT_BYTES - encoded.length) {
                    return CompletableFuture.failedFuture(new IOException("control output queue is full"));
                }
                outbound.add(pending);
                queuedBytes += encoded.length;
            }
            tryWrite();
            return pending.completion;
        }

        @Override
        public void handshakeComplete() {
            cancelHandshakeDeadline();
        }

        @Override
        public void close() {
            fail(new IOException("agent control stream closed"));
        }

        @Override
        public void onDataAvailable() throws IOException {
            byte[] bytes = new byte[INPUT_CHUNK_BYTES];
            while (input.isReady() && !input.isFinished()) {
                int read = input.read(bytes);
                if (read < 0) {
                    finishInput();
                    return;
                }
                if (read == 0) {
                    return;
                }
                accept(ByteBuffer.wrap(bytes, 0, read));
            }
        }

        @Override
        public void onAllDataRead() {
            finishInput();
        }

        @Override
        public void onError(Throwable failure) {
            fail(failure);
        }

        private void accept(ByteBuffer bytes) {
            SequenceDecodeResult<AgentMessage> result;
            synchronized (decoderLock) {
                if (closed.get()) {
                    return;
                }
                result = decoder.accept(bytes);
            }
            for (SequenceDecodeResult.Outcome<AgentMessage> outcome : result.outcomes()) {
                if (outcome instanceof SequenceDecodeResult.Decoded<AgentMessage> decoded
                        && !inbound.offer(decoded.value())) {
                    fail(new IOException("control input queue is full"));
                    return;
                }
            }
            result.terminalIssue().ifPresent(issue -> fail(issue.exception()));
        }

        private void finishInput() {
            SequenceDecodeResult<AgentMessage> result;
            synchronized (decoderLock) {
                if (closed.get()) {
                    return;
                }
                result = decoder.finish();
            }
            for (SequenceDecodeResult.Outcome<AgentMessage> outcome : result.outcomes()) {
                if (outcome instanceof SequenceDecodeResult.Decoded<AgentMessage> decoded
                        && !inbound.offer(decoded.value())) {
                    fail(new IOException("control input queue is full"));
                    return;
                }
            }
            if (result.terminalIssue().isPresent()) {
                fail(result.terminalIssue().orElseThrow().exception());
            } else {
                finish(null);
            }
        }

        private void tryWrite() {
            PendingWrite pending;
            synchronized (outputLock) {
                if (closed.get() || writing) {
                    return;
                }
                pending = outbound.peek();
                if (pending == null) {
                    return;
                }
                writing = true;
            }
            response.write(false, ByteBuffer.wrap(pending.bytes), Callback.from(
                    () -> writeSucceeded(pending), this::fail));
        }

        private void writeSucceeded(PendingWrite pending) {
            synchronized (outputLock) {
                if (closed.get() || outbound.peek() != pending) {
                    writing = false;
                    return;
                }
                outbound.remove();
                queuedBytes -= pending.bytes.length;
                writing = false;
            }
            pending.completion.complete(null);
            tryWrite();
        }

        @Override
        public void onComplete(AsyncEvent event) {
            finish(null);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            timeoutHandshake();
        }

        @Override
        public void onError(AsyncEvent event) {
            fail(event.getThrowable());
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
        }

        @Override
        public void lifeCycleStopping(LifeCycle event) {
            fail(new IOException("HTTP server stopping"));
        }

        private void fail(Throwable failure) {
            finish(failure == null ? new IOException("control stream failed") : failure);
        }

        private void timeoutHandshake() {
            if (handshakePending.compareAndSet(true, false)) {
                fail(new IOException("agent control handshake timed out"));
            }
        }

        private void cancelHandshakeDeadline() {
            if (handshakePending.compareAndSet(true, false)) {
                Scheduler.Task deadline = handshakeDeadline;
                if (deadline != null) {
                    deadline.cancel();
                }
            }
        }

        private void finish(Throwable failure) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            server.removeEventListener(this);
            cancelHandshakeDeadline();
            terminalFailure = failure;
            synchronized (decoderLock) {
                decoder.reset();
            }
            inbound.clear();
            inbound.offer(END);
            synchronized (outputLock) {
                PendingWrite pending;
                while ((pending = outbound.poll()) != null) {
                    pending.completion.completeExceptionally(
                            failure == null ? new IOException("control stream closed") : failure);
                }
                queuedBytes = 0;
                writing = false;
            }
            if (failure != null) {
                request.getServletChannel().abort(failure);
            } else {
                try {
                    async.complete();
                } catch (IllegalStateException ignored) {
                    // The container already completed the asynchronous request.
                }
            }
        }

        private static final class PendingWrite {
            private final byte[] bytes;
            private final CompletableFuture<Void> completion = new CompletableFuture<>();

            private PendingWrite(byte[] bytes) {
                this.bytes = bytes;
            }
        }
    }
}
