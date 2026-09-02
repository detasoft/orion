package pro.deta.orion.agent.protocol;

public record ConnectionId(String value) {
    public ConnectionId {
        value = ProtocolValidation.identifier(value, "connectionId");
    }
}
