package pro.deta.orion.agentd.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class AgentProcessLock implements AgentService {
    private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> UNSAFE_WRITE = Set.of(
            PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);

    private final Path path;
    private final AgentProcessMetadata metadata;
    private FileChannel channel;
    private FileLock lock;

    public AgentProcessLock(Path path, AgentLaunchContext context) {
        this(path, AgentProcessMetadata.current(context));
    }

    AgentProcessLock(Path path, AgentProcessMetadata metadata) {
        this.path = path.toAbsolutePath().normalize();
        this.metadata = metadata;
    }

    @Override
    public synchronized void start() throws IOException {
        if (channel != null) {
            throw new IllegalStateException("AgentD process lock is already started");
        }
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("AgentD lock path requires a parent directory");
        }
        Files.createDirectories(parent);
        rejectUnsafeDirectory(parent);
        if (Files.isSymbolicLink(path)) {
            throw new IOException("AgentD lock path must not be symbolic");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            rejectUnsafeFile(path);
        }
        FileChannel opened = null;
        FileLock acquired = null;
        try {
            boolean supportsPosix = Files.getFileStore(parent)
                    .supportsFileAttributeView(PosixFileAttributeView.class);
            opened = FileChannel.open(
                    path,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    creationAttributes(supportsPosix));
            try {
                acquired = opened.tryLock();
            } catch (OverlappingFileLockException occupied) {
                throw new AgentAlreadyRunningException();
            }
            if (acquired == null) {
                throw new AgentAlreadyRunningException();
            }
            writeMetadata(opened);
            channel = opened;
            lock = acquired;
        } catch (IOException | RuntimeException failure) {
            if (acquired != null) {
                acquired.release();
            }
            if (opened != null) {
                opened.close();
            }
            throw failure;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        IOException failure = null;
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException releaseFailure) {
                failure = releaseFailure;
            }
            lock = null;
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            channel = null;
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void writeMetadata(FileChannel output) throws IOException {
        String value = "version=1\n"
                + "pid=" + metadata.pid() + "\n"
                + "startEpochMillis=" + metadata.startEpochMillis() + "\n"
                + "launchId=" + metadata.launchId().value() + "\n"
                + "generation=" + metadata.generation().value() + "\n";
        ByteBuffer bytes = StandardCharsets.US_ASCII.encode(value);
        output.truncate(0);
        output.position(0);
        while (bytes.hasRemaining()) {
            output.write(bytes);
        }
        output.force(true);
    }

    private static void rejectUnsafeDirectory(Path directory) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
                directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null && hasUnsafeWrite(view.readAttributes().permissions())) {
            throw new IOException("AgentD state directory is writable by another user class");
        }
    }

    private static void rejectUnsafeFile(Path file) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
                file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null && hasUnsafeWrite(view.readAttributes().permissions())) {
            throw new IOException("AgentD lock file is writable by another user class");
        }
    }

    private static boolean hasUnsafeWrite(Set<PosixFilePermission> permissions) {
        for (PosixFilePermission permission : UNSAFE_WRITE) {
            if (permissions.contains(permission)) {
                return true;
            }
        }
        return false;
    }

    static FileAttribute<?>[] creationAttributes(boolean supportsPosix) {
        if (supportsPosix) {
            return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(OWNER_FILE)};
        }
        return new FileAttribute<?>[0];
    }
}
