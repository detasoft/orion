package pro.deta.orion.provisioning;

import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;

final class OwnedCommandInput implements AutoCloseable {
    private final byte[] bytes;
    private final InputStream stream;

    OwnedCommandInput(byte[] input) {
        bytes = input == null ? new byte[0] : input.clone();
        stream = new ByteArrayInputStream(bytes);
    }

    InputStream stream() {
        return stream;
    }

    @TestOnly
    boolean isCleared() {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        Arrays.fill(bytes, (byte) 0);
    }
}
