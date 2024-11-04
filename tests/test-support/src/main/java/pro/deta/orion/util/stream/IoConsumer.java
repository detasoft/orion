package pro.deta.orion.util.stream;

import java.io.IOException;

public interface IoConsumer<T> {
    void accept(T t) throws IOException;
}
