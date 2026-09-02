package pro.deta.orion.agentd.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

public final class LaunchPermitReader {
    private static final int MAX_ENCODED_BYTES = 683;

    public LaunchPermit read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] supplied = input.readNBytes(MAX_ENCODED_BYTES + 2);
        byte[] encoded = null;
        byte[] decoded = null;
        try {
            if (supplied.length < 2 || supplied.length > MAX_ENCODED_BYTES + 1
                    || supplied[supplied.length - 1] != '\n') {
                throw invalid();
            }
            for (int index = 0; index < supplied.length - 1; index++) {
                int value = supplied[index] & 0xff;
                if (!(value >= 'A' && value <= 'Z')
                        && !(value >= 'a' && value <= 'z')
                        && !(value >= '0' && value <= '9')
                        && value != '-' && value != '_') {
                    throw invalid();
                }
            }
            encoded = Arrays.copyOf(supplied, supplied.length - 1);
            decoded = Base64.getUrlDecoder().decode(encoded);
            return new LaunchPermit(decoded);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        } finally {
            Arrays.fill(supplied, (byte) 0);
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }

    private static IOException invalid() {
        return new IOException("Invalid launch permit input");
    }
}
