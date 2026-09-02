package pro.deta.orion.agentd.protocol;

public record ConnectionId(String value) {
    public ConnectionId {
        value = ProtocolValidation.identifier(value, "connectionId");
    }
}
