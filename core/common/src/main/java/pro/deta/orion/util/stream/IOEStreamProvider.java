package pro.deta.orion.util.stream;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;

import static pro.deta.orion.util.stream.StreamUtils.closeIt;
import static pro.deta.orion.util.stream.StreamUtils.flushIt;

public interface IOEStreamProvider extends AutoCloseable{

    default void close() {
        closeIt(getInputStream());
        flushIt(getOutputStream());
        flushIt(getErrorStream());
    }

    InputStream getInputStream();
    OutputStream getOutputStream();
    OutputStream getErrorStream();
}
