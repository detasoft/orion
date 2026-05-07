package pro.deta.orion.util.stream;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.ByteBuffer;

@Slf4j
public class StreamUtils {

    public static StandardStreams newInstance(InputStream inputStream, OutputStream outputStream, OutputStream errorStream) {
        if (log.isTraceEnabled()) {
            return new RecordingStandardStreams(inputStream, outputStream, errorStream, new StringBuilder());
        } else
            return new StandardStreams(inputStream, outputStream, new TeeOutputStream(errorStream, new ByteArrayOutputStream()));
    }

    public static byte[] getByteArray(ByteBuffer bu) {
        byte[] arr = new byte[bu.flip().remaining()];
        bu.get(arr);
        return arr;
    }


    public static int readStreamInto(ByteBuffer bu, InputStream recv) throws IOException {
        byte[] buffer = new byte[1024];
        int n;
        do {
            n = recv.read(buffer);
            bu.put(buffer, 0, n);
        } while (n == 0);

        return n;
    }

    public static void flushIt(Flushable flushable) {
        try {
            flushable.flush();
        } catch (IOException ignored) {
        }
    }

    public static void closeIt(Closeable flushable) {
        try {
            flushable.close();
        } catch (IOException ignored) {
        }
    }
}
