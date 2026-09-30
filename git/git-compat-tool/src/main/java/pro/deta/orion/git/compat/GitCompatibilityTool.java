package pro.deta.orion.git.compat;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

public final class GitCompatibilityTool {
    private GitCompatibilityTool() {}

    public static void main(String[] args) {
        System.exit(run(args, System.err));
    }

    static int run(String[] args, PrintStream errors) {
        if (args.length != 7 || !"export".equals(args[0])
                || !"--store".equals(args[1]) || !"--repository".equals(args[3])
                || !"--output".equals(args[5])) {
            errors.println("Usage: git-compat-tool export --store PATH --repository NAME --output PATH");
            return 2;
        }
        try {
            GitCompatibilityExporter exporter = new FileGitCompatibilityExporter();
            exporter.export(Path.of(args[2]), args[4], Path.of(args[6]));
            return 0;
        } catch (IOException | IllegalArgumentException failure) {
            errors.println("Git export failed: " + failure.getMessage());
            return 1;
        }
    }
}
