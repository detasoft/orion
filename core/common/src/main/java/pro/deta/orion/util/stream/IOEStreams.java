package pro.deta.orion.util.stream;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;
import java.io.OutputStream;

@Getter
@RequiredArgsConstructor
public class IOEStreams implements IOEStreamProvider {
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final OutputStream errorStream;
}
