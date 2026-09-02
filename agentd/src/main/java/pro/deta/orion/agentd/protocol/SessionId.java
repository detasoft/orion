package pro.deta.orion.agentd.protocol;

public record SessionId(String value) {
    public SessionId {
        value = ProtocolValidation.identifier(value, "sessionId");
    }
}
