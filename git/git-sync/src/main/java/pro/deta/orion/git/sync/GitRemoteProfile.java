package pro.deta.orion.git.sync;

import pro.deta.orion.schema.orion.RepositoryRemote;

public interface GitRemoteProfile {
    GitRemoteConnection open(RepositoryRemote remote);
}
