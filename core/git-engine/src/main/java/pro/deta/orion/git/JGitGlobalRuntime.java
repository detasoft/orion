package pro.deta.orion.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.internal.WorkQueue;
import org.eclipse.jgit.util.FS;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class JGitGlobalRuntime {
    private static final int SHUTDOWN_WAIT_SECONDS = 5;

    @Inject
    public JGitGlobalRuntime() {
    }

    public void initializeGlobalExecutors() {
        WorkQueue.getExecutor();
        FS.FileStoreAttributes.setBackground(false);
        FS.FileStoreAttributes.get(Path.of("").toAbsolutePath());
    }

    public void shutdownGlobalExecutors() {
        shutdownExecutor("JGit WorkQueue", WorkQueue.getExecutor());
        shutdownFileStoreAttributeExecutor("FUTURE_RUNNER");
        shutdownFileStoreAttributeExecutor("SAVE_RUNNER");
    }

    private void shutdownFileStoreAttributeExecutor(String fieldName) {
        try {
            Field field = FS.FileStoreAttributes.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof ExecutorService executor) {
                shutdownExecutor("JGit FileStoreAttributes " + fieldName, executor);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("Unable to stop JGit FileStoreAttributes executor {}", fieldName, e);
        }
    }

    private void shutdownExecutor(String name, ExecutorService executor) {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("{} did not stop within {} seconds", name, SHUTDOWN_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while stopping {}", name, e);
        }
    }
}
