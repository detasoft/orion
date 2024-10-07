package pro.deta.orion.git;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import org.apache.sshd.common.config.keys.KeyEntryResolver;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.io.IoServiceFactoryFactory;
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.io.output.SecureByteArrayOutputStream;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.common.util.security.bouncycastle.BouncyCastleSecurityProviderRegistrar;
import org.apache.sshd.common.util.security.eddsa.EdDSASecurityProviderRegistrar;
import org.apache.sshd.common.util.security.eddsa.OpenSSHEd25519PrivateKeyEntryDecoder;
import org.apache.sshd.mina.MinaServiceFactoryFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.AsyncAuthException;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.password.PasswordChangeRequiredException;
import org.apache.sshd.server.auth.pubkey.CachingPublicKeyAuthenticator;
import org.apache.sshd.server.forward.StaticDecisionForwardingFilter;
import org.apache.sshd.server.session.ServerSession;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import pro.deta.orion.config.SshTransportConfig;
import pro.deta.orion.git.ssh.SshCommandFactory;
import pro.deta.orion.git.util.FileKeyPairProvider;
import pro.deta.orion.util.OrionPathResolver;
import pro.deta.orion.util.OrionUtils;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.text.MessageFormat;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

@Slf4j
@RequiredArgsConstructor
public class GitSshTransportService implements AutoCloseable {
    private static final int BACKLOG = 2;
    private final SshTransportConfig config;
    private final OrionPathResolver orionPathResolver;
    private final GitInternalService gitInternalService;
    private final Executor executor;
    private final SshServer sshd = SshServer.setUpDefaultServer();

