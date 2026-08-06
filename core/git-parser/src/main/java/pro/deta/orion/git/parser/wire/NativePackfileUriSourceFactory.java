package pro.deta.orion.git.parser.wire;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUriSource;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;

public interface NativePackfileUriSourceFactory {
    NativePackfileUriSourceFactory NONE =
            (data, repository) -> NativePackfileUriSource.NONE;

    NativePackfileUriSource sourceFor(
            InitialRequestData data,
            NativeGitRepository repository);
}
