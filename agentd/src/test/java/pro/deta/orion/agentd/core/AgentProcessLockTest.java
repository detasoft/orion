package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AgentProcessLockTest {
    private static final AgentProcessMetadata FIRST = metadata(101, 1_000, 1);
    private static final AgentProcessMetadata SECOND = metadata(202, 2_000, 2);

    @TempDir
    Path temporaryDirectory;

    @Test
    void excludesConcurrentAgentAndAllowsReuseWithoutDeletingFile() throws Exception {
        Path lockFile = temporaryDirectory.resolve("state/agentd.lock");
        try (AgentProcessLock first = new AgentProcessLock(lockFile, FIRST)) {
            first.start();
            assertThat(Files.readString(lockFile)).isEqualTo("""
                    version=2
                    pid=101
                    startEpochMillis=1000
                    launchId=10010203-0405-0607-0809-0a0b0c0d0e0f
                    generation=1
                    executable=/opt/orion/releases/1/agentd
                    """);

            AgentProcessLock second = new AgentProcessLock(lockFile, SECOND);
            assertThatExceptionOfType(AgentAlreadyRunningException.class).isThrownBy(second::start);
            second.close();
        }

        try (AgentProcessLock replacement = new AgentProcessLock(lockFile, SECOND)) {
            replacement.start();
            replacement.close();
            replacement.close();
        }
        assertThat(lockFile).exists();
    }

    @Test
    void ignoresStaleMetadataButRejectsSymbolicLink() throws Exception {
        Path state = temporaryDirectory.resolve("state");
        Files.createDirectories(state);
        if (Files.getFileAttributeView(state, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        }
        Path lockFile = state.resolve("agentd.lock");
        Files.writeString(lockFile, "stale");
        if (Files.getFileAttributeView(lockFile, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(lockFile, PosixFilePermissions.fromString("rw-------"));
        }

        try (AgentProcessLock lock = new AgentProcessLock(lockFile, FIRST)) {
            lock.start();
        }

        Path target = temporaryDirectory.resolve("target");
        Files.writeString(target, "target");
        Path symbolic = temporaryDirectory.resolve("agentd-link.lock");
        Files.createSymbolicLink(symbolic, target);
        assertThatIOException().isThrownBy(() -> new AgentProcessLock(symbolic, FIRST).start())
                .withMessageContaining("symbolic");
    }

    @Test
    void rejectsExistingLockFileWritableByOtherUsersWherePosixIsSupported() throws Exception {
        Path lockFile = temporaryDirectory.resolve("agentd.lock");
        Files.writeString(lockFile, "stale");
        assumeTrue(Files.getFileAttributeView(lockFile, PosixFileAttributeView.class) != null);
        Files.setPosixFilePermissions(lockFile, PosixFilePermissions.fromString("rw-rw-rw-"));

        assertThatIOException().isThrownBy(() -> new AgentProcessLock(lockFile, FIRST).start())
                .withMessageContaining("accessible");
    }

    @Test
    void rejectsStateAndLockMetadataAccessibleByOtherUsers() throws Exception {
        Path state = Files.createDirectories(temporaryDirectory.resolve("shared-state"));
        assumeTrue(Files.getFileAttributeView(state, PosixFileAttributeView.class) != null);
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwxr-x---"));

        assertThatIOException().isThrownBy(() ->
                        new AgentProcessLock(state.resolve("agentd.lock"), FIRST).start())
                .withMessageContaining("accessible");

        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        Path lock = Files.writeString(state.resolve("agentd.lock"), "stale");
        Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-r-----"));
        assertThatIOException().isThrownBy(() -> new AgentProcessLock(lock, FIRST).start())
                .withMessageContaining("accessible");
    }

    @Test
    void suppliesOwnerOnlyCreationPermissionsOnlyForPosixFileStores() {
        assertThat(AgentProcessLock.creationAttributes(false)).isEmpty();
        assertThat(AgentProcessLock.creationAttributes(true))
                .singleElement()
                .satisfies(attribute -> {
                    assertThat(attribute.name()).isEqualTo("posix:permissions");
                    assertThat(attribute.value()).isEqualTo(PosixFilePermissions.fromString("rw-------"));
                });
    }

    @Test
    void processMetadataRejectsAnInexactExecutable() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new AgentProcessMetadata(
                        101, 1_000,
                        new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")),
                        new AgentGeneration(1), "relative/agentd"));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new AgentProcessMetadata(
                        101, 1_000,
                        new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")),
                        new AgentGeneration(1), "/opt/orion/agentd\nforged"));
    }

    private static AgentProcessMetadata metadata(long pid, long start, long generation) {
        return new AgentProcessMetadata(
                pid,
                start,
                new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")),
                new AgentGeneration(generation),
                "/opt/orion/releases/1/agentd");
    }
}
