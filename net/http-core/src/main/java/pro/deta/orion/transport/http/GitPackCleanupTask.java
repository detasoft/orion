package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.util.LogScope;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Periodic orphan-pack maintenance. Only the local backend deletes expired packs. */
@Singleton
public final class GitPackCleanupTask implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(GitPackCleanupTask.class);
    private static final Duration INTERVAL = Duration.ofHours(1);
    private static final Duration RETENTION = Duration.ofHours(24);

    private final NativeGitRepositoryProvider repositories;
    private volatile Status status = new Status("stopped", "", "", 0, 0, 0, "");
    private ScheduledExecutorService executor;

    @Inject
    public GitPackCleanupTask(NativeGitRepositoryProvider repositories) {
        this.repositories = repositories;
    }

    public synchronized void start() {
        if (executor != null) throw new IllegalStateException("Git pack cleanup is already running");
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("git-pack-cleanup").factory());
        status = new Status("scheduled", "", Instant.now().toString(), 0, 0, 0, "");
        executor.scheduleWithFixedDelay(this, 0, INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    public synchronized void stop() throws InterruptedException {
        ScheduledExecutorService running = executor;
        if (running == null) return;
        running.shutdownNow();
        if (!running.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Git pack cleanup has not stopped");
        }
        executor = null;
        Status previous = status;
        status = new Status("stopped", previous.lastAttempt(), "", previous.deleted(),
                previous.observed(), previous.skipped(), previous.message());
    }

    public Status status() {
        return status;
    }

    @Override
    public void run() {
        runOnce(Instant.now());
    }

    Status runOnce(Instant now) {
        try (LogScope user = LogScope.user(null); LogScope task = LogScope.task("git-pack-cleanup")) {
            int deleted = 0;
            int observed = 0;
            int skipped = 0;
            int failed = 0;
            for (String name : repositories.repositoryNames()) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    NativeGitRepository repository = repositories.find(name)
                            .valueOrFailure("Cannot open Git repository for pack cleanup: " + name);
                    if (repository.storage() instanceof LocalGitStorage) {
                        OptionalInt count = repository.deleteExpiredLocalPacks(now.minus(RETENTION));
                        if (count.isPresent()) {
                            deleted += count.getAsInt();
                            if (count.getAsInt() > 0) {
                                LOG.info("Deleted {} expired packs from {}", count.getAsInt(), name);
                            }
                        } else {
                            skipped++;
                        }
                    } else {
                        int count = repository.packCleanupCandidates().size();
                        observed += count;
                        if (count > 0) LOG.info("Found {} undeleted pack candidates in {}", count, name);
                    }
                } catch (IOException | RuntimeException failure) {
                    failed++;
                    LOG.warn("Git pack cleanup failed for {}", name, failure);
                }
            }
            String state = failed == 0 ? "scheduled" : "retrying";
            String message = failed == 0 ? "" : failed + " repositories could not be checked";
            status = new Status(state, now.toString(), now.plus(INTERVAL).toString(),
                    deleted, observed, skipped, message);
        } catch (RuntimeException failure) {
            LOG.warn("Git pack cleanup scan failed", failure);
            status = new Status("retrying", now.toString(), now.plus(INTERVAL).toString(),
                    0, 0, 0, "Repository listing failed");
        }
        return status;
    }

    public record Status(String state, String lastAttempt, String nextAttempt,
                         int deleted, int observed, int skipped, String message) {}
}
