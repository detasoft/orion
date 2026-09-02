package pro.deta.orion.agentd.protocol;

public record WorkspaceId(String value) {
    public WorkspaceId {
        value = ProtocolValidation.identifier(value, "workspaceId");
    }
}
