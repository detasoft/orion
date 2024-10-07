package pro.deta.orion.git.util;

import org.eclipse.jgit.diff.DiffEntry;

public class GitUtils {
    public static void printDiff(DiffEntry diff) {
        System.out.printf("Diff: %-6s: %s%6s -> %6s: %s%n",
                diff.getChangeType(),
                diff.getDiffAttribute() != null ? diff.getDiffAttribute() + "-" : "",
                diff.getOldMode(), diff.getNewMode(),
                diff.getOldPath().equals(diff.getNewPath()) ? diff.getNewPath() : diff.getOldPath() + " -> " + diff.getNewPath());
    }
}
