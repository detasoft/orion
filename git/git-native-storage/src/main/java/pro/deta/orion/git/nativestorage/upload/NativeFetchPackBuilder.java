package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.DeltaPackBuilder;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.NoDeltaPackBuilder;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class NativeFetchPackBuilder {
    private static final String DEFAULT_HEAD = "refs/heads/main";

    private final LooseRefStore refs;
    private final LooseObjectStore objects;
    private final String defaultHead;
    private final NoDeltaPackBuilder noDeltaPackBuilder;
    private final DeltaPackBuilder deltaPackBuilder;
    private final NativePackfileUriSource packfileUriSource;

    public NativeFetchPackBuilder(
            LooseRefStore refs,
            LooseObjectStore objects) {
        this(
                refs,
                objects,
                DEFAULT_HEAD,
                new NoDeltaPackBuilder(),
                new DeltaPackBuilder(),
                NativePackfileUriSource.NONE);
    }

    NativeFetchPackBuilder(
            LooseRefStore refs,
            LooseObjectStore objects,
            String defaultHead,
            NoDeltaPackBuilder noDeltaPackBuilder,
            DeltaPackBuilder deltaPackBuilder) {
        this(
                refs,
                objects,
                defaultHead,
                noDeltaPackBuilder,
                deltaPackBuilder,
                NativePackfileUriSource.NONE);
    }

    public NativeFetchPackBuilder(
            LooseRefStore refs,
            LooseObjectStore objects,
            NativePackfileUriSource packfileUriSource) {
        this(
                refs,
                objects,
                DEFAULT_HEAD,
                packfileUriSource);
    }

    public NativeFetchPackBuilder(
            LooseRefStore refs,
            LooseObjectStore objects,
            String defaultHead,
            NativePackfileUriSource packfileUriSource) {
        this(
                refs,
                objects,
                defaultHead,
                new NoDeltaPackBuilder(),
                new DeltaPackBuilder(),
                packfileUriSource);
    }

    NativeFetchPackBuilder(
            LooseRefStore refs,
            LooseObjectStore objects,
            String defaultHead,
            NoDeltaPackBuilder noDeltaPackBuilder,
            DeltaPackBuilder deltaPackBuilder,
            NativePackfileUriSource packfileUriSource) {
        this.refs = Objects.requireNonNull(refs, "refs");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.defaultHead = Objects.requireNonNull(defaultHead, "defaultHead");
        this.noDeltaPackBuilder = Objects.requireNonNull(
                noDeltaPackBuilder,
                "noDeltaPackBuilder");
        this.deltaPackBuilder = Objects.requireNonNull(
                deltaPackBuilder,
                "deltaPackBuilder");
        this.packfileUriSource = Objects.requireNonNull(
                packfileUriSource,
                "packfileUriSource");
    }

    public NativeFetchResponse build(NativeFetchRequest request) {
        Objects.requireNonNull(request, "request");
        validateDeepening(request);
        Map<String, String> refSnapshot = refs.snapshot();
        Map<String, GitObjectId> wantedRefs =
                resolveWantedRefs(request.wantRefs(), refSnapshot);
        Set<GitObjectId> deepenNotRoots = resolveDeepenNotRefs(
                request.deepenNotRefs(),
                refSnapshot);
        Set<GitObjectId> wants = new LinkedHashSet<>(request.wants());
        wants.addAll(wantedRefs.values());
        NativeObjectClosure closure = new NativeObjectClosure(objects);
        NativeObjectClosure.FetchSelection selection =
                closure.selectionFor(
                        wants,
                        request.haves(),
                        request.depth(),
                        request.clientShallowCommits(),
                        request.deepenRelative(),
                        request.deepenSince(),
                        deepenNotRoots,
                        request.objectFilter());
        Set<GitObjectId> objectIds = new LinkedHashSet<>(
                selection.objectIds());
        if (request.includeTag()) {
            includeReachableAnnotatedTags(objectIds);
        }
        NativePackfileUriSelection packfileUriSelection =
                packfileUriSelection(request, objectIds);
        objectIds.removeAll(packfileUriSelection.objectIds());
        NativePackProducer producer = request.ofsDelta()
                ? deltaPackBuilder.producer(
                        objects,
                        objectIds,
                        externalBaseIds(request, closure, objectIds))
                : noDeltaPackBuilder.producer(objects, objectIds);
        return new NativeFetchResponse(
                producer,
                selection.shallowBoundaries(),
                selection.unshallowBoundaries(),
                wantedRefs,
                packfileUriSelection.packfileUris());
    }

    private static void validateDeepening(
            NativeFetchRequest request) {
        if (request.depth() > 0
                && (request.deepenSince() >= 0
                || !request.deepenNotRefs().isEmpty())) {
            throw new GitUploadPackException(
                    GitUploadPackException.Kind.INVALID_REQUEST,
                    "Depth cannot be combined with time or ref deepening");
        }
        if (request.deepenRelative() && request.depth() == 0) {
            throw new GitUploadPackException(
                    GitUploadPackException.Kind.INVALID_REQUEST,
                    "Relative deepening requires a depth");
        }
    }

    private NativePackfileUriSelection packfileUriSelection(
            NativeFetchRequest request,
            Set<GitObjectId> objectIds) {
        if (request.packfileUriProtocols().isEmpty()) {
            return NativePackfileUriSelection.empty();
        }
        return packfileUriSource.select(
                objectIds,
                request.packfileUriProtocols());
    }

    private Map<String, GitObjectId> resolveWantedRefs(
            Set<String> wantRefs,
            Map<String, String> snapshot) {
        Map<String, GitObjectId> resolved = new LinkedHashMap<>();
        for (String wantRef : wantRefs) {
            String refName = "HEAD".equals(wantRef)
                    ? effectiveHeadTarget(snapshot)
                    : wantRef;
            String objectId = snapshot.get(refName);
            if (objectId == null) {
                throw new GitUploadPackException(
                        GitUploadPackException.Kind.MISSING_REF,
                        "Requested Git ref is unavailable: " + wantRef);
            }
            resolved.put(wantRef, GitObjectId.of(objectId));
        }
        return resolved;
    }

    private Set<GitObjectId> resolveDeepenNotRefs(
            Set<String> deepenNotRefs,
            Map<String, String> snapshot) {
        Set<GitObjectId> resolved = new LinkedHashSet<>();
        for (String deepenNotRef : deepenNotRefs) {
            String refName = "HEAD".equals(deepenNotRef)
                    ? effectiveHeadTarget(snapshot)
                    : deepenNotRef;
            if (!"HEAD".equals(deepenNotRef)
                    && !deepenNotRef.startsWith("refs/")) {
                throw new GitUploadPackException(
                        GitUploadPackException.Kind.INVALID_REQUEST,
                        "Unsupported deepen-not revision: " + deepenNotRef);
            }
            String objectId = snapshot.get(refName);
            if (objectId == null) {
                throw new GitUploadPackException(
                        GitUploadPackException.Kind.MISSING_REF,
                        "Requested Git ref is unavailable: " + deepenNotRef);
            }
            resolved.add(GitObjectId.of(objectId));
        }
        return resolved;
    }

    private String effectiveHeadTarget(Map<String, String> snapshot) {
        if (snapshot.containsKey(defaultHead)) {
            return defaultHead;
        }
        List<String> refNames = new ArrayList<>(snapshot.keySet());
        refNames.sort(String::compareTo);
        for (String refName : refNames) {
            if (refName.startsWith("refs/heads/")) {
                return refName;
            }
        }
        return defaultHead;
    }

    private Set<GitObjectId> externalBaseIds(
            NativeFetchRequest request,
            NativeObjectClosure closure,
            Set<GitObjectId> objectIds) {
        if (!request.thinPack()
                || !request.ofsDelta()
                || request.shallow()) {
            return Set.of();
        }
        Set<GitObjectId> externalBaseIds =
                new LinkedHashSet<>(request.haves());
        externalBaseIds.addAll(
                closure.existingObjectIdsReachableFrom(request.haves()));
        externalBaseIds.removeAll(objectIds);
        return externalBaseIds;
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
