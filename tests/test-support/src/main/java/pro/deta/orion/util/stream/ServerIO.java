package pro.deta.orion.util.stream;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;
import java.io.OutputStream;

@Getter
@RequiredArgsConstructor
public class ServerIO {
    private final InputStream receive;
    private final OutputStream send;
    private final OutputStream error;

    public StandardStreams standardStreams() {
        return new StandardStreams(receive, send, error);
    }
}
