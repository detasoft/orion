package pro.deta.orion.agentd.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class BundledSessionHostTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void installsTheBundledExecutableUnderTheStateDirectory() throws Exception {
        Path installed = BundledSessionHost.install(temporaryDirectory);

        assertThat(installed).isEqualTo(temporaryDirectory.resolve("runtime/session-host"));
        assertThat(installed).isRegularFile();
        assertThat(installed).isExecutable();
        assertThat(Files.size(installed)).isPositive();
        if (Files.getFileStore(installed).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(installed)).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
        }
    }

    @Test
    void trustsAnExecutableWithTheBundledTimestampBeforeHashing() throws Exception {
        Path installed = BundledSessionHost.install(temporaryDirectory);
        FileTime resourceTimestamp = Files.getLastModifiedTime(installed);
        byte[] changed = "same timestamp".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(installed, changed);
        installed.toFile().setExecutable(true, true);
        Files.setLastModifiedTime(installed, resourceTimestamp);

        BundledSessionHost.install(temporaryDirectory);

        assertThat(Files.readAllBytes(installed)).isEqualTo(changed);
    }

    @Test
    void repairsOnlyTheTimestampWhenChecksumsMatchForAnExecutable() throws Exception {
        Path installed = BundledSessionHost.install(temporaryDirectory);
        FileTime resourceTimestamp = Files.getLastModifiedTime(installed);
        Object fileKey = Files.readAttributes(
                installed, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        Files.setLastModifiedTime(installed, FileTime.fromMillis(resourceTimestamp.toMillis() - 10_000));
        if (Files.getFileStore(installed).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(installed, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE));
        }
        Set<PosixFilePermission> permissions = Files.getFileStore(installed).supportsFileAttributeView("posix")
                ? Files.getPosixFilePermissions(installed) : Set.of();

        BundledSessionHost.install(temporaryDirectory);

        assertThat(Files.getLastModifiedTime(installed)).isEqualTo(resourceTimestamp);
        assertThat(Files.readAttributes(
                installed, java.nio.file.attribute.BasicFileAttributes.class).fileKey()).isEqualTo(fileKey);
        if (!permissions.isEmpty()) {
            assertThat(Files.getPosixFilePermissions(installed)).isEqualTo(permissions);
        }
        assertThat(installed).isExecutable();
    }

    @Test
    void atomicallyReplacesMatchingContentThatIsNotExecutable() throws Exception {
        Path installed = BundledSessionHost.install(temporaryDirectory);
        Object fileKey = Files.readAttributes(
                installed, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        Files.setLastModifiedTime(installed, FileTime.fromMillis(1));
        if (Files.getFileStore(installed).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(installed, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } else {
            installed.toFile().setExecutable(false, false);
        }

        BundledSessionHost.install(temporaryDirectory);

        assertThat(Files.readAttributes(
                installed, java.nio.file.attribute.BasicFileAttributes.class).fileKey()).isNotEqualTo(fileKey);
        assertThat(installed).isExecutable();
    }

    @Test
    void atomicallyReplacesContentWithADifferentChecksum() throws Exception {
        Path installed = BundledSessionHost.install(temporaryDirectory);
        byte[] expected = Files.readAllBytes(installed);
        Files.writeString(installed, "obsolete host");
        installed.toFile().setExecutable(true, true);
        Files.setLastModifiedTime(installed, FileTime.fromMillis(1));

        BundledSessionHost.install(temporaryDirectory);

        assertThat(Files.readAllBytes(installed)).isEqualTo(expected);
        assertThat(installed).isExecutable();
        try (var files = Files.list(installed.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactly("session-host");
        }
    }

    @Test
    void concurrentInstallationsConvergeOnOneExecutable() throws Exception {
        List<Path> installed = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var calls = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> executor.submit(() -> BundledSessionHost.install(temporaryDirectory)))
                    .toList();
            for (var call : calls) {
                installed.add(call.get());
            }
        }

        assertThat(installed).containsOnly(temporaryDirectory.resolve("runtime/session-host"));
        assertThat(installed.getFirst()).isExecutable();
        try (var files = Files.list(installed.getFirst().getParent())) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactly("session-host");
        }
    }
}
