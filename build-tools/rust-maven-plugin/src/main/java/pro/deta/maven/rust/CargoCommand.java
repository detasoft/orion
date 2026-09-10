package pro.deta.maven.rust;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record CargoCommand(
        String executable,
        Path workingDirectory,
        List<String> arguments,
        Map<String, String> environment) {

    CargoCommand {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        arguments = List.copyOf(arguments);
        environment = Map.copyOf(environment);
    }

    static CargoCommand create(
            String executable,
            Path manifest,
            Path cargoTargetDirectory,
            String goal,
            String target,
            String profile,
            boolean release,
            List<String> features,
            boolean locked,
            boolean offline,
            boolean incremental) {
        String selectedProfile = nonBlank(profile);
        if (release && selectedProfile != null) {
            throw new IllegalArgumentException("release and profile cannot be configured together");
        }

        List<String> arguments = new ArrayList<>();
        arguments.add(goal);
        arguments.add("--manifest-path");
        arguments.add(manifest.toString());
        addOption(arguments, "--target", nonBlank(target));
        if (release) {
            arguments.add("--release");
        } else {
            addOption(arguments, "--profile", selectedProfile);
        }
        if (features != null && !features.isEmpty()) {
            arguments.add("--features");
            arguments.add(String.join(",", features));
        }
        if (locked) {
            arguments.add("--locked");
        }
        if (offline) {
            arguments.add("--offline");
        }

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("CARGO_TARGET_DIR", cargoTargetDirectory.toString());
        environment.put("CARGO_INCREMENTAL", incremental ? "1" : "0");
        return new CargoCommand(executable, manifest.toAbsolutePath().getParent(), arguments, environment);
    }

    List<String> processArguments() {
        List<String> processArguments = new ArrayList<>(arguments.size() + 1);
        processArguments.add(executable);
        processArguments.addAll(arguments);
        return processArguments;
    }

    private static void addOption(List<String> arguments, String option, String value) {
        if (value != null) {
            arguments.add(option);
            arguments.add(value);
        }
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
