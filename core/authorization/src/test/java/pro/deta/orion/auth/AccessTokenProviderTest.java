package pro.deta.orion.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenProviderTest {
    @Test
    void refreshesAtTheSkewAndJitterBoundaryAndAtomicallyReplacesTheToken() {
        MutableClock clock = new MutableClock(1_000);
        AtomicInteger renewals = new AtomicInteger();
        AccessTokenProvider provider = new AccessTokenProvider(
                () -> switch (renewals.incrementAndGet()) {
                    case 1 -> TokenRefreshResult.success("token-one", 1_100);
                    case 2 -> TokenRefreshResult.success("token-two", 1_200);
                    default -> throw new AssertionError("unexpected renewal");
                },
                clock,
                Duration.ofSeconds(30),
                Duration.ofSeconds(20),
                1,
                Duration.ZERO,
                () -> 10,
                ignored -> { });

        assertSuccess(provider.token(), "token-one", 1_100);
        clock.setEpochSecond(1_059);
        assertSuccess(provider.token(), "token-one", 1_100);
        clock.setEpochSecond(1_060);
        assertSuccess(provider.token(), "token-two", 1_200);
        assertThat(renewals).hasValue(2);
    }

    @Test
    void concurrentCallersShareOneRenewal() throws Exception {
        MutableClock clock = new MutableClock(1_000);
        AtomicInteger renewals = new AtomicInteger();
        CountDownLatch renewalEntered = new CountDownLatch(1);
        CountDownLatch releaseRenewal = new CountDownLatch(1);
        AccessTokenProvider provider = new AccessTokenProvider(
                () -> {
                    renewals.incrementAndGet();
                    renewalEntered.countDown();
                    await(releaseRenewal);
                    return TokenRefreshResult.success("shared-token", 1_100);
                },
                clock,
                Duration.ofSeconds(30),
                Duration.ZERO,
                1,
                Duration.ZERO,
                () -> 0,
                ignored -> { });

        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<AccessTokenProvider.Result>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return provider.token();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(renewalEntered.await(5, TimeUnit.SECONDS)).isTrue();
            releaseRenewal.countDown();

            for (Future<AccessTokenProvider.Result> result : results) {
                assertSuccess(result.get(5, TimeUnit.SECONDS), "shared-token", 1_100);
            }
        }
        assertThat(renewals).hasValue(1);
    }

    @Test
    void retriesARejectedRenewalBeforePublishingTheReplacement() {
        MutableClock clock = new MutableClock(1_000);
        AtomicInteger renewals = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        AccessTokenProvider provider = new AccessTokenProvider(
                () -> renewals.incrementAndGet() == 1
                        ? TokenRefreshResult.failure("temporarily unavailable")
                        : TokenRefreshResult.success("retried-token", 1_100),
                clock,
                Duration.ofSeconds(30),
                Duration.ZERO,
                2,
                Duration.ofSeconds(1),
                () -> 0,
                ignored -> sleeps.incrementAndGet());

        assertSuccess(provider.token(), "retried-token", 1_100);
        assertThat(renewals).hasValue(2);
        assertThat(sleeps).hasValue(1);
    }

    @Test
    void preservesAStillValidTokenButFailsAfterItExpiresWhenRenewalIsRejected() {
        MutableClock clock = new MutableClock(1_000);
        AtomicInteger renewals = new AtomicInteger();
        AccessTokenProvider provider = new AccessTokenProvider(
                () -> renewals.incrementAndGet() == 1
                        ? TokenRefreshResult.success("existing-token", 1_100)
                        : TokenRefreshResult.failure("renewal rejected"),
                clock,
                Duration.ofSeconds(30),
                Duration.ofSeconds(20),
                1,
                Duration.ZERO,
                () -> 10,
                ignored -> { });

        assertSuccess(provider.token(), "existing-token", 1_100);
        clock.setEpochSecond(1_060);
        assertSuccess(provider.token(), "existing-token", 1_100);
        clock.setEpochSecond(1_100);
        assertThat(provider.token()).isEqualTo(
                new AccessTokenProvider.Result.Failure("renewal rejected", null));
        assertThat(renewals).hasValue(3);
    }

    @Test
    void tokenResultsDoNotExposeTokenContentsInDiagnostics() {
        assertThat(new AccessTokenProvider.Result.Success("provider-secret", 1_100).toString())
                .doesNotContain("provider-secret");
        assertThat(TokenIssueResult.success("issue-secret", 1_100).toString())
                .doesNotContain("issue-secret");
        assertThat(TokenRefreshResult.success("refresh-secret", 1_100).toString())
                .doesNotContain("refresh-secret");
    }

    private static void assertSuccess(AccessTokenProvider.Result result, String token, long expiresAt) {
        assertThat(result).isEqualTo(new AccessTokenProvider.Result.Success(token, expiresAt));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test coordination");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test interrupted", e);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant current;

        private MutableClock(long epochSecond) {
            current = Instant.ofEpochSecond(epochSecond);
        }

        private void setEpochSecond(long epochSecond) {
            current = Instant.ofEpochSecond(epochSecond);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
