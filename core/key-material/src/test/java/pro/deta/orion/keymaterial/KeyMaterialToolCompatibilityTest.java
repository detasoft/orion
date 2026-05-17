package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KeyMaterialToolCompatibilityTest {
    @TempDir
    private Path tempDir;

    @Test
    void pkcs12StoreCanBeListedByKeytool() throws Exception {
        Path keytool = keytoolPath();
        assumeTrue(Files.isExecutable(keytool), "keytool is not available");
        Path keyStorePath = createLocalPkcs12(KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS);

        ProcessResult result = run(List.of(
                keytool.toString(),
                "-list",
                "-storetype",
                KeyMaterialConstants.DEFAULT_KEY_STORE_TYPE,
                "-keystore",
                keyStorePath.toString(),
                "-storepass",
                KeyMaterialTestConstants.PASSWORD_VALUE));

        assertThat(result.exitCode()).describedAs(result.output()).isZero();
        assertThat(result.output()).contains(KeyMaterialTestConstants.SERVER_SIGNING_2026_05_ALIAS);
    }

    @Test
    void pkcs12StoreCanBeInspectedByOpenSsl() throws Exception {
        assumeTrue(commandSucceeds(List.of(
                KeyMaterialTestConstants.OPENSSL_EXECUTABLE,
                KeyMaterialTestConstants.OPENSSL_VERSION_ARGUMENT)), "openssl is not available");
        Path keyStorePath = createLocalPkcs12(KeyMaterialTestConstants.HTTPS_2026_05_ALIAS);

        ProcessResult result = run(List.of(
                KeyMaterialTestConstants.OPENSSL_EXECUTABLE,
                "pkcs12",
                "-in",
                keyStorePath.toString(),
                "-passin",
                "pass:" + KeyMaterialTestConstants.PASSWORD_VALUE,
                "-nokeys",
                "-info",
                "-noout"));

        assertThat(result.exitCode()).describedAs(result.output()).isZero();
    }

    private Path createLocalPkcs12(String alias) throws Exception {
        Path keyStorePath = tempDir.resolve(alias + KeyMaterialTestConstants.PKCS12_EXTENSION);
        LocalKeyMaterialContentStore store = new LocalKeyMaterialContentStore(keyStorePath);
        KeyMaterialService service = KeyMaterialService.open(
                store,
                KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password(), true));
        service.generateKeyIfMissing(alias, KeyMaterialKeySpec.rsa(KeyMaterialTestConstants.TOOL_COMPATIBILITY_PURPOSE));
        service.save();
        return keyStorePath;
    }

    private static Path keytoolPath() {
        String executable = OS.current() == OS.WINDOWS
                ? KeyMaterialTestConstants.KEYTOOL_WINDOWS_EXECUTABLE
                : KeyMaterialTestConstants.KEYTOOL_EXECUTABLE;
        return Path.of(
                System.getProperty(KeyMaterialTestConstants.JAVA_HOME_PROPERTY),
                KeyMaterialTestConstants.JAVA_BIN_DIRECTORY,
                executable);
    }

    private static boolean commandSucceeds(List<String> command) {
        try {
            return run(command).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static ProcessResult run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(KeyMaterialTestConstants.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("Command timed out: " + command);
        }
        String output = new String(process.getInputStream().readAllBytes());
        return new ProcessResult(process.exitValue(), output);
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
