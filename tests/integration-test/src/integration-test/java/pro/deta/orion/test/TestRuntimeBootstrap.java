package pro.deta.orion.test;

import pro.deta.orion.KeyMaterialBootstrap;

import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.git.s3.ConfiguredNativeGitRepositoryFactory;
import pro.deta.orion.git.s3.S3Transport;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.acl.RootPasswordReset;
import pro.deta.orion.util.ConfigurationContext;

import java.util.Map;

final class TestRuntimeBootstrap {
    private TestRuntimeBootstrap() {
    }

    static byte[] configurationXml(pro.deta.orion.config.OrionConfigurationEditor editor) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        pro.deta.orion.schema.orion.OrionXml.write(editor.current().result()
                .valueOrFailure("Cannot read configuration document"), output);
        return output.toByteArray();
    }

    static <T> T withAccess(pro.deta.orion.config.OrionConfigurationEditor editor,
            pro.deta.orion.internal.ChangeMetadata metadata,
            java.util.function.Function<pro.deta.orion.acl.AccessControlAccess, T> operation) {
        pro.deta.orion.config.OrionConfigurationModification modification =
                editor.edit(editor.current().revision(), metadata);
        pro.deta.orion.acl.AccessControlAccess access = new pro.deta.orion.acl.AccessControlAccess(
                modification, new pro.deta.orion.crypto.OrionPasswordHashingService());
        try {
            T result = operation.apply(access);
            awaitConfiguration(editor, modification.revision());
            return result;
        } finally {
            access.discard();
        }
    }

    static void awaitConfiguration(pro.deta.orion.config.OrionConfigurationEditor editor,
            pro.deta.orion.config.OrionRevision revision) {
        if (!pro.deta.orion.util.OrionUtils.waitForCondition(
                () -> editor.current().revision().equals(revision))) {
            throw new IllegalStateException("Configuration revision was not activated");
        }
    }

    static void updateConfiguration(OrionComponent component, pro.deta.orion.config.OrionRevision revision,
            pro.deta.orion.internal.ChangeMetadata metadata,
            java.util.function.UnaryOperator<pro.deta.orion.schema.orion.v2.OrionDocument> update) {
        pro.deta.orion.config.OrionConfigurationModification modification =
                component.configurationEditor().edit(revision, metadata);
        try {
            modification.update(update).apply();
            awaitConfiguration(component.configurationEditor(), modification.revision());
        } finally {
            modification.discard();
        }
    }

    static OrionComponent.Builder componentBuilder(
            BootstrapConfiguration configuration,
            OrionKeyMaterial material,
            KeyPairProvider sshHostKeys) {
        return componentBuilder(configuration, material, sshHostKeys, new RootPasswordReset(false));
    }

    static OrionComponent.Builder componentBuilder(
            BootstrapConfiguration configuration,
            OrionKeyMaterial material,
            KeyPairProvider sshHostKeys,
            RootPasswordReset rootPasswordReset) {
        pro.deta.orion.event.OrionEventManager events = new pro.deta.orion.event.OrionEventManager();
        NativeGitRepositoryBackend backend = NativeGitRepositoryBackend.file(
                new ConfigurationContext(configuration).getFileGitStoragePath(), events);
        S3Transport transport = new S3Transport();
        ConfiguredNativeGitRepositoryFactory configured =
                new ConfiguredNativeGitRepositoryFactory(backend, transport, events);
        NativeGitRepositoryFactory provider =
                NativeGitRepositoryFactory.bootstrap(configured, Map.copyOf(System.getenv()));
        provider.resolveProvisional(
                NativeGitRepositoryFactory.CONFIGURATION_SOURCE,
                configuration.getBootstrap().getConfiguration(),
                true);
        return DaggerOrionComponent.builder()
                .bootstrapConfiguration(configuration)
                .rootPasswordReset(rootPasswordReset)
                .signingMaterial(KeyMaterialBootstrap.signingMaterial(
                        configuration.getBootstrap().getKeyMaterial()))
                .keyMaterial(material)
                .eventManager(events)
                .sshHostKeyProvider(sshHostKeys)
                .nativeGitRepositoryProvider(provider)
                .configuredRepositoryFactory(configured)
                .s3Transport(transport);
    }
}
