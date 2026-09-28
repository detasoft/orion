package pro.deta.orion.util.stream;

import lombok.Getter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@Getter
public class Chunk {
    private final byte[] data;

    private Chunk(String data) {
        this.data = (data + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static Chunk of(String data) {
        return new Chunk(data);
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        outputStream.write(data);
        outputStream.flush();
    }
}
