package pro.deta.orion.provisioning;

public final class PosixShell {
    private PosixShell() {
    }

    public static String quote(String value) {
        if (value == null || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("POSIX shell value contains an invalid character");
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
