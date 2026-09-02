package pro.deta.orion.agentd.protocol;

public record MachineInfo(String hostname, String os, String architecture) {
    public MachineInfo {
        hostname = ProtocolValidation.nonBlank(hostname, "hostname");
        os = ProtocolValidation.nonBlank(os, "os");
        architecture = ProtocolValidation.nonBlank(architecture, "architecture");
    }
}
