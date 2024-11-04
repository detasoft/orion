package pro.deta.orion.util.stream;

import lombok.Getter;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;

@Getter
public class DirectionalByteArrayOutputStream extends ByteArrayOutputStream {
    private final Direction direction;

    public DirectionalByteArrayOutputStream(Direction direction) {
        super(256);
        this.direction = direction;
    }

    public DirectionalByteArrayOutputStream(Direction direction, byte[] arr) {
        super(arr.length);
        this.direction = direction;
        this.write(arr, 0, arr.length);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DirectionalByteArrayOutputStream that)) return false;
        return direction == that.direction && Arrays.equals(this.toByteArray(), that.toByteArray());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(direction) + Arrays.hashCode(toByteArray());
    }
}
