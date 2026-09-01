package pro.deta.orion.git.parser.wire;

import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.LegacyReceivePack;
import pro.deta.orion.git.parser.wire.exchange.LsRefsRequest;

import java.util.List;
import java.util.Objects;

public interface GitNativeRepositoryService {
    GitV1Advertisement legacyUploadPackAdvertisement(
            InitialRequestData data,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration);

    GitV1Advertisement legacyReceivePackAdvertisement(
            InitialRequestData data,
            GitNativeRepositoryAccessHook accessHook,
            GitWireConfiguration configuration);

    NativePackProducer legacyUploadPack(
            InitialRequestData data,
            NativeFetchRequest request,
            GitNativeRepositoryAccessHook accessHook);

    NativeFetchResponse legacyUploadFetch(
            InitialRequestData data,
            NativeFetchRequest request,
            GitNativeRepositoryAccessHook accessHook);

    NativeFetchResponse protocolV2Fetch(
            InitialRequestData data,
            NativeFetchRequest request,
            GitNativeRepositoryAccessHook accessHook,
            NativePackfileUriSourceFactory packfileUriSourceFactory);

    List<GitObjectId> protocolV2FetchAcknowledgments(
            InitialRequestData data,
            NativeFetchRequest request,
            GitNativeRepositoryAccessHook accessHook);

    List<GitObjectId> commonHaves(
            InitialRequestData data,
            Iterable<GitObjectId> haves,
            GitNativeRepositoryAccessHook accessHook);

    boolean legacyUploadReady(
            InitialRequestData data,
            Iterable<GitObjectId> wants,
            Iterable<GitObjectId> commonHaves,
            GitNativeRepositoryAccessHook accessHook);

    PackIngestionSession beginLegacyReceivePack(
            InitialRequestData data,
            GitNativeRepositoryAccessHook accessHook);

    List<ReceivePackStatus> completeLegacyReceivePack(
            LegacyReceivePack receivePack,
            GitNativeRepositoryAccessHook accessHook);

    GitLsRefsResponse lsRefs(
            InitialRequestData data,
            LsRefsRequest request,
            GitNativeRepositoryAccessHook accessHook);

    record ReceivePackStatus(
            String refName,
            boolean ok,
            String message) {
        public ReceivePackStatus {
            Objects.requireNonNull(refName, "refName");
            Objects.requireNonNull(message, "message");
        }
    }
}
