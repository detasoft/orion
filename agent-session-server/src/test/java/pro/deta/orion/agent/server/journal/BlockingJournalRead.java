package pro.deta.orion.agent.server.journal;

import pro.deta.orion.agent.protocol.AgentProtocolLimits;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BlockingJournalRead {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final FileSystemSessionJournalStorage storage;
    private volatile boolean armed;

    public BlockingJournalRead(Path root) {
        storage = new FileSystemSessionJournalStorage(root,
                new JournalStorageConfig(AgentProtocolLimits.journalDefaults()), new DurableFileOperations() {
                    @Override
                    void beforeContentRead(Path path) throws IOException {
                        if (!armed) {
                            return;
                        }
                        started.countDown();
                        try {
                            if (!released.await(10, TimeUnit.SECONDS)) {
                                throw new IOException("Timed out waiting to release journal read");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while awaiting journal read release", failure);
                        }
                    }
                });
    }

    public FileSystemSessionJournalStorage storage() {
        return storage;
    }

    public void arm() {
        armed = true;
    }

    public boolean awaitRead() throws InterruptedException {
        return started.await(5, TimeUnit.SECONDS);
    }

    public void release() {
        released.countDown();
    }
}
