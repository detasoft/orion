package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GitLockTest {
    @TempDir
    Path directory;

    @Test
    void interruptingAWaiterDoesNotReleaseTheOwnerOrCancelItsSignal() throws Exception {
        var lock = new GitLock(directory);
        var other = new GitLock(directory);
        var id = new PackId(new byte[20]);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var acquired = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = lock.lockPack(id);
            try {
                var waiter = executor.submit(() -> {
                    started.countDown();
                    try (var ignored = other.lockPack(id)) {
                        throw new AssertionError("Owner has not released the pack");
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                    }
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                waiter.cancel(true);
                assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
                var successor = executor.submit(() -> {
                    try (var ignored = other.lockPack(id)) {
                        acquired.countDown();
                    }
                    return null;
                });
                assertThat(acquired.getCount()).isEqualTo(1);
                owner.close();
                successor.get(5, TimeUnit.SECONDS);
                assertThat(acquired.getCount()).isZero();
            } finally {
                owner.close();
            }
        }
    }

    @Test
    void namespacesAndRepositoriesDoNotBlockEachOther() throws Exception {
        var first = new GitLock(directory);
        var second = new GitLock(directory.resolve("another"));
        byte[] bytes = new byte[20];
        try (var pack = first.lockPack(new PackId(bytes));
             var object = first.lockObject(new ObjectId(bytes));
             var otherRepository = second.lockPack(new PackId(bytes))) {
            assertThat(pack).isNotNull();
            assertThat(object).isNotNull();
            assertThat(otherRepository).isNotNull();
        }
    }
}
