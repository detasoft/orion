package pro.deta.orion.transport.git;

import pro.deta.orion.config.ConfigurationFile;
import pro.deta.orion.config.OrionConfigurationStorage;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.keymaterial.ConfigurationMaterialCapability;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.util.Result;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

public final class TestConfigurationEditor {
    private TestConfigurationEditor() {
    }

    public static OrionConfigurationEditor create() {
        byte[] initial;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            OrionXml.write(OrionDocument.withAccessControl(new AccessControl()), output);
            initial = output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        OrionConfigurationStorage storage = new OrionConfigurationStorage() {
            private ConfigurationFile snapshot = new ConfigurationFile(initial, Optional.empty());

            @Override
            public Result<ConfigurationFile> load() {
                return Result.of(snapshot);
            }

            @Override
            public void save(ConfigurationFile candidate, String message, UserEmail author) {
                snapshot = candidate;
            }

        };
        return new OrionConfigurationEditor(storage,
                new OrionConfiguration(),
                ConfigurationCipherCapability.unavailable(),
                ConfigurationMaterialCapability.unavailable(),
                new pro.deta.orion.config.OrionDesiredState());
    }
}
