package pro.deta.orion.transport.http;





import pro.deta.orion.config.*;
import pro.deta.orion.auth.StorageManagement;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.keymaterial.*;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.auth.DefaultAccessControl;
import pro.deta.orion.bootstrap.config.*;
import pro.deta.orion.schema.orion.*;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.transport.git.ConfiguredStorageManagement;

import pro.deta.orion.test.integration.TestConfiguration;
import java.util.List;
import java.util.Optional;

final class StorageManagementFixture {
    static StorageManagement create(
            pro.deta.orion.test.util.TestEvents events,
            NativeGitRepositoryProvider repositories,
            List<OrionDocument.Organization> orgs) {
        return open(events, repositories, orgs, ConfigurationCipherCapability.unavailable()).management();
    }

    static State open(
            pro.deta.orion.test.util.TestEvents events,
            NativeGitRepositoryProvider repositories,
            List<OrionDocument.Organization> orgs,
            ConfigurationCipherCapability cipher) {
        User root = new User("root", "", "", "", List.of(), List.of(),
                DefaultAccessControl.create("unused").grants());
        User admin = new User("admin", "", "", "", List.of(), List.of(), root.grants());
        OrionDocument document = new OrionDocument(new OrionDocument.SystemConfiguration(
                new AccessControl(List.of(root, admin), List.of(), List.of())), orgs);
        TestConfiguration configuration = new TestConfiguration(events, new BootstrapConfiguration(), cipher,
                ConfigurationMaterialCapability.unavailable(), ServerIdentityCapability.unavailable());
        configuration.publish(document, Optional.of("v1"));
        return new State(new ConfiguredStorageManagement(configuration.acl(), configuration.editor(), cipher,
                repositories), configuration);
    }

    record State(StorageManagement management, TestConfiguration desired) {
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

}
