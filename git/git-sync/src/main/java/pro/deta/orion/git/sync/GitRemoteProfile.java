package pro.deta.orion.git.sync;

import pro.deta.orion.schema.orion.v2.RepositoryRemote;

public interface GitRemoteProfile {
    GitRemoteConnection open(RepositoryRemote remote);
}
