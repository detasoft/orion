package pro.deta.orion.agent.protocol;

public record CommandId(String value) {
    public CommandId {
        value = ProtocolValidation.identifier(value, "commandId");
    }
}
