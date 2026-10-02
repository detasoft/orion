package pro.deta.orion.git.proxy;

import pro.deta.orion.schema.orion.v2.Connection;
import pro.deta.orion.schema.orion.v2.GitProxyBinding;

/** The exact SSH definition used by a proxy operation, retained for revision-safe host-key decisions. */
public record ProxySshConnection(GitProxyBinding binding, Connection.Ssh connection) {}
