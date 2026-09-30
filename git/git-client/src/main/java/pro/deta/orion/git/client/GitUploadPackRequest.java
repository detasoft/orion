package pro.deta.orion.git.client;

import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Receives a borrowed stream containing only pack bytes while the transport session is open.
 * The reader must consume the pack; fetch checks the response end before returning its result.
 * Publication belongs to the caller after fetch succeeds, including any trailing side-band messages.
 */
public record GitUploadPackRequest<T>(
        List<String> wants,
        List<String> haves,
        Reader<T> packReader,
        Consumer<String> progress) {

    public GitUploadPackRequest {
        wants = validatedObjectIds(wants, "wants");
        if (wants.isEmpty()) {
            throw new IllegalArgumentException("wants must not be empty");
        }
        haves = validatedObjectIds(haves, "haves");
        Objects.requireNonNull(packReader, "packReader");
        Objects.requireNonNull(progress, "progress");
    }

    public static <T> GitUploadPackRequest<T> of(
            String want,
            Reader<T> packReader) {
        return new GitUploadPackRequest<>(
                List.of(want),
                List.of(),
                packReader,
                ignored -> { });
    }

    @FunctionalInterface
    public interface Reader<T> {
        T read(BufferedByteInputV2 input) throws IOException;
    }

    private static List<String> validatedObjectIds(
            List<String> values,
            String name) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, name));
        for (String value : copy) {
            GitClientValidation.requireObjectId(value, name);
        }
        return copy;
    }
}
