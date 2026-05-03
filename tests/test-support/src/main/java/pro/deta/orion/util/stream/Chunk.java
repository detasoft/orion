package pro.deta.orion.util.stream;

import lombok.Getter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@Getter
public class Chunk {
    private final byte[] data;
    private final boolean flagToClose;

    private Chunk(byte[] data) {
        this.data = data;
        this.flagToClose = false;
    }

    private Chunk(String data) {
        this.data = (data + "\n").getBytes(StandardCharsets.UTF_8);
        this.flagToClose = false;
    }

    private Chunk(byte[] data, boolean isFlagToClose) {
        this.data = data;
        this.flagToClose = isFlagToClose;
    }

    private Chunk(boolean isFlagToClose) {
        this.data = new byte[0];
        this.flagToClose = isFlagToClose;
    }

    public static Chunk of(String data) {
        return new Chunk(data);
    }

    public boolean writeTo(OutputStream outputStream) throws IOException {
        outputStream.write(data);
        outputStream.flush();
        if (flagToClose) {
            return false;
        }
        return true;
    }
}
