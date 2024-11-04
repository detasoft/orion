package pro.deta.orion.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

public class TimeoutReader {
    private final BufferedReader reader;
    private final long initialTimeoutMillis;
    private final long extendTimeoutMillis;

    public TimeoutReader(BufferedReader reader) {
        this(reader, 3000, 1000);
    }

    public TimeoutReader(BufferedReader reader, long initialTimeoutMillis, long extendTimeoutMillis) {
        this.reader = reader;
        this.initialTimeoutMillis = initialTimeoutMillis;
        this.extendTimeoutMillis = extendTimeoutMillis;
    }

    public String readAll() throws IOException {
        StringBuilder sb = new StringBuilder();
        long endTime = System.currentTimeMillis() + initialTimeoutMillis;

        while (System.currentTimeMillis() < endTime) {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line != null) {
                    sb.append(line).append("\n");
                    endTime = System.currentTimeMillis() + extendTimeoutMillis;
                }
            } else {
                LockSupport.parkNanos(5_000);
            }
        }
        return sb.toString();
    }
}
