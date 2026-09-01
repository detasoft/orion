package pro.deta.orion.git.client;

import pro.deta.orion.net.io.BufferedByteOutput;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public record GitUploadPackRequest(
        List<String> wants,
        List<String> haves,
        BufferedByteOutput packTarget,
        Consumer<String> progress) {

    public GitUploadPackRequest {
        wants = validatedObjectIds(wants, "wants");
        if (wants.isEmpty()) {
            throw new IllegalArgumentException("wants must not be empty");
        }
        haves = validatedObjectIds(haves, "haves");
        Objects.requireNonNull(packTarget, "packTarget");
        Objects.requireNonNull(progress, "progress");
    }

    public static GitUploadPackRequest of(
            String want,
            BufferedByteOutput packTarget) {
        return new GitUploadPackRequest(
                List.of(want),
                List.of(),
                packTarget,
                ignored -> { });
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
