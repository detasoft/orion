package pro.deta.orion.git.compat;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.object.LooseObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

final class FileGitCompatibilityExporter implements GitCompatibilityExporter {
    private static final int OBJECT_ID_BYTES = 20;
    private static final int TREE_MODE = 040000;
    private static final int GITLINK_MODE = 0160000;

    @Override
    public void export(Path sourceStore, String repositoryName, Path bareOutput) throws IOException {
        Objects.requireNonNull(sourceStore, "sourceStore");
        Objects.requireNonNull(repositoryName, "repositoryName");
        Path output = Objects.requireNonNull(bareOutput, "bareOutput").toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceStore)) {
            throw new IOException("Source store does not exist: " + sourceStore);
        }
        if (Files.exists(output, NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(output.toString());
        }
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Export parent directory does not exist: " + parent);
        }
        Path temporary = Files.createTempDirectory(parent, ".orion-git-export-");
        boolean published = false;
        try {
            FileNativeGitRepositoryProvider provider = new FileNativeGitRepositoryProvider(sourceStore);
            if (!provider.exists(repositoryName)) {
                throw new IOException("Source repository does not exist: " + repositoryName);
            }
            try (NativeGitRepository source = provider.find(repositoryName).valueOrFailure("source repository");
                 Repository target = FileRepositoryBuilder.create(temporary.toFile())) {
                if (source.hashAlgorithm() != GitHashAlgorithm.SHA1) {
                    throw new IOException("Only SHA-1 source repositories can be exported to Git SHA-1");
                }
                Map<String, String> refs = source.refs();
                target.create(true);
                Map<String, String> converted;
                try (ObjectInserter inserter = target.newObjectInserter()) {
                    converted = convertReachable(source, inserter, refs);
                    inserter.flush();
                }
                if (!source.refs().equals(refs)) {
                    throw new IOException("Source refs changed during export");
                }
                publishRefs(target, refs, converted, source.defaultHead());
                writeIdMap(temporary, converted);
            }
            verifyWithGit(temporary);
            Files.move(temporary, output, ATOMIC_MOVE);
            published = true;
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        } finally {
            if (!published) {
                deleteTree(temporary);
            }
        }
    }

    private static Map<String, String> convertReachable(
            NativeGitRepository source, ObjectInserter inserter, Map<String, String> refs) throws IOException {
        Map<String, String> converted = new HashMap<>();
        Set<String> visiting = new HashSet<>();
        for (String root : refs.values()) {
            ObjectId rootId = new ObjectId(root);
            if (converted.containsKey(root)) {
                continue;
            }
            Deque<Frame> stack = new ArrayDeque<>();
            push(source, rootId, null, visiting, stack);
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                if (frame.nextDependency < frame.dependencies.size()) {
                    Dependency dependency = frame.dependencies.get(frame.nextDependency++);
                    String id = dependency.id().toHex();
                    if (converted.containsKey(id)) {
                        continue;
                    }
                    if (visiting.contains(id)) {
                        throw new IOException("Object graph contains a cycle at " + id);
                    }
                    push(source, dependency.id(), dependency.type(), visiting, stack);
                    continue;
                }
                byte[] payload = convertedPayload(frame.object, converted);
                org.eclipse.jgit.lib.ObjectId result = inserter.insert(jgitType(frame.object.type()), payload);
                converted.put(frame.id.toHex(), result.name());
                visiting.remove(frame.id.toHex());
                stack.pop();
            }
        }
        return converted;
    }

    private static void push(NativeGitRepository source, ObjectId id, GitObjectType expected,
            Set<String> visiting, Deque<Frame> stack) throws IOException {
        LooseObject object = source.readObject(id)
                .orElseThrow(() -> new IOException("Referenced object is missing: " + id));
        if (expected != null && object.type() != expected) {
            throw new IOException("Object " + id + " has type " + object.type() + ", expected " + expected);
        }
        List<Dependency> dependencies = switch (object.type()) {
            case BLOB -> List.of();
            case TREE -> treeDependencies(parseTree(object.data()));
            case COMMIT -> commitDependencies(parseHeaders(object.data()));
            case TAG -> tagDependencies(parseHeaders(object.data()));
            default -> throw new IOException("Unsupported object type: " + object.type());
        };
        visiting.add(id.toHex());
        stack.push(new Frame(id, object, dependencies));
    }

    private static byte[] convertedPayload(
            LooseObject object, Map<String, String> converted) throws IOException {
        return switch (object.type()) {
            case BLOB -> object.data();
            case TREE -> convertTree(parseTree(object.data()), converted);
            case COMMIT -> convertCommit(parseHeaders(object.data()), converted);
            case TAG -> convertTag(parseHeaders(object.data()), converted);
            default -> throw new IOException("Unsupported object type: " + object.type());
        };
    }

    private static int jgitType(GitObjectType type) throws IOException {
        return switch (type) {
            case BLOB -> Constants.OBJ_BLOB;
            case TREE -> Constants.OBJ_TREE;
            case COMMIT -> Constants.OBJ_COMMIT;
            case TAG -> Constants.OBJ_TAG;
            default -> throw new IOException("Unsupported object type: " + type);
        };
    }

    private static List<Dependency> treeDependencies(List<TreeEntry> entries) {
        List<Dependency> dependencies = new ArrayList<>();
        for (TreeEntry entry : entries) {
            if (entry.mode != GITLINK_MODE) {
                dependencies.add(new Dependency(entry.id, entry.mode == TREE_MODE
                        ? GitObjectType.TREE : GitObjectType.BLOB));
            }
        }
        return dependencies;
    }

    private static byte[] convertTree(
            List<TreeEntry> entries, Map<String, String> converted) throws IOException {
        entries.sort(FileGitCompatibilityExporter::compareTreeEntries);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Set<String> names = new HashSet<>();
        for (TreeEntry entry : entries) {
            if (!names.add(new String(entry.name, StandardCharsets.ISO_8859_1))) {
                throw new IOException("Tree contains duplicate entry names");
            }
            output.writeBytes((Integer.toOctalString(entry.mode) + " ").getBytes(StandardCharsets.US_ASCII));
            output.writeBytes(entry.name);
            output.write(0);
            ObjectId child = entry.mode == GITLINK_MODE ? entry.id : mapped(entry.id, converted);
            output.writeBytes(child.toBytes());
        }
        return output.toByteArray();
    }

    private static int compareTreeEntries(TreeEntry left, TreeEntry right) {
        int position = 0;
        while (true) {
            int first = treeNameByte(left, position);
            int second = treeNameByte(right, position);
            if (first != second) {
                return Integer.compare(first, second);
            }
            if (first == 0) {
                return 0;
            }
            position++;
        }
    }

    private static int treeNameByte(TreeEntry entry, int position) {
        if (position < entry.name.length) {
            return Byte.toUnsignedInt(entry.name[position]);
        }
        return position == entry.name.length && entry.mode == TREE_MODE ? '/' : 0;
    }

    private static List<TreeEntry> parseTree(byte[] payload) throws IOException {
        List<TreeEntry> entries = new ArrayList<>();
        int offset = 0;
        while (offset < payload.length) {
            int space = indexOf(payload, (byte) ' ', offset);
            int nul = indexOf(payload, (byte) 0, space + 1);
            if (space <= offset || nul <= space + 1 || nul + 1 + OBJECT_ID_BYTES > payload.length) {
                throw new IOException("Malformed tree entry");
            }
            int mode;
            try {
                String value = new String(payload, offset, space - offset, StandardCharsets.US_ASCII);
                mode = Integer.parseInt(value, 8);
            } catch (NumberFormatException failure) {
                throw new IOException("Malformed tree mode", failure);
            }
            if (mode != TREE_MODE && mode != GITLINK_MODE
                    && mode != 0100644 && mode != 0100755 && mode != 0120000) {
                throw new IOException("Unsupported tree mode: " + Integer.toOctalString(mode));
            }
            byte[] name = Arrays.copyOfRange(payload, space + 1, nul);
            for (byte value : name) {
                if (value == '/') {
                    throw new IOException("Tree entry name contains '/'");
                }
            }
            ObjectId id = new ObjectId(Arrays.copyOfRange(payload, nul + 1, nul + 1 + OBJECT_ID_BYTES));
            entries.add(new TreeEntry(mode, name, id));
            offset = nul + 1 + OBJECT_ID_BYTES;
        }
        return entries;
    }

    private static int indexOf(byte[] bytes, byte value, int from) {
        for (int index = Math.max(0, from); index < bytes.length; index++) {
            if (bytes[index] == value) {
                return index;
            }
        }
        return -1;
    }

    private static ObjectId mapped(ObjectId old, Map<String, String> converted) throws IOException {
        String current = converted.get(old.toHex());
        if (current == null) {
            throw new IOException("Referenced object was not converted: " + old);
        }
        return new ObjectId(current);
    }

    private static List<Dependency> commitDependencies(Headers headers) throws IOException {
        List<Dependency> dependencies = new ArrayList<>();
        boolean treeSeen = false;
        for (String line : headers.lines) {
            if (line.startsWith("tree ")) {
                if (treeSeen) {
                    throw new IOException("Commit has multiple trees");
                }
                dependencies.add(new Dependency(parseId(line, "tree "), GitObjectType.TREE));
                treeSeen = true;
            } else if (line.startsWith("parent ")) {
                dependencies.add(new Dependency(parseId(line, "parent "), GitObjectType.COMMIT));
            }
        }
        if (!treeSeen) {
            throw new IOException("Commit has no tree");
        }
        return dependencies;
    }

    private static byte[] convertCommit(Headers headers, Map<String, String> converted) throws IOException {
        boolean changed = false;
        boolean signed = false;
        List<String> lines = new ArrayList<>(headers.lines.size());
        for (String line : headers.lines) {
            if (line.startsWith("gpgsig ") || line.startsWith("gpgsig-sha256 ")
                    || line.startsWith("mergetag ")) {
                signed = true;
            }
            if (line.startsWith("tree ") || line.startsWith("parent ")) {
                int prefix = line.startsWith("tree ") ? 5 : 7;
                ObjectId original = parseId(line, line.substring(0, prefix));
                String replacement = mapped(original, converted).toHex();
                changed |= !replacement.equals(original.toHex());
                line = line.substring(0, prefix) + replacement;
            }
            lines.add(line);
        }
        if (changed && signed) {
            throw new IOException("Signed commit would require rewriting its signature");
        }
        return headers.render(lines);
    }

    private static List<Dependency> tagDependencies(Headers headers) throws IOException {
        String object = null;
        GitObjectType type = null;
        for (String line : headers.lines) {
            if (line.startsWith("object ")) {
                object = line;
            } else if (line.startsWith("type ")) {
                try {
                    type = GitObjectType.valueOf(line.substring(5).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException failure) {
                    throw new IOException("Unsupported tag target type", failure);
                }
            }
        }
        if (object == null || type == null) {
            throw new IOException("Tag has no supported target");
        }
        jgitType(type);
        return List.of(new Dependency(parseId(object, "object "), type));
    }

    private static byte[] convertTag(Headers headers, Map<String, String> converted) throws IOException {
        List<String> lines = new ArrayList<>(headers.lines);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.startsWith("object ")) {
                continue;
            }
            ObjectId original = parseId(line, "object ");
            String replacement = mapped(original, converted).toHex();
            if (!replacement.equals(original.toHex())) {
                if (headers.body.contains("-----BEGIN PGP SIGNATURE-----")
                        || headers.body.contains("-----BEGIN SSH SIGNATURE-----")
                        || headers.body.contains("-----BEGIN SIGNED MESSAGE-----")) {
                    throw new IOException("Signed tag would require rewriting its signature");
                }
                lines.set(index, "object " + replacement);
            }
            return headers.render(lines);
        }
        throw new IOException("Tag has no target");
    }

    private static ObjectId parseId(String line, String prefix) throws IOException {
        String value = line.substring(prefix.length());
        if (value.length() != OBJECT_ID_BYTES * 2) {
            throw new IOException("Malformed object ID in " + prefix.trim() + " header");
        }
        try {
            return new ObjectId(value);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Malformed object ID in " + prefix.trim() + " header", failure);
        }
    }

    private static Headers parseHeaders(byte[] payload) throws IOException {
        String text = new String(payload, StandardCharsets.ISO_8859_1);
        int separator = text.indexOf("\n\n");
        if (separator < 0) {
            throw new IOException("Git object has no header/body separator");
        }
        return new Headers(List.of(text.substring(0, separator).split("\n", -1)),
                text.substring(separator + 2));
    }

    private static void publishRefs(Repository target, Map<String, String> refs,
            Map<String, String> converted, String defaultHead) throws IOException {
        for (Map.Entry<String, String> ref : new TreeMap<>(refs).entrySet()) {
            if (!ref.getKey().startsWith("refs/")) {
                throw new IOException("Unsupported ref name: " + ref.getKey());
            }
            RefUpdate update = target.updateRef(ref.getKey());
            update.setNewObjectId(org.eclipse.jgit.lib.ObjectId.fromString(converted.get(ref.getValue())));
            if (update.update() != RefUpdate.Result.NEW) {
                throw new IOException("Cannot publish exported ref: " + ref.getKey());
            }
        }
        RefUpdate head = target.updateRef(Constants.HEAD, true);
        RefUpdate.Result result = head.link(defaultHead);
        if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED
                && result != RefUpdate.Result.NO_CHANGE) {
            throw new IOException("Cannot publish exported HEAD: " + result);
        }
    }

    private static void writeIdMap(Path output, Map<String, String> converted) throws IOException {
        Path directory = Files.createDirectory(output.resolve("orion-export"));
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(converted).entrySet()) {
            text.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
        }
        Files.writeString(directory.resolve("object-map.tsv"), text);
    }

    private static void verifyWithGit(Path bareRepository) throws IOException {
        Process process = new ProcessBuilder("git", "--git-dir", bareRepository.toString(), "fsck", "--full")
                .redirectErrorStream(true).start();
        String diagnostic = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (process.waitFor() != 0) {
                throw new IOException("Exported repository failed git fsck: " + diagnostic.trim());
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while verifying exported repository", failure);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return CONTINUE;
            }
        });
    }

    private record Dependency(ObjectId id, GitObjectType type) {}

    private record TreeEntry(int mode, byte[] name, ObjectId id) {}

    private record Headers(List<String> lines, String body) {
        private byte[] render(List<String> replacement) {
            return (String.join("\n", replacement) + "\n\n" + body).getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    private static final class Frame {
        private final ObjectId id;
        private final LooseObject object;
        private final List<Dependency> dependencies;
        private int nextDependency;

        private Frame(ObjectId id, LooseObject object, List<Dependency> dependencies) {
            this.id = id;
            this.object = object;
            this.dependencies = dependencies;
        }
    }
}