    public void start() {
        try {
            SecurityUtils.registerSecurityProvider(new BouncyCastleSecurityProviderRegistrar());
            if (SecurityUtils.isBouncyCastleRegistered()) {
                log.info("BouncyCastle is registered as a JCE provider");
            }
            SecurityUtils.registerSecurityProvider(new EdDSASecurityProviderRegistrar());
            if (SecurityUtils.isProviderRegistered("EdDSA")) {
                log.info("EdDSA is registered as a JCE provider");
            }

            FileKeyPairProvider hostKeyPairProvider = generateKeys();

            System.setProperty(IoServiceFactoryFactory.class.getName(), Nio2ServiceFactoryFactory.class.getName());
//            System.setProperty(IoServiceFactoryFactory.class.getName(), MinaServiceFactoryFactory.class.getName());

            // Create the socket address for binding the SSH server
            InetSocketAddress addr;
            if (OrionUtils.isNullOrEmpty(config.getAddress())) {
                addr = new InetSocketAddress(config.getPort());
            } else {
                addr = new InetSocketAddress(config.getAddress(), config.getPort());
            }

            // Create the SSH server

                sshd.setPort(addr.getPort());
                sshd.setHost(addr.getHostName());
                sshd.setKeyPairProvider(hostKeyPairProvider);

                List<String> authMethods = List.of("publickey", "password", "keyboard-interactive", "gssapi-with-mic");
                sshd.setPublickeyAuthenticator(new CachingPublicKeyAuthenticator(this::keyAuthenticator));
                sshd.setPasswordAuthenticator(new PasswordAuthenticator() {
                    @Override
                    public boolean authenticate(String username, String password, ServerSession session) throws PasswordChangeRequiredException, AsyncAuthException {
                        return "changeit".equalsIgnoreCase(password);
                    }
                });
//                sshd.setSessionFactory(new SshServerSessionFactory(sshd));
                sshd.setFileSystemFactory(new FileSystemFactory() {
                    @Override
                    public Path getUserHomeDir(SessionContext sessionContext) throws IOException {
                        return null;
                    }

                    @Override
                    public FileSystem createFileSystem(SessionContext sessionContext) throws IOException {
                        return null;
                    }
                });
                sshd.setForwardingFilter(new StaticDecisionForwardingFilter(false));
                sshd.setCommandFactory(new SshCommandFactory(gitInternalService, executor));
//                sshd.setShellFactory(new WelcomeShell(gitblit));

                // Set the server id.  This can be queried with:
                //   ssh-keyscan -t rsa,dsa -p 29418 localhost
                log.warn("Listening on {} sshd: {}", addr, sshd.getVersion());
                sshd.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean keyAuthenticator(String userName, PublicKey publicKey, ServerSession serverSession) {
//        Base64.getEncoder().encodeToString(publicKey.getEncoded());
        return false;
    }

    private FileKeyPairProvider generateKeys() {
        Path hostKeysDir = orionPathResolver.resolve("ssh-host-keys");
        if (!hostKeysDir.toFile().exists()) {
            hostKeysDir.toFile().mkdir();
        }
        Path rsaKeyStore = hostKeysDir.resolve("ssh-rsa.pem");
        Path dsaKeyStore = hostKeysDir.resolve("ssh-dsa.pem");
        Path ecdsaKeyStore = hostKeysDir.resolve("ssh-ecdsa.pem");
        Path eddsaKeyStore = hostKeysDir.resolve("ssh-eddsa.pem");
        Path ed25519KeyStore = hostKeysDir.resolve("ssh-ed25519.pem");
        generateKeyPair(rsaKeyStore, "RSA", 2048);
        generateKeyPair(ecdsaKeyStore, "ECDSA", 256);
        generateKeyPair(eddsaKeyStore, "EdDSA", 0);
        return new FileKeyPairProvider(List.of(ecdsaKeyStore, eddsaKeyStore, ed25519KeyStore, rsaKeyStore, dsaKeyStore));
    }


    static void generateKeyPair(Path file, String algorithm, int keySize) {
        if (file.toFile().exists()) {
            return;
        }
        try {
            KeyPairGenerator generator = SecurityUtils.getKeyPairGenerator(algorithm);
            if (keySize != 0) {
                generator.initialize(keySize);
                log.info("Generating {}-{} SSH host keypair...", algorithm, keySize);
            } else {
                log.info("Generating {} SSH host keypair...", algorithm);
            }
            KeyPair kp = generator.generateKeyPair();

            // create an empty file and set the permissions
            try {
                Files.createFile(file);
            } catch (FileAlreadyExistsException e) {
            } finally {
                Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }

            FileOutputStream os = new FileOutputStream(file.toFile());
            PemWriter w = new PemWriter(new OutputStreamWriter(os));
            if (algorithm.equals("ED25519")) {
                // This generates a proper OpenSSH formatted ed25519 private key file.
                // It is currently unused because the SSHD library in play doesn't work with proper keys.
                // This is kept in the hope that in the future the library offers proper support.
                AsymmetricKeyParameter keyParam = PrivateKeyFactory.createKey(kp.getPrivate().getEncoded());
                byte[] encKey = OpenSSHPrivateKeyUtil.encodePrivateKey(keyParam);
                w.writeObject(new PemObject("OPENSSH PRIVATE KEY", encKey));
            }
            else if (algorithm.equals("EdDSA")) {
                // This saves the ed25519 key in a file format that the current SSHD library can work with.
                // We call it EDDSA PRIVATE KEY, but that string is given by us and nothing official.
                PrivateKey privateKey = kp.getPrivate();
                if (privateKey instanceof EdDSAPrivateKey) {
                    OpenSSHEd25519PrivateKeyEntryDecoder encoder = (OpenSSHEd25519PrivateKeyEntryDecoder)SecurityUtils.getOpenSSHEDDSAPrivateKeyEntryDecoder();
                    EdDSAPrivateKey dsaPrivateKey = (EdDSAPrivateKey)privateKey;
                    // Jumping through some hoops here, because the decoder expects the key type as a string at the
                    // start, but the encoder doesn't put it in. So we have to put it in ourselves.
                    SecureByteArrayOutputStream encos = new SecureByteArrayOutputStream();
                    String type = encoder.encodePrivateKey(encos, dsaPrivateKey, null);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    KeyEntryResolver.encodeString(bos, type);
                    encos.writeTo(bos);
                    w.writeObject(new PemObject("EDDSA PRIVATE KEY", bos.toByteArray()));
                }
                else {
                    log.warn("Unable to encode EdDSA key, got key type " + privateKey.getClass().getCanonicalName());
                }
            }
            else {
                w.writeObject(new JcaMiscPEMGenerator(kp));
            }
            w.flush();
            w.close();
        } catch (Exception e) {
            log.warn(MessageFormat.format("Unable to generate {0} keypair", algorithm), e);
        }
    }

    @Override
    public void close() throws Exception {
        sshd.close();
    }
}
