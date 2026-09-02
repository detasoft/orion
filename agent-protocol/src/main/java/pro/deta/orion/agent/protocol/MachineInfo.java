package pro.deta.orion.agent.protocol;

public record MachineInfo(String hostname, String operatingSystem, String architecture) {
    public MachineInfo {
        hostname = ProtocolValidation.nonBlank(hostname, "hostname");
        operatingSystem = ProtocolValidation.nonBlank(operatingSystem, "operatingSystem");
        architecture = ProtocolValidation.nonBlank(architecture, "architecture");
    }
}
