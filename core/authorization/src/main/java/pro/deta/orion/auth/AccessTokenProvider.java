package pro.deta.orion.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Supplies one current access token and renews it early without allowing concurrent duplicate renewals.
 */
public final class AccessTokenProvider {
    private final Renewal renewal;
    private final Clock clock;
    private final long refreshSkewSeconds;
    private final long maxJitterSeconds;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final LongSupplier jitterSeconds;
    private final Sleeper sleeper;
    private final AtomicReference<TokenState> current = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Result>> inFlight = new AtomicReference<>();

    public AccessTokenProvider(
            Renewal renewal,
            Clock clock,
            Duration refreshSkew,
            Duration maxJitter,
            int maxAttempts,
            Duration retryDelay) {
        this(
                renewal,
                clock,
                refreshSkew,
                maxJitter,
                maxAttempts,
                retryDelay,
                () -> randomJitter(maxJitter.getSeconds()),
                AccessTokenProvider::sleep);
    }

    AccessTokenProvider(
            Renewal renewal,
            Clock clock,
            Duration refreshSkew,
            Duration maxJitter,
            int maxAttempts,
            Duration retryDelay,
            LongSupplier jitterSeconds,
            Sleeper sleeper) {
        this.renewal = Objects.requireNonNull(renewal, "renewal");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refreshSkewSeconds = nonNegativeSeconds(refreshSkew, "refreshSkew");
        this.maxJitterSeconds = nonNegativeSeconds(maxJitter, "maxJitter");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative");
        }
        this.jitterSeconds = Objects.requireNonNull(jitterSeconds, "jitterSeconds");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    public Result token() {
        while (true) {
            TokenState snapshot = current.get();
            if (isBeforeRefresh(snapshot)) {
                return snapshot.result();
            }
            CompletableFuture<Result> active = inFlight.get();
            if (active != null) {
                return active.join();
            }
            CompletableFuture<Result> created = new CompletableFuture<>();
            if (inFlight.compareAndSet(null, created)) {
                Result result;
                try {
                    try {
                        result = renew(snapshot);
                    } catch (RuntimeException e) {
                        result = fallbackOrFailure(snapshot, "token renewal failed", e);
                    }
                    created.complete(result);
                    return result;
                } catch (Error error) {
                    created.completeExceptionally(error);
                    throw error;
                } finally {
                    inFlight.compareAndSet(created, null);
                }
            }
        }
    }

    private Result renew(TokenState previous) {
        String failureReason = "token renewal failed";
        Throwable failure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            TokenRefreshResult refreshed;
            try {
                refreshed = renewal.refresh();
            } catch (RuntimeException e) {
                refreshed = TokenRefreshResult.failure("token renewal failed", e);
            }
            if (refreshed instanceof TokenRefreshResult.Success(var token, var expiresAtEpochSecond)) {
                Result accepted = accept(token, expiresAtEpochSecond);
                if (accepted instanceof Result.Success) {
                    return accepted;
                }
                Result.Failure rejected = (Result.Failure) accepted;
                failureReason = rejected.reason();
                failure = rejected.throwable();
            } else if (refreshed instanceof TokenRefreshResult.Failure(var reason, var throwable)) {
                failureReason = reason;
                failure = throwable;
            } else {
                failureReason = "token renewal returned no result";
                failure = null;
            }

            if (attempt < maxAttempts) {
                try {
                    sleeper.sleep(retryDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return fallbackOrFailure(previous, "token renewal interrupted", e);
                }
            }
        }
        return fallbackOrFailure(previous, failureReason, failure);
    }

    private Result accept(String token, long expiresAtEpochSecond) {
        long now = clock.instant().getEpochSecond();
        if (token == null || token.isBlank() || expiresAtEpochSecond <= now) {
            return Result.failure("token renewal returned an unusable token");
        }
        long jitter = Math.max(0, Math.min(maxJitterSeconds, jitterSeconds.getAsLong()));
        long refreshAtEpochSecond = subtractSaturated(
                expiresAtEpochSecond,
                addSaturated(refreshSkewSeconds, jitter));
        TokenState replacement = new TokenState(token, expiresAtEpochSecond, refreshAtEpochSecond);
        current.set(replacement);
        return replacement.result();
    }

    private Result fallbackOrFailure(TokenState previous, String reason, Throwable throwable) {
        if (previous != null && clock.instant().getEpochSecond() < previous.expiresAtEpochSecond()) {
            return previous.result();
        }
        return Result.failure(reason, throwable);
    }

    private boolean isBeforeRefresh(TokenState state) {
        return state != null && clock.instant().getEpochSecond() < state.refreshAtEpochSecond();
    }

    private static long nonNegativeSeconds(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value.getSeconds();
    }

    private static long randomJitter(long maximum) {
        if (maximum <= 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextLong(maximum == Long.MAX_VALUE ? maximum : maximum + 1);
    }

    private static long addSaturated(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long subtractSaturated(long left, long right) {
        if (left < Long.MIN_VALUE + right) {
            return Long.MIN_VALUE;
        }
        return left - right;
    }

    private static void sleep(Duration delay) throws InterruptedException {
        Thread.sleep(delay);
    }

    @FunctionalInterface
    public interface Renewal {
        TokenRefreshResult refresh();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }

    public sealed interface Result permits Result.Success, Result.Failure {
        record Success(String token, long expiresAtEpochSecond) implements Result {
            @Override
            public String toString() {
                return "Success[expiresAtEpochSecond=" + expiresAtEpochSecond + "]";
            }
        }

        record Failure(String reason, Throwable throwable) implements Result {
        }

        static Result failure(String reason) {
            return new Failure(reason, null);
        }

        static Result failure(String reason, Throwable throwable) {
            return new Failure(reason, throwable);
        }
    }

    private record TokenState(
            String token,
            long expiresAtEpochSecond,
            long refreshAtEpochSecond) {
        private Result.Success result() {
            return new Result.Success(token, expiresAtEpochSecond);
        }
    }
}
