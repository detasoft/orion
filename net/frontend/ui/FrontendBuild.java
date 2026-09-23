import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Serializes Maven's npm install/build pair within one frontend directory. */
class FrontendBuild {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]).toRealPath();
        Path nodeDirectory = Path.of(args[1]).toAbsolutePath();
        Path outputDirectory = Path.of(args[2]).toAbsolutePath();
        Path lockFile = directory.resolve(".npm-build.lock");

        System.out.println("Waiting for the frontend build lock...");
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            runNpm(directory, nodeDirectory, List.of("install", "--no-save", "--no-audit", "--no-fund"));
            runNpm(directory, nodeDirectory,
                    List.of("run", "build", "--", "--outDir", outputDirectory.toString()));
        }
    }

    private static void runNpm(Path directory, Path nodeDirectory, List<String> arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(nodeDirectory.resolve("node").toString());
        command.add(nodeDirectory.resolve("node_modules/npm/bin/npm-cli.js").toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).inheritIO();
        builder.environment().put("PATH", nodeDirectory + File.pathSeparator
                + builder.environment().getOrDefault("PATH", ""));
        int exitCode = builder.start().waitFor();
        if (exitCode != 0) {
            throw new IOException("npm " + arguments.getFirst() + " failed with exit code " + exitCode);
        }
    }
}
