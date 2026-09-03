package pro.deta.orion.provisioning;

import java.util.Locale;

public enum RemotePlatform {
    LINUX_X86_64,
    LINUX_AARCH64,
    MACOS_X86_64,
    MACOS_AARCH64;

    public static RemotePlatform parse(String operatingSystem, String architecture) {
        String os = normalize(operatingSystem);
        String arch = normalize(architecture);
        if ("linux".equals(os) && ("x86_64".equals(arch) || "amd64".equals(arch))) {
            return LINUX_X86_64;
        }
        if ("linux".equals(os) && ("aarch64".equals(arch) || "arm64".equals(arch))) {
            return LINUX_AARCH64;
        }
        if ("darwin".equals(os) && ("x86_64".equals(arch) || "amd64".equals(arch))) {
            return MACOS_X86_64;
        }
        if ("darwin".equals(os) && ("aarch64".equals(arch) || "arm64".equals(arch))) {
            return MACOS_AARCH64;
        }
        throw new IllegalArgumentException(
                "Unsupported remote platform: " + safe(operatingSystem) + "/" + safe(architecture));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Unsupported remote platform response");
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "?");
    }
}
