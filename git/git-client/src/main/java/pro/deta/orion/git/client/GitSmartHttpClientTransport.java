package pro.deta.orion.git.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Blocking Git Smart HTTP transport backed by the JDK HTTP client.
 *
 * <p>The default transport requires HTTPS and rejects redirects. A supplied
 * {@link HttpClient} owns TLS trust and proxy configuration; its redirect
 * policy must remain compatible with the caller's remote-security policy.
 */
public final class GitSmartHttpClientTransport implements GitClientTransport {
    private static final int PIPE_CAPACITY = 64 * 1024;
    private static final int MAXIMUM_ADVERTISEMENT_BYTES = 16 * 1024 * 1024;
    private static final ScheduledExecutorService DISCOVERY_WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true)
                            .name("orion-git-http-timeout-", 0).factory());

    private final HttpClient client;
    private final GitHttpRequestConfigurer requestConfigurer;
    private final boolean allowPlainHttp;

    public GitSmartHttpClientTransport() {
        this(null, GitHttpRequestConfigurer.none(), false);
    }

    public GitSmartHttpClientTransport(
            HttpClient client,
            GitHttpRequestConfigurer requestConfigurer,
            boolean allowPlainHttp) {
        this.client = client;
        this.requestConfigurer = Objects.requireNonNull(
                requestConfigurer, "requestConfigurer");
        this.allowPlainHttp = allowPlainHttp;
    }

    @Override
    public GitClientTransportSession open(
            GitClientService service,
            URI remoteUri,
            GitClientOptions options) throws GitClientTransportException {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(options, "options");
        URI repositoryUri = validate(remoteUri);
        requireConnectTimeout(options);
        HttpClient httpClient = client == null ? defaultClient(
                options.connectTimeout()) : client;
        try {
            byte[] advertisement = discover(
                    httpClient, service, repositoryUri, options);
            return openPost(httpClient, service, repositoryUri, options, advertisement);
        } catch (GitClientTransportException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure(
                    GitClientFailure.Kind.CANCELLED,
                    true,
                    "Git Smart HTTP discovery was cancelled",
                    error);
        } catch (IOException error) {
            throw failure(
                    GitClientFailure.Kind.TRANSPORT_UNAVAILABLE,
                    true,
                    "Failed to open Git Smart HTTP session",
                    error);
        }
    }

    private void requireConnectTimeout(GitClientOptions options)
            throws GitClientTransportException {
        if (client == null) {
            return;
        }
        Duration configured = client.connectTimeout().orElse(null);
        if (configured == null || configured.compareTo(options.connectTimeout()) > 0) {
            throw failure(
                    GitClientFailure.Kind.PROTOCOL_UNSUPPORTED,
                    false,
                    "Injected HTTP client must use a connect timeout no greater than Git options",
                    null);
        }
    }

    private byte[] discover(
            HttpClient httpClient,
            GitClientService service,
            URI repositoryUri,
            GitClientOptions options)
            throws IOException, InterruptedException, GitClientTransportException {
        URI uri = URI.create(repositoryUri + "/info/refs?service=" + service.command());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(options.operationTimeout())
                .header("Accept", advertisementType(service))
                .header("Git-Protocol", "version=1")
                .GET();
        requestConfigurer.configure(builder);
        HttpResponse<Flow.Publisher<List<ByteBuffer>>> response = awaitResponse(
                httpClient.sendAsync(
                        builder.build(), HttpResponse.BodyHandlers.ofPublisher()),
                options.readTimeout());
        try {
            requireSuccessfulResponse(response, advertisementType(service));
        } catch (GitClientTransportException error) {
            cancel(response.body());
            throw error;
        }
        return stripServiceAnnouncement(
                readBounded(response.body(), options.readTimeout()), service);
    }

    private GitClientTransportSession openPost(
            HttpClient httpClient,
            GitClientService service,
            URI repositoryUri,
            GitClientOptions options,
            byte[] advertisement) throws IOException {
        PipedInputStream requestInput = new PipedInputStream(PIPE_CAPACITY);
        PipedOutputStream requestOutput = new PipedOutputStream(requestInput);
        URI uri = URI.create(repositoryUri + "/" + service.command());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(options.operationTimeout())
                .header("Accept", resultType(service))
                .header("Content-Type", requestType(service))
                .header("Git-Protocol", "version=1")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> requestInput));
        requestConfigurer.configure(builder);
        return GitTimedTransportSession.wrap(new SmartHttpSession(
                advertisement,
                requestInput,
                requestOutput,
                () -> httpClient.sendAsync(
                        builder.build(), HttpResponse.BodyHandlers.ofInputStream()),
                service,
                options.readTimeout()), options);
    }

    private URI validate(URI remoteUri) throws GitClientTransportException {
        Objects.requireNonNull(remoteUri, "remoteUri");
        String scheme = remoteUri.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean http = "http".equalsIgnoreCase(scheme);
        if (!https && !(http && allowPlainHttp)) {
            throw failure(
                    GitClientFailure.Kind.PROTOCOL_UNSUPPORTED,
                    false,
                    "Git Smart HTTP transport requires HTTPS",
                    null);
        }
        if (remoteUri.getHost() == null || remoteUri.getHost().isBlank()) {
            throw failure(
                    GitClientFailure.Kind.PROTOCOL_UNSUPPORTED,
                    false,
                    "Git Smart HTTP URI requires a host",
                    null);
        }
        if (remoteUri.getRawUserInfo() != null
                || remoteUri.getRawQuery() != null
                || remoteUri.getRawFragment() != null) {
            throw failure(
                    GitClientFailure.Kind.PROTOCOL_UNSUPPORTED,
                    false,
                    "Git Smart HTTP URI contains unsupported components",
                    null);
        }
        String text = remoteUri.toASCIIString();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return URI.create(text);
    }

    private static byte[] readBounded(
            Flow.Publisher<List<ByteBuffer>> body,
            Duration timeout)
            throws IOException, InterruptedException, GitClientTransportException {
        BoundedBodySubscriber subscriber = new BoundedBodySubscriber(timeout);
        body.subscribe(subscriber);
        return subscriber.await();
    }

    private static void cancel(Flow.Publisher<List<ByteBuffer>> body) {
        body.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.cancel();
            }

            @Override
            public void onNext(List<ByteBuffer> ignored) {
            }

            @Override
            public void onError(Throwable ignored) {
            }

            @Override
            public void onComplete() {
            }
        });
    }

    private static GitClientTransportException discoveryTimeout(Throwable cause) {
        return failure(GitClientFailure.Kind.TIMEOUT, true,
                "Git Smart HTTP discovery read timed out", cause);
    }

    private static <T> HttpResponse<T> awaitResponse(
            CompletableFuture<HttpResponse<T>> response,
            Duration timeout) throws IOException, InterruptedException {
        try {
            return response.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            response.cancel(true);
            throw failure(GitClientFailure.Kind.TIMEOUT, true,
                    "Git Smart HTTP connection timed out", error);
        } catch (InterruptedException error) {
            response.cancel(true);
            throw error;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof HttpTimeoutException timeoutError) {
                throw failure(GitClientFailure.Kind.TIMEOUT, true,
                        "Git Smart HTTP connection timed out", timeoutError);
            }
            throw new IOException("Git Smart HTTP connection failed", error.getCause());
        }
    }

    private static byte[] stripServiceAnnouncement(
            byte[] bytes,
            GitClientService service) throws GitClientTransportException {
        if (bytes.length < 8) {
            throw malformedAdvertisement();
        }
        int packetLength;
        try {
            packetLength = Integer.parseInt(
                    new String(bytes, 0, 4, StandardCharsets.US_ASCII), 16);
        } catch (NumberFormatException error) {
            throw malformedAdvertisement();
        }
        String expected = "# service=" + service.command() + "\n";
        if (packetLength != expected.getBytes(StandardCharsets.UTF_8).length + 4
                || packetLength + 4 > bytes.length
                || !expected.equals(new String(
                        bytes, 4, packetLength - 4, StandardCharsets.UTF_8))
                || bytes[packetLength] != '0'
                || bytes[packetLength + 1] != '0'
                || bytes[packetLength + 2] != '0'
                || bytes[packetLength + 3] != '0') {
            throw malformedAdvertisement();
        }
        return java.util.Arrays.copyOfRange(bytes, packetLength + 4, bytes.length);
    }

    private static void requireSuccessfulResponse(
            HttpResponse<?> response,
            String expectedType) throws GitClientTransportException {
        int status = response.statusCode();
        if (status == 401) {
            throw failure(GitClientFailure.Kind.AUTHENTICATION_FAILED, false,
                    "Git Smart HTTP authentication failed", null);
        }
        if (status == 403) {
            throw failure(GitClientFailure.Kind.AUTHORIZATION_DENIED, false,
                    "Git Smart HTTP authorization was denied", null);
        }
        if (status < 200 || status >= 300) {
            throw failure(GitClientFailure.Kind.SERVER_ERROR, status >= 500,
                    "Git Smart HTTP request failed with status " + status, null);
        }
        String contentType = response.headers().firstValue("Content-Type")
                .orElse("").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!expectedType.equals(contentType)) {
            throw failure(GitClientFailure.Kind.MALFORMED_RESPONSE, false,
                    "Git Smart HTTP response has an unexpected content type", null);
        }
    }

    private static String advertisementType(GitClientService service) {
        return "application/x-" + service.command() + "-advertisement";
    }

    private static String requestType(GitClientService service) {
        return "application/x-" + service.command() + "-request";
    }

    private static String resultType(GitClientService service) {
        return "application/x-" + service.command() + "-result";
    }

    private static GitClientTransportException malformedAdvertisement() {
        return failure(GitClientFailure.Kind.MALFORMED_RESPONSE, false,
                "Malformed Git Smart HTTP service advertisement", null);
    }

    private static GitClientTransportException failure(
            GitClientFailure.Kind kind,
            boolean retryable,
            String message,
            Throwable cause) {
        return new GitClientTransportException(kind, retryable, message, cause);
    }

    static HttpClient defaultClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static final class BoundedBodySubscriber
            implements Flow.Subscriber<List<ByteBuffer>> {
        private final AtomicInteger state = new AtomicInteger();
        private final AtomicLong progress = new AtomicLong();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
        private volatile ScheduledFuture<?> watchdog;
        private volatile Flow.Subscription subscription;

        private BoundedBodySubscriber(Duration timeout) {
            this.timeout = timeout;
            resetWatchdog();
        }

        private final Duration timeout;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (state.get() != 0) {
                subscription.cancel();
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (state.get() != 0) {
                return;
            }
            resetWatchdog();
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                if (bytes.size() + length > MAXIMUM_ADVERTISEMENT_BYTES) {
                    fail(failure(
                            GitClientFailure.Kind.MALFORMED_RESPONSE,
                            false,
                            "Git Smart HTTP advertisement exceeds size limit",
                            null));
                    return;
                }
                byte[] copy = new byte[length];
                buffer.get(copy);
                bytes.writeBytes(copy);
            }
        }

        @Override
        public void onError(Throwable error) {
            fail(error);
        }

        @Override
        public void onComplete() {
            if (state.compareAndSet(0, 1)) {
                watchdog.cancel(false);
                completed.complete(bytes.toByteArray());
            }
        }

        private byte[] await()
                throws IOException, InterruptedException, GitClientTransportException {
            try {
                return completed.get();
            } catch (InterruptedException error) {
                cancel();
                throw error;
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof GitClientTransportException transportError) {
                    throw transportError;
                }
                if (cause instanceof IOException ioError) {
                    throw ioError;
                }
                throw new IOException("Git Smart HTTP discovery body failed", cause);
            }
        }

        private void timeout() {
            timeout(progress.get());
        }

        private void timeout(long expectedProgress) {
            if (progress.get() == expectedProgress && state.compareAndSet(0, 2)) {
                Flow.Subscription currentSubscription = subscription;
                if (currentSubscription != null) {
                    currentSubscription.cancel();
                }
                completed.completeExceptionally(discoveryTimeout(null));
            }
        }

        private void fail(Throwable error) {
            if (state.compareAndSet(0, 1)) {
                watchdog.cancel(false);
                Flow.Subscription currentSubscription = subscription;
                if (currentSubscription != null) {
                    currentSubscription.cancel();
                }
                completed.completeExceptionally(error);
            }
        }

        private void cancel() {
            if (state.compareAndSet(0, 1)) {
                watchdog.cancel(false);
                Flow.Subscription currentSubscription = subscription;
                if (currentSubscription != null) {
                    currentSubscription.cancel();
                }
                completed.cancel(false);
            }
        }

        private void resetWatchdog() {
            long currentProgress = progress.incrementAndGet();
            ScheduledFuture<?> previous = watchdog;
            watchdog = DISCOVERY_WATCHDOG.schedule(
                    () -> timeout(currentProgress), timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (previous != null) {
                previous.cancel(false);
            }
        }
    }

    private static final class SmartHttpSession implements GitClientTransportSession {
        private final PipedInputStream requestInput;
        private final PipedOutputStream requestOutput;
        private final Supplier<CompletableFuture<HttpResponse<InputStream>>> responseSupplier;
        private final GitClientService service;
        private final Duration readTimeout;
        private final SwitchingInput input;
        private final FinishingOutput output;
        private InputStream responseBody;
        private CompletableFuture<HttpResponse<InputStream>> response;
        private boolean closed;

        private SmartHttpSession(
                byte[] advertisement,
                PipedInputStream requestInput,
                PipedOutputStream requestOutput,
                Supplier<CompletableFuture<HttpResponse<InputStream>>> responseSupplier,
                GitClientService service,
                Duration readTimeout) {
            this.requestInput = requestInput;
            this.requestOutput = requestOutput;
            this.responseSupplier = responseSupplier;
            this.service = service;
            this.readTimeout = readTimeout;
            input = new SwitchingInput(
                    new ByteArrayInputStream(advertisement), this::postResponse);
            output = new FinishingOutput(requestOutput, this::startPost);
        }

        @Override
        public BufferedByteInput input() {
            return input;
        }

        @Override
        public BufferedByteOutput output() {
            return output;
        }

        private InputStream postResponse() throws IOException {
            try {
                HttpResponse<InputStream> completed = startPost().get(
                        readTimeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
                rememberResponseBody(completed.body());
                requireSuccessfulResponse(completed, resultType(service));
                return responseBody;
            } catch (InterruptedException error) {
                cancelPost();
                Thread.currentThread().interrupt();
                throw failure(GitClientFailure.Kind.CANCELLED, true,
                        "Git Smart HTTP response was cancelled", error);
            } catch (java.util.concurrent.TimeoutException error) {
                throw failure(GitClientFailure.Kind.TIMEOUT, true,
                        "Git Smart HTTP response timed out", error);
            } catch (ExecutionException error) {
                if (error.getCause() instanceof HttpTimeoutException timeoutError) {
                    throw failure(GitClientFailure.Kind.TIMEOUT, true,
                            "Git Smart HTTP response timed out", timeoutError);
                }
                throw failure(GitClientFailure.Kind.TRANSPORT_UNAVAILABLE, true,
                        "Git Smart HTTP response failed", error.getCause());
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (this) {
                closed = true;
            }
            cancelPost();
            IOException failure = null;
            try {
                requestOutput.close();
            } catch (IOException error) {
                failure = error;
            }
            try {
                requestInput.close();
            } catch (IOException error) {
                failure = combine(failure, error);
            }
            if (responseBody != null) {
                try {
                    responseBody.close();
                } catch (IOException error) {
                    failure = combine(failure, error);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static IOException combine(IOException first, IOException next) {
            if (first == null) {
                return next;
            }
            first.addSuppressed(next);
            return first;
        }

        private synchronized CompletableFuture<HttpResponse<InputStream>> startPost() {
            if (response == null) {
                response = responseSupplier.get();
                response.whenComplete((completed, error) -> {
                    if (completed != null) {
                        rememberResponseBody(completed.body());
                    }
                });
            }
            return response;
        }

        private synchronized void cancelPost() {
            if (response != null) {
                response.cancel(true);
            }
        }

        private synchronized void rememberResponseBody(InputStream body) {
            if (responseBody != null) {
                return;
            }
            if (closed) {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // The session has already closed; no caller can observe this error.
                }
                return;
            }
            responseBody = body;
        }
    }

    @FunctionalInterface
    private interface InputSupplier {
        InputStream get() throws IOException;
    }

    private static final class SwitchingInput implements BufferedByteInput {
        private InputStreamBufferedByteInput delegate;
        private InputSupplier next;

        private SwitchingInput(InputStream initial, InputSupplier next) {
            delegate = new InputStreamBufferedByteInput(initial);
            this.next = next;
        }

        @Override
        public int available() {
            return delegate.available();
        }

        @Override
        public int readUnsignedByte() throws IOException {
            try {
                return delegate.readUnsignedByte();
            } catch (EOFException error) {
                switchInput();
                return delegate.readUnsignedByte();
            }
        }

        @Override
        public ByteBuf readCopy(int length, ByteBufAllocator allocator) throws IOException {
            try {
                return delegate.readCopy(length, allocator);
            } catch (EOFException error) {
                switchInput();
                return delegate.readCopy(length, allocator);
            }
        }

        @Override
        public int readInto(ByteBuf target, int maxLength) throws IOException {
            int read = delegate.readInto(target, maxLength);
            if (read == 0 && next != null) {
                switchInput();
                return delegate.readInto(target, maxLength);
            }
            return read;
        }

        private void switchInput() throws IOException {
            if (next == null) {
                throw new EOFException("Git Smart HTTP response reached end of stream");
            }
            delegate = new InputStreamBufferedByteInput(next.get());
            next = null;
        }
    }

    private static final class FinishingOutput implements BufferedByteOutput {
        private final PipedOutputStream output;
        private final Runnable start;
        private boolean finished;
        private boolean started;

        private FinishingOutput(PipedOutputStream output, Runnable start) {
            this.output = output;
            this.start = start;
        }

        @Override
        public void write(ByteBuf buffer) throws IOException {
            if (finished) {
                throw new IOException("Git Smart HTTP request is already complete");
            }
            start();
            buffer.getBytes(buffer.readerIndex(), output, buffer.readableBytes());
        }

        @Override
        public void flush() throws IOException {
            if (!finished) {
                start();
                finished = true;
                output.close();
            }
        }

        private void start() {
            if (!started) {
                started = true;
                start.run();
            }
        }
    }
}
