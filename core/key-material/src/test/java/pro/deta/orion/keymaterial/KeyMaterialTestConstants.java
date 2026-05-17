package pro.deta.orion.keymaterial;

import pro.deta.orion.resource.address.ResourceAddressConstants;
import pro.deta.orion.resource.address.ResourceScheme;

import java.nio.file.Path;
import java.util.Base64;

final class KeyMaterialTestConstants {
    static final String PASSWORD_VALUE = "changeit";
    static final String PASSWORD_WITH_LINE_BREAK = PASSWORD_VALUE + "\n";
    static final String KEY_STORE_FILE_NAME = "orion.p12";
    static final String PLAIN_KEY_STORE_FILE_NAME = "plain.p12";
    static final String FILE_KEY_STORE_FILE_NAME = "file.p12";
    static final String ENV_KEY_STORE_FILE_NAME = "env.p12";
    static final String PASSWORD_FILE_NAME = "password.txt";
    static final String PKCS12_EXTENSION = ".p12";
    static final String ORION_KEYSTORE_LOCATION_ENV = "ORION_KEYSTORE_LOCATION";
    static final String ORION_KEYSTORE_PASSWORD_ENV = "ORION_KEYSTORE_PASSWORD";
    static final String SERVER_SIGNING_PURPOSE = "server-signing";
    static final String SSH_HOST_PURPOSE = "ssh-host";
    static final String HTTPS_PURPOSE = "https";
    static final String CA_ISSUER_PURPOSE = "ca-issuer";
    static final String ACME_ACCOUNT_PURPOSE = "acme-account";
    static final String TOOL_COMPATIBILITY_PURPOSE = "tool-compatibility";
    static final String SERVER_SIGNING_2026_04_ALIAS = "server-signing-2026-04";
    static final String SERVER_SIGNING_2026_05_ALIAS = "server-signing-2026-05";
    static final String SERVER_SIGNING_2026_06_ALIAS = "server-signing-2026-06";
    static final String SSH_HOST_RSA_2026_05_ALIAS = "ssh-host-rsa-2026-05";
    static final String HTTPS_2026_05_ALIAS = "https-2026-05";
    static final String ORION_CA_2026_05_ALIAS = "orion-ca-2026-05";
    static final String ORION_CA_CERT_2026_05_ALIAS = "orion-ca-cert-2026-05";
    static final String BAD_SERVER_SIGNING_ALIAS = "bad-server-signing";
    static final String KEYTOOL_EXECUTABLE = "keytool";
    static final String KEYTOOL_WINDOWS_EXECUTABLE = "keytool.exe";
    static final String OPENSSL_EXECUTABLE = "openssl";
    static final String OPENSSL_VERSION_ARGUMENT = "version";
    static final String JAVA_HOME_PROPERTY = "java.home";
    static final String JAVA_BIN_DIRECTORY = "bin";
    static final long COMMAND_TIMEOUT_SECONDS = 10;

    private KeyMaterialTestConstants() {
    }

    static char[] password() {
        return PASSWORD_VALUE.toCharArray();
    }

    static String envReference(String environmentVariableName) {
        return ResourceScheme.ENV.value() + ":" + environmentVariableName;
    }

    static String fileReference(Path path) {
        return ResourceScheme.FILE.value() + ":" + path;
    }

    static String contentBase64Reference(byte[] bytes) {
        return ResourceScheme.CONTENT.value()
                + ":"
                + ResourceAddressConstants.BASE64_CONTENT_PREFIX
                + Base64.getEncoder().encodeToString(bytes);
    }
}
