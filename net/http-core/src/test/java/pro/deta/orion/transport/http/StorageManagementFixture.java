package pro.deta.orion.transport.http;

import pro.deta.orion.config.ConfigurationFile;
import pro.deta.orion.config.OrionConfigurationEditor;

import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.config.*;
import pro.deta.orion.auth.StorageManagement;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.keymaterial.*;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.bootstrap.config.*;
import pro.deta.orion.schema.orion.*;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.transport.git.ConfiguredStorageManagement;
import pro.deta.orion.util.Result;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

final class StorageManagementFixture {
    static StorageManagement create(NativeGitRepositoryProvider repositories, List<OrionDocument.Organization> orgs) {
        return open(repositories, orgs, ConfigurationCipherCapability.unavailable()).management();
    }

    static State open(NativeGitRepositoryProvider repositories, List<OrionDocument.Organization> orgs,
            ConfigurationCipherCapability cipher) {
        User root = new User("root", "", "", "", List.of(), List.of(),
                ACLUtil.generateDefaultAccessControl("unused").grants());
        User admin = new User("admin", "", "", "", List.of(), List.of(), root.grants());
        OrionDocument document = new OrionDocument(new OrionDocument.SystemConfiguration(
                new AccessControl(List.of(root, admin), List.of(), List.of())), orgs);
        OrionDesiredState desired = new OrionDesiredState();
        desired.publish(document, Optional.of("v1"));
        OrionConfigurationStorage storage = new OrionConfigurationStorage() {
            private ConfigurationFile snapshot = snapshot(document, "v1");
            private int revision = 1;
            @Override public Result<ConfigurationFile> load() { return Result.of(snapshot); }
            @Override public void save(ConfigurationFile updated, String message, UserEmail author) {
                if (!snapshot.revision().equals(updated.revision())) {
                    throw new OrionConfigurationConcurrentUpdateException("Changed", null);
                }
                snapshot = new ConfigurationFile(updated.content(), Optional.of("v" + ++revision));
            }
        };
        OrionConfigurationEditor editor = new OrionConfigurationEditor(storage,
                new BootstrapConfiguration(),
                cipher,
                ConfigurationMaterialCapability.unavailable(),
                desired);
        OrionAccessControlServiceImpl acl = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(),
                OrionRuntimeOptions.defaults(),
                ServerIdentityCapability.unavailable(),
                desired,
                editor,
                Optional.empty());
        return new State(new ConfiguredStorageManagement(acl, editor, desired, cipher, repositories), desired);
    }

    record State(StorageManagement management, OrionDesiredState desired) {
    }

    static jakarta.servlet.http.HttpServletRequest request(String json,
            pro.deta.orion.auth.SecurityContext security, String organization) {
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return (jakarta.servlet.http.HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
                StorageManagementFixture.class.getClassLoader(),
                new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAttribute")) return security;
                    if (method.getName().equals("getParameter")) return organization;
                    if (method.getName().equals("getInputStream")) return new jakarta.servlet.ServletInputStream() {
                        private final java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(bytes);
                        @Override public int read() { return input.read(); }
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(jakarta.servlet.ReadListener listener) {}
                    };
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ConfigurationFile snapshot(OrionDocument document, String revision) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            OrionXml.write(document, output);
            return new ConfigurationFile(output.toByteArray(), Optional.of(revision));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
