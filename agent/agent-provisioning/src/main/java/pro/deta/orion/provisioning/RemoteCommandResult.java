package pro.deta.orion.provisioning;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public record RemoteCommandResult(
        int exitCode,
        byte[] stdout,
        byte[] stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated
) {
    public RemoteCommandResult {
        stdout = Arrays.copyOf(stdout, stdout.length);
        stderr = Arrays.copyOf(stderr, stderr.length);
    }

    @Override
    public byte[] stdout() {
        return Arrays.copyOf(stdout, stdout.length);
    }

    @Override
    public byte[] stderr() {
        return Arrays.copyOf(stderr, stderr.length);
    }

    public String stdoutText() {
        return new String(stdout, StandardCharsets.UTF_8);
    }

    public String stderrText() {
        return new String(stderr, StandardCharsets.UTF_8);
    }
}
