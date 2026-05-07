package pro.deta.orion.util.stream;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;
import java.io.OutputStream;

import static pro.deta.orion.util.stream.StreamUtils.closeIt;
import static pro.deta.orion.util.stream.StreamUtils.flushIt;

@Getter
@RequiredArgsConstructor
public class StandardStreams implements AutoCloseable {
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final OutputStream errorStream;

    @Override
    public void close() {
        closeIt(inputStream);
        flushIt(outputStream);
        flushIt(errorStream);
    }
}
