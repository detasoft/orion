package pro.deta.orion.agent.protocol;

public record WorkspaceId(String value) {
    public WorkspaceId {
        value = ProtocolValidation.identifier(value, "workspaceId");
    }
}
