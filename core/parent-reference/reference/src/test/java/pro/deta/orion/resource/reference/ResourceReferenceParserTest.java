package pro.deta.orion.resource.reference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceReferenceParserTest {
    @Test
    void parsesSimpleEnvironmentReference() {
        ResourceReference reference = ResourceReference.parse("${ORION_ROOT}");

        assertThat(reference.raw()).isEqualTo("${ORION_ROOT}");
        VariableReferenceNode variable = assertNode(reference.root(), VariableReferenceNode.class);
        assertThat(variable.name()).isEqualTo("ORION_ROOT");
        assertThat(variable.operator()).isEmpty();
    }

    @Test
    void parsesContextReference() {
        ResourceReference reference = ResourceReference.parse("${bootstrap.baseDir}");

        VariableReferenceNode variable = assertNode(reference.root(), VariableReferenceNode.class);
        assertThat(variable.name()).isEqualTo("bootstrap.baseDir");
        assertThat(variable.isContextReference()).isTrue();
    }

    @Test
    void parsesDefaultAndRequiredOperators() {
        assertOperator(
                "${ORION_ROOT:-orion_root}",
                ParameterExpansionOperator.DEFAULT_IF_UNSET_OR_BLANK,
                "orion_root");
        assertOperator("${ORION_ROOT-orion_root}", ParameterExpansionOperator.DEFAULT_IF_UNSET, "orion_root");
        assertOperator("${ORION_ROOT:?required}", ParameterExpansionOperator.ERROR_IF_UNSET_OR_BLANK, "required");
        assertOperator("${ORION_ROOT?required}", ParameterExpansionOperator.ERROR_IF_UNSET, "required");
    }

    @Test
    void parsesCompoundPathWithoutFlatteningSegments() {
        ResourceReference reference = ResourceReference.parse("${ORION_ROOT:-orion_root}/orion.xml");

        InterpolatedReferenceNode interpolation = assertNode(reference.root(), InterpolatedReferenceNode.class);
        assertThat(interpolation.parts()).hasSize(2);
        assertThat(interpolation.parts().get(0)).isInstanceOf(VariableReferenceNode.class);
        assertThat(interpolation.parts().get(1)).isEqualTo(new LiteralReferenceNode("/orion.xml"));
    }

    @Test
    void parsesDocumentReferenceWithNestedSource() {
        ResourceReference reference = ResourceReference.parse("${yaml:${ORION_CONFIG_DATA}/bootstrap/baseDir}");

        DocumentReferenceNode document = assertNode(reference.root(), DocumentReferenceNode.class);
        assertThat(document.format()).isEqualTo(DocumentFormat.YAML);
        assertThat(document.source()).isInstanceOf(VariableReferenceNode.class);
        assertThat(document.path()).isEqualTo(DocumentPath.parse("/bootstrap/baseDir"));
    }

    @Test
    void rejectsInlineDocumentSourceWithoutNestedReference() {
        assertThatThrownBy(() -> ResourceReference.parse("${yaml:bootstrap/baseDir}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document source must be a nested reference");
    }

    private static void assertOperator(
            String raw,
            ParameterExpansionOperator expectedOperator,
            String expectedOperand) {
        VariableReferenceNode variable = assertNode(ResourceReference.parse(raw).root(), VariableReferenceNode.class);
        assertThat(variable.operator()).contains(expectedOperator);
        assertThat(variable.operatorOperand()).contains(expectedOperand);
    }

    private static <T> T assertNode(ReferenceNode node, Class<T> type) {
        assertThat(node).isInstanceOf(type);
        return type.cast(node);
    }
}
