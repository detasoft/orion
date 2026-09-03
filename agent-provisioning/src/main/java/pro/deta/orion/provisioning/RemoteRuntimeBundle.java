package pro.deta.orion.provisioning;

public record RemoteRuntimeBundle(
        String version,
        RemotePlatform platform,
        RuntimeArtifact agentd,
        RuntimeArtifact sessionHost
) {
    public RemoteRuntimeBundle {
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Runtime bundle version is invalid");
        }
        if (platform == null || agentd == null || sessionHost == null) {
            throw new IllegalArgumentException("Runtime bundle fields must not be null");
        }
        if (!"agentd".equals(agentd.remoteName()) || !"session-host".equals(sessionHost.remoteName())) {
            throw new IllegalArgumentException("Runtime artifacts must be named agentd and session-host");
        }
    }
}
