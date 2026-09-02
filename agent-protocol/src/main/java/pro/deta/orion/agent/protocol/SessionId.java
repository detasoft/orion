package pro.deta.orion.agent.protocol;

public record SessionId(String value) {
    public SessionId {
        value = ProtocolValidation.identifier(value, "sessionId");
    }
}
