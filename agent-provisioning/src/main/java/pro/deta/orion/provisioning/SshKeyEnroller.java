package pro.deta.orion.provisioning;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.keymaterial.SshClientKeyCapability;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Optional;
import java.util.function.Function;

public final class SshKeyEnroller {
    private static final int INVALID_KEY_INPUT_EXIT = 20;
    private static final int UNSAFE_SSH_DIRECTORY_EXIT = 30;
    private static final int UNSAFE_AUTHORIZED_KEYS_EXIT = 31;
    private static final int REMOTE_WRITE_EXIT = 40;
    private static final String ENROLLMENT_COMMAND = """
            set -eu
            set -f
            umask 077
            IFS= read -r key || exit 20
            if IFS= read -r extra; then exit 20; fi
            set -- $key
            [ "$#" -eq 2 ] || exit 20
            algorithm=$1
            blob=$2
            case "$algorithm" in ssh-*|ecdsa-*|sk-*) ;; *) exit 20 ;; esac
            case "$blob" in ''|*[!A-Za-z0-9+/=]*) exit 20 ;; esac
            ssh_directory=$HOME/.ssh
            authorized_keys=$ssh_directory/authorized_keys
            [ ! -L "$ssh_directory" ] || exit 30
            if [ -e "$ssh_directory" ]; then
              [ -d "$ssh_directory" ] || exit 30
            else
              mkdir -m 700 "$ssh_directory" || exit 40
            fi
            [ ! -L "$authorized_keys" ] || exit 31
            if [ -e "$authorized_keys" ]; then
              [ -f "$authorized_keys" ] || exit 31
            else
              (umask 077; : > "$authorized_keys") || exit 40
            fi
            if awk -v target_algorithm="$algorithm" -v target_blob="$blob" '
              function authorized_field(line, wanted,   c, escaped, field, position, quoted, started, token) {
                for (position = 1; position <= length(line); position++) {
                  c = substr(line, position, 1)
                  if (escaped) {
                    token = token c
                    escaped = 0
                    started = 1
                  } else if (quoted && c == "\\\\") {
                    token = token c
                    escaped = 1
                    started = 1
                  } else if (c == "\\\"") {
                    token = token c
                    quoted = !quoted
                    started = 1
                  } else if (!quoted && c ~ /[[:space:]]/) {
                    if (started) {
                      field++
                      if (field == wanted) return token
                      token = ""
                      started = 0
                    }
                  } else {
                    token = token c
                    started = 1
                  }
                }
                if (started && ++field == wanted) return token
                return ""
              }
              /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
              {
                first = authorized_field($0, 1)
                second = authorized_field($0, 2)
                if (first == target_algorithm) candidate = second
                else if (second == target_algorithm) candidate = authorized_field($0, 3)
                else candidate = ""
                if (candidate == target_blob) found = 1
              }
              END { exit found ? 0 : 1 }
            ' "$authorized_keys"; then
              exit 0
            else
              awk_status=$?
              [ "$awk_status" -eq 1 ] || exit 40
            fi
            if [ -s "$authorized_keys" ] &&
               [ "$(tail -c 1 "$authorized_keys" | wc -l | tr -d ' ')" -eq 0 ]; then
              printf '\n' >> "$authorized_keys" || exit 40
            fi
            printf '%s\n' "$key" >> "$authorized_keys" || exit 40
            """;
    private final Function<PublicKey, String> keyFormatter;

    public SshKeyEnroller() {
        this(PublicKeyEntry::toString);
    }

    private SshKeyEnroller(Function<PublicKey, String> keyFormatter) {
        if (keyFormatter == null) {
            throw new IllegalArgumentException("SSH public key formatter must not be null");
        }
        this.keyFormatter = keyFormatter;
    }

    @TestOnly
    static SshKeyEnroller withKeyFormatterForTest(Function<PublicKey, String> keyFormatter) {
        return new SshKeyEnroller(keyFormatter);
    }

    public void enroll(
            SshEndpoint endpoint,
            SshClientKeyCapability keyCapability,
            Optional<BootstrapPassword> bootstrapPassword,
            ProvisioningOptions options) throws SshKeyEnrollmentException {
        if (bootstrapPassword == null) {
            throw new IllegalArgumentException("Bootstrap password option must not be null");
        }
        try {
            if (endpoint == null || keyCapability == null || options == null) {
                throw new IllegalArgumentException("SSH key enrollment arguments must not be null");
            }
            KeyPair selectedKey = selectedKey(keyCapability);
            if (alreadyEnrolled(endpoint, selectedKey, options)) {
                return;
            }
            if (bootstrapPassword.isEmpty()) {
                throw new SshKeyEnrollmentException(
                        EnrollmentFailure.BOOTSTRAP_PASSWORD_REQUIRED,
                        "SSH public key is not enrolled and no bootstrap password was supplied");
            }
            appendKey(endpoint, selectedKey, bootstrapPassword.orElseThrow(), options);
            verifyEnrollment(endpoint, selectedKey, options);
        } finally {
            bootstrapPassword.ifPresent(BootstrapPassword::close);
        }
    }

