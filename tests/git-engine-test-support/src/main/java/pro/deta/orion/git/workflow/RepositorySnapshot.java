package pro.deta.orion.git.workflow;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.TreeMap;

public final class RepositorySnapshot {
    private final String headSymref;
    private final Map<String, String> refs;
    private final Map<String, Commit> commits;

    private RepositorySnapshot(String headSymref, Map<String, String> refs, Map<String, Commit> commits) {
        this.headSymref = Objects.requireNonNull(headSymref, "headSymref");
        this.refs = Collections.unmodifiableMap(new TreeMap<>(refs));
        this.commits = Collections.unmodifiableMap(new TreeMap<>(commits));
    }

    public static RepositorySnapshot of(
            String headSymref,
            Map<String, String> refs,
            Map<String, Commit> commits) {
        return new RepositorySnapshot(headSymref, refs, commits);
    }

    public static RepositorySnapshot capture(Path repositoryDirectory) throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(repositoryDirectory.toFile());
        try (Repository repository = builder.build()) {
            return capture(repository);
        }
    }

    @Deprecated
    public static RepositorySnapshot capture(Path workTree, String ignoredHead) throws IOException {
        return capture(workTree);
    }

    public static RepositorySnapshot capture(Repository repository) throws IOException {
        Map<String, String> refs = refs(repository);
        return new RepositorySnapshot(headSymref(repository), refs, commits(repository));
    }

    public String headSymref() {
        return headSymref;
    }

    public Map<String, String> refs() {
        return refs;
    }

    public Map<String, Commit> commits() {
        return commits;
    }

    public byte[] bytes() {
        return text().getBytes(StandardCharsets.UTF_8);
    }

    public String text() {
        StringBuilder result = new StringBuilder("HEAD\t").append(headSymref).append('\n');
        refs.forEach((name, objectId) -> result.append("REF\t").append(name).append('\t').append(objectId)
                .append('\n'));
        commits.forEach((objectId, commit) -> appendCommit(result, objectId, commit));
        return result.toString();
    }

    public String difference(RepositorySnapshot actual) {
        String difference = firstDifference("HEAD", headSymref, actual.headSymref);
        if (difference != null) {
            return difference;
        }
        difference = firstMapDifference("ref", refs, actual.refs);
        if (difference != null) {
            return difference;
        }
        return firstCommitDifference(actual);
    }

    private static Map<String, String> refs(Repository repository) throws IOException {
        Map<String, String> refs = new TreeMap<>();
        for (Ref ref : relevantRefs(repository)) {
            ObjectId objectId = ref.getObjectId();
            if (objectId != null) {
                refs.put(ref.getName(), objectId.name());
            }
        }
        return refs;
    }

    private static List<Ref> relevantRefs(Repository repository) throws IOException {
        List<Ref> refs = new ArrayList<>();
        refs.addAll(repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS));
        refs.addAll(repository.getRefDatabase().getRefsByPrefix(Constants.R_TAGS));
        return refs;
    }

    private static String headSymref(Repository repository) throws IOException {
        Ref head = repository.exactRef(Constants.HEAD);
        if (head == null) {
            return "";
        }
        if (head.isSymbolic()) {
            return head.getTarget().getName();
        }
        ObjectId objectId = head.getObjectId();
        return objectId == null ? "" : objectId.name();
    }

    private static Map<String, Commit> commits(Repository repository) throws IOException {
        Map<String, Commit> commits = new TreeMap<>();
        try (RevWalk walk = new RevWalk(repository)) {
            for (Ref ref : relevantRefs(repository)) {
                try {
                    Ref peeled = repository.getRefDatabase().peel(ref);
                    ObjectId objectId = peeled.getPeeledObjectId();
                    if (objectId == null) {
                        objectId = peeled.getObjectId();
                    }
                    walk.markStart(walk.parseCommit(objectId));
                } catch (IOException ignored) {
                    // Tags and non-commit refs remain represented by refs but do not start history.
                }
            }
            for (RevCommit commit : walk) {
                commits.put(commit.name(), commit(repository, commit));
            }
        }
        return commits;
    }

    private static Commit commit(Repository repository, RevCommit commit) throws IOException {
        List<String> parents = new ArrayList<>();
        for (RevCommit parent : commit.getParents()) {
            parents.add(parent.name());
        }
        return new Commit(commit.getTree().name(), parents, treeEntries(repository, commit));
    }

    private static Map<String, TreeEntry> treeEntries(
            Repository repository,
            RevCommit commit) throws IOException {
        Map<String, TreeEntry> entries = new TreeMap<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(commit.getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                ObjectId objectId = walk.getObjectId(0);
                FileMode mode = walk.getFileMode(0);
                entries.put(walk.getPathString(), new TreeEntry(
                        mode.getBits(), objectId.name(), contentHash(repository, mode, objectId)));
            }
        }
        return entries;
    }

    private static String contentHash(
            Repository repository,
            FileMode mode,
            ObjectId objectId) throws IOException {
        if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE && mode != FileMode.SYMLINK) {
            return "";
        }
        return sha256(repository.open(objectId).getBytes());
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    private static void appendCommit(StringBuilder result, String objectId, Commit commit) {
        result.append("COMMIT\t").append(objectId).append('\t').append(commit.tree()).append('\t')
                .append(String.join(",", commit.parents())).append('\n');
        commit.entries().forEach((path, entry) -> result.append("TREE\t").append(objectId).append('\t')
                .append(path).append('\t').append(entry.mode()).append('\t').append(entry.objectId())
                .append('\t')
                .append(entry.contentHash()).append('\n'));
    }

    private String firstCommitDifference(RepositorySnapshot actual) {
        TreeSet<String> ids = new TreeSet<>(commits.keySet());
        ids.addAll(actual.commits.keySet());
        for (String id : ids) {
            Commit expected = commits.get(id);
            Commit received = actual.commits.get(id);
            if (expected == null || received == null) {
                return "commit " + id + " expected=" + expected + " actual=" + received;
            }
            String difference = firstDifference("commit " + id + " tree", expected.tree, received.tree);
            if (difference != null) {
                return difference;
            }
            difference = firstDifference("commit " + id + " parents", expected.parents, received.parents);
            if (difference != null) {
                return difference;
            }
            difference = firstMapDifference("tree " + id, expected.entries, received.entries);
            if (difference != null) {
                return difference;
            }
        }
        return null;
    }

    private static <T> String firstMapDifference(String kind, Map<String, T> expected, Map<String, T> actual) {
        TreeSet<String> names = new TreeSet<>(expected.keySet());
        names.addAll(actual.keySet());
        for (String name : names) {
            String difference = firstDifference(kind + " " + name, expected.get(name), actual.get(name));
            if (difference != null) {
                return difference;
            }
        }
        return null;
    }

    private static String firstDifference(String subject, Object expected, Object actual) {
        return java.util.Objects.equals(expected, actual) ? null
                : subject + " expected=" + expected + " actual=" + actual;
    }

    public record Commit(String tree, List<String> parents, Map<String, TreeEntry> entries) {
        public Commit {
            parents = List.copyOf(parents);
            entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        }
    }

    public record TreeEntry(int mode, String objectId, String contentHash) {
    }
}
