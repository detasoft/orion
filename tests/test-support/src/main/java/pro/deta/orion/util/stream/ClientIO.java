package pro.deta.orion.util.stream;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;

@Getter
@RequiredArgsConstructor
public class ClientIO {
    private final PipedOutputStream send;
    private final PipedInputStream receive;
}
