package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.common.GitObjectId;

import java.util.Set;

public interface NativePackfileUriSource {
    NativePackfileUriSource NONE =
            (objectIds, acceptedProtocols) -> NativePackfileUriSelection.empty();

    NativePackfileUriSelection select(
            Set<GitObjectId> objectIds,
            Set<String> acceptedProtocols);
}
