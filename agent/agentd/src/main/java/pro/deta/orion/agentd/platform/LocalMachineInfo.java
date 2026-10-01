package pro.deta.orion.agentd.platform;

import java.net.InetAddress;
import java.util.Objects;
import java.util.function.Supplier;

import pro.deta.orion.agent.protocol.MachineInfo;

public final class LocalMachineInfo {
    private static final int MAX_VALUE_LENGTH = 128;
    private final Supplier<String> hostname;

    public LocalMachineInfo() {
        this(LocalMachineInfo::lookupHostname);
    }

    LocalMachineInfo(Supplier<String> hostname) {
        this.hostname = Objects.requireNonNull(hostname, "hostname");
    }

    public MachineInfo read() {
        String host;
        try {
            host = bounded(hostname.get(), "unknown-host");
        } catch (RuntimeException failure) {
            host = "unknown-host";
        }
        return new MachineInfo(
                host,
                bounded(System.getProperty("os.name"), "unknown-os"),
                bounded(System.getProperty("os.arch"), "unknown-architecture"));
    }

    private static String lookupHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception failure) {
            return "unknown-host";
        }
    }

    private static String bounded(String value, String fallback) {
        String selected = value == null || value.isBlank() ? fallback : value.trim();
        return selected.length() <= MAX_VALUE_LENGTH ? selected : selected.substring(0, MAX_VALUE_LENGTH);
    }
}
