package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.NoDeltaPackBuilder;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class NativeFetchPackBuilder {
    private final LooseRefStore refs;
    private final LooseObjectStore objects;
    private final NoDeltaPackBuilder packBuilder;

    public NativeFetchPackBuilder(
            LooseRefStore refs,
            LooseObjectStore objects) {
        this(
                refs,
                objects,
                new NoDeltaPackBuilder());
    }

    NativeFetchPackBuilder(
            LooseRefStore refs,
            LooseObjectStore objects,
            NoDeltaPackBuilder packBuilder) {
        this.refs = Objects.requireNonNull(refs, "refs");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.packBuilder = Objects.requireNonNull(
                packBuilder,
                "packBuilder");
    }

    public NativeFetchResponse build(NativeFetchRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, GitObjectId> wantedRefs =
                resolveWantedRefs(request.wantRefs());
        Set<GitObjectId> wants = new LinkedHashSet<>(request.wants());
        wants.addAll(wantedRefs.values());
        NativeObjectClosure.FetchSelection selection =
                new NativeObjectClosure(objects).selectionFor(
                        wants,
                        request.haves(),
                        request.depth(),
                        request.objectFilter());
        Set<GitObjectId> objectIds = new LinkedHashSet<>(
                selection.objectIds());
        if (request.includeTag()) {
            includeReachableAnnotatedTags(objectIds);
        }
        // Fetch currently uses a self-contained whole-object pack even when a
        // client requests thin-pack or ofs-delta capabilities.
        return new NativeFetchResponse(
                packBuilder.producer(objects, objectIds),
                selection.shallowBoundaries(),
                wantedRefs);
    }

    private Map<String, GitObjectId> resolveWantedRefs(
            Set<String> wantRefs) {
        Map<String, String> snapshot = refs.snapshot();
        Map<String, GitObjectId> resolved = new LinkedHashMap<>();
        for (String wantRef : wantRefs) {
            String objectId = snapshot.get(wantRef);
            if (objectId == null) {
                throw new GitUploadPackException(
                        GitUploadPackException.Kind.MISSING_REF,
                        "Requested Git ref is unavailable: " + wantRef);
            }
            resolved.put(wantRef, GitObjectId.of(objectId));
        }
        return resolved;
    }

    private void includeReachableAnnotatedTags(
            Set<GitObjectId> objectIds) {
        for (Map.Entry<String, String> ref : refs.snapshot().entrySet()) {
            if (!ref.getKey().startsWith("refs/tags/")) {
                continue;
            }
            GitObjectId tagId = GitObjectId.of(ref.getValue());
            Optional<LooseObject> candidate = objects.read(tagId);
            if (candidate.isEmpty()
                    || candidate.get().type() != ObjectType.TAG) {
                continue;
            }
            Set<GitObjectId> tagChain = new LinkedHashSet<>();
            GitObjectId target = tagId;
            while (true) {
                Optional<LooseObject> object = objects.read(target);
                if (object.isEmpty()
                        || object.get().type() != ObjectType.TAG
                        || !tagChain.add(target)) {
                    break;
                }
                target = tagTarget(object.get().data());
            }
            if (objectIds.contains(target)) {
                objectIds.addAll(tagChain);
            }
        }
    }

    private static GitObjectId tagTarget(byte[] data) {
        String prefix = "object ";
        int idLength = 40;
        int newline = prefix.length() + idLength;
        if (data.length <= newline || data[newline] != '\n') {
            throw new IllegalArgumentException("Malformed Git tag object");
        }
        String line = new String(data, 0, newline, StandardCharsets.US_ASCII);
        if (!line.startsWith(prefix)) {
            throw new IllegalArgumentException("Malformed Git tag object");
        }
        return GitObjectId.of(line.substring(prefix.length()));
    }
}
