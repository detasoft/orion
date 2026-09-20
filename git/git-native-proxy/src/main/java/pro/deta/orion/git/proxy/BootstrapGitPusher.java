package pro.deta.orion.git.proxy;

import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.PackId;

import java.util.List;
import java.util.Optional;

@FunctionalInterface
interface BootstrapGitPusher {
    List<Boolean> push(
            BootstrapGitLocation location,
            GitClientTransport transport,
            NativeGitRepository repository,
            Optional<PackId> received,
            List<RefUpdate> updates,
            boolean atomic) throws Exception;
}