    private static KeyPair selectedKey(SshClientKeyCapability keyCapability)
            throws SshKeyEnrollmentException {
        try {
            KeyPair keyPair = keyCapability.keyPair();
            if (keyPair == null || keyPair.getPrivate() == null || keyPair.getPublic() == null) {
                throw new GeneralSecurityException("Incomplete SSH client key pair");
            }
            return keyPair;
        } catch (GeneralSecurityException error) {
            throw new SshKeyEnrollmentException(
                    EnrollmentFailure.KEY_MATERIAL, "SSH client key material is unavailable");
        }
    }

    private static boolean alreadyEnrolled(
            SshEndpoint endpoint,
            KeyPair selectedKey,
            ProvisioningOptions options) throws SshKeyEnrollmentException {
        try (MinaSshOperation ignored = MinaSshOperation.open(
                endpoint, new SshCredentials(selectedKey), options)) {
            return true;
        } catch (ProvisioningException error) {
            if (error.failure() == ProvisioningFailure.AUTHENTICATION) {
                return false;
            }
            throw map(error);
        }
    }

    private void appendKey(
            SshEndpoint endpoint,
            KeyPair selectedKey,
            BootstrapPassword password,
            ProvisioningOptions options) throws SshKeyEnrollmentException {
        byte[] keyLine = (keyFormatter.apply(selectedKey.getPublic()) + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        try (MinaSshOperation operation = MinaSshOperation.openWithPassword(endpoint, password, options)) {
            RemoteCommandResult result = operation.execute(ENROLLMENT_COMMAND, keyLine);
            requireEnrollmentSuccess(result.exitCode());
        } catch (ProvisioningException error) {
            throw map(error);
        }
    }

    private static void requireEnrollmentSuccess(int exitCode) throws SshKeyEnrollmentException {
        switch (exitCode) {
            case 0 -> { }
            case INVALID_KEY_INPUT_EXIT -> throw new SshKeyEnrollmentException(
                    EnrollmentFailure.KEY_MATERIAL,
                    "Generated SSH public key input was rejected");
            case UNSAFE_SSH_DIRECTORY_EXIT -> throw new SshKeyEnrollmentException(
                    EnrollmentFailure.UNSAFE_REMOTE_STATE,
                    "Remote SSH directory state is unsafe");
            case UNSAFE_AUTHORIZED_KEYS_EXIT -> throw new SshKeyEnrollmentException(
                    EnrollmentFailure.UNSAFE_REMOTE_STATE,
                    "Remote authorized_keys state is unsafe");
            case REMOTE_WRITE_EXIT -> throw new SshKeyEnrollmentException(
                    EnrollmentFailure.REMOTE_WRITE,
                    "Remote authorized_keys write failed");
            default -> throw new SshKeyEnrollmentException(
                    EnrollmentFailure.REMOTE_WRITE,
                    "Remote SSH key enrollment returned an unexpected status");
        }
    }

    private static void verifyEnrollment(
            SshEndpoint endpoint,
            KeyPair selectedKey,
            ProvisioningOptions options) throws SshKeyEnrollmentException {
        try (MinaSshOperation ignored = MinaSshOperation.open(
                endpoint, new SshCredentials(selectedKey), options)) {
            // Successful authentication in this fresh session is the verification boundary.
        } catch (ProvisioningException error) {
            if (error.failure() == ProvisioningFailure.AUTHENTICATION) {
                throw new SshKeyEnrollmentException(
                        EnrollmentFailure.VERIFICATION,
                        "SSH public-key enrollment could not be verified");
            }
            throw map(error);
        }
    }

    private static SshKeyEnrollmentException map(ProvisioningException error) {
        EnrollmentFailure failure = switch (error.failure()) {
            case HOST_IDENTITY -> EnrollmentFailure.HOST_IDENTITY;
            case AUTHENTICATION -> EnrollmentFailure.AUTHENTICATION;
            case TIMEOUT -> EnrollmentFailure.TIMEOUT;
            case CONNECTION -> EnrollmentFailure.CONNECTION;
            default -> EnrollmentFailure.REMOTE_WRITE;
        };
        return new SshKeyEnrollmentException(failure, message(failure));
    }

    private static String message(EnrollmentFailure failure) {
        return switch (failure) {
            case CONNECTION -> "SSH connection failed during key enrollment";
            case HOST_IDENTITY -> "SSH server host key was rejected during key enrollment";
            case AUTHENTICATION -> "SSH bootstrap authentication failed";
            case TIMEOUT -> "SSH key enrollment timed out";
            default -> "Remote SSH key enrollment failed";
        };
    }
}
