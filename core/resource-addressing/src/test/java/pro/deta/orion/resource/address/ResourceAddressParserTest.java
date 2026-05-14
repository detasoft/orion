package pro.deta.orion.resource.address;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceAddressParserTest {
    @Test
    void parsesWrappedGitAddressWithNestedEnvironmentAndResourcePath() {
        ResourceExpression expression = ResourceExpression.parse("git(env:PATH_TO_GIT_ROOT)/acl/orion.xml?ref=main");

        assertThat(expression.scheme()).isEqualTo(ResourceScheme.GIT);
        assertThat(expression.path()).isEqualTo("acl/orion.xml");
        assertThat(expression.parameters()).containsEntry("ref", "main");
        assertThat(expression.nested().scheme()).isEqualTo(ResourceScheme.ENV);
        assertThat(expression.nested().body()).isEqualTo("PATH_TO_GIT_ROOT");
    }

    @Test
    void parsesLegacyNestedColonAddress() {
        ResourceExpression expression = ResourceExpression.parse("file:env:ORION_CONFIG");

        assertThat(expression.scheme()).isEqualTo(ResourceScheme.FILE);
        assertThat(expression.body()).isNull();
        assertThat(expression.nested().scheme()).isEqualTo(ResourceScheme.ENV);
        assertThat(expression.nested().body()).isEqualTo("ORION_CONFIG");
    }

    @Test
    void acceptsCustomSchemesWithoutChangingParserCode() {
        ResourceExpression expression = ResourceExpression.parse("vault:secret/orion/signing-key");

        assertThat(expression.scheme()).isEqualTo(ResourceScheme.of("vault"));
        assertThat(expression.directValue()).isEqualTo("secret/orion/signing-key");
    }
}
