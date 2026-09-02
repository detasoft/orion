package pro.deta.orion.agentd.protocol;

public record CommandId(String value) {
    public CommandId {
        value = ProtocolValidation.identifier(value, "commandId");
    }
}
