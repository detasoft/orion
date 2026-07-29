package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class NativeObjectClosure {
    private static final int RAW_OBJECT_ID_BYTES = 20;

    private final LooseObjectStore objects;

    public NativeObjectClosure(LooseObjectStore objects) {
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    public List<LooseObject> objectsFor(Set<GitObjectId> wants, Set<GitObjectId> haves) {
        Objects.requireNonNull(wants, "wants");
        Objects.requireNonNull(haves, "haves");

        Set<GitObjectId> wantedClosure = traverse(wants);
        wantedClosure.removeAll(traverse(haves));

        List<LooseObject> result = new ArrayList<>();
        for (GitObjectId id : wantedClosure) {
            result.add(requireObject(id));
        }
        result.sort(Comparator.comparing(object -> object.id().value()));
        return List.copyOf(result);
    }

    private Set<GitObjectId> traverse(Set<GitObjectId> roots) {
        Set<GitObjectId> visited = new HashSet<>();
        ArrayDeque<GitObjectId> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            GitObjectId id = pending.removeFirst();
            if (!visited.add(id)) {
                continue;
            }
            LooseObject object = requireObject(id);
            switch (object.type()) {
                case COMMIT -> addCommitReferences(object.data(), pending);
                case TREE -> addTreeReferences(object.data(), pending);
                case BLOB, TAG -> {
                }
            }
        }
        return visited;
    }

    private LooseObject requireObject(GitObjectId id) {
        return objects.read(id).orElseThrow(() ->
                new GitUploadPackException(
                        GitUploadPackException.Kind.MISSING_OBJECT,
                        "Requested Git object is unavailable"));
    }

    private static void addCommitReferences(byte[] data, ArrayDeque<GitObjectId> pending) {
        String commit = new String(data, StandardCharsets.US_ASCII);
        String[] lines = commit.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) {
                return;
            }
            if (line.startsWith("tree ")) {
                pending.addLast(GitObjectId.of(line.substring("tree ".length())));
            } else if (line.startsWith("parent ")) {
                pending.addLast(GitObjectId.of(line.substring("parent ".length())));
            }
        }
    }

    private static void addTreeReferences(byte[] data, ArrayDeque<GitObjectId> pending) {
        int offset = 0;
        while (offset < data.length) {
            int nul = indexOf(data, (byte) 0, offset);
            if (nul < 0 || nul + 1 + RAW_OBJECT_ID_BYTES > data.length) {
                throw new IllegalArgumentException("Malformed Git tree object");
            }
            byte[] rawId = new byte[RAW_OBJECT_ID_BYTES];
            System.arraycopy(data, nul + 1, rawId, 0, RAW_OBJECT_ID_BYTES);
            pending.addLast(GitObjectId.of(HexFormat.of().formatHex(rawId)));
            offset = nul + 1 + RAW_OBJECT_ID_BYTES;
        }
    }

    private static int indexOf(byte[] data, byte target, int offset) {
        for (int i = offset; i < data.length; i++) {
            if (data[i] == target) {
                return i;
            }
        }
        return -1;
    }
}
