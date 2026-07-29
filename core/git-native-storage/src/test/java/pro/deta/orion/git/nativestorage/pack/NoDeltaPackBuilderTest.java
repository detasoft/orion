package pro.deta.orion.git.nativestorage.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class NoDeltaPackBuilderTest {
    @TempDir
    private Path tempDir;

    private final LooseObjectStore objects = new LooseObjectStore();
    private final NoDeltaPackBuilder builder = new NoDeltaPackBuilder();

    @Test
    void buildsOneObjectPackWithHeaderAndTrailerAcceptedByGit() throws Exception {
        GitObjectId id = objects.write(ObjectType.BLOB, "hello\n".getBytes(StandardCharsets.UTF_8));
        LooseObject object = objects.read(id).orElseThrow();

        byte[] pack = builder.build(List.of(object));

        assertThat(Arrays.copyOfRange(pack, 0, 4)).containsExactly((byte) 'P', (byte) 'A', (byte) 'C', (byte) 'K');
        assertThat(readInt(pack, 4)).isEqualTo(2);
        assertThat(readInt(pack, 8)).isEqualTo(1);
        assertThat(Arrays.copyOfRange(pack, pack.length - 20, pack.length))
                .containsExactly(sha1(Arrays.copyOfRange(pack, 0, pack.length - 20)));
        assertGitAcceptsPack(pack);
    }

    @Test
    void sortsObjectsByIdForDeterministicPacks() throws Exception {
        GitObjectId first = objects.write(ObjectType.BLOB, "one\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId second = objects.write(ObjectType.BLOB, "two\n".getBytes(StandardCharsets.UTF_8));
        LooseObject firstObject = objects.read(first).orElseThrow();
        LooseObject secondObject = objects.read(second).orElseThrow();

        byte[] forward = builder.build(List.of(firstObject, secondObject));
        byte[] reverse = builder.build(List.of(secondObject, firstObject));

        assertThat(forward).containsExactly(reverse);
        assertGitAcceptsPack(forward);
    }

    private void assertGitAcceptsPack(byte[] pack) throws Exception {
        Path gitDir = tempDir.resolve("objects-" + System.nanoTime());
        run("git", "init", "--bare", gitDir.toString());
        Process process = new ProcessBuilder("git", "-C", gitDir.toString(), "index-pack", "--stdin")
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().write(pack);
        process.getOutputStream().close();
        boolean finished = process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
    }

    private static void run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static byte[] sha1(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
