package pro.deta.orion.git.parser.wire;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.capability.GitCapabilitySet;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitWireValueStackTest {

    @Test
    void passesACompletedValueToTheNextPhase() {
        GitWireValueStack stack = new GitWireValueStack();
        GitCapabilitySet capabilities = new GitCapabilitySet(List.of(GitCapability.bare("thin-pack")));

        stack.push(GitCapabilitySet.class, capabilities);

        assertThat(stack.pop(GitCapabilitySet.class)).isSameAs(capabilities);
        assertThat(stack.isEmpty()).isTrue();
    }

    @Test
    void exposesTheFinalValueWithoutRemovingIt() {
        GitWireValueStack stack = new GitWireValueStack();
        stack.push(String.class, "complete");

        assertThat(stack.peek(String.class)).isEqualTo("complete");
        assertThat(stack.peek(String.class)).isEqualTo("complete");
        assertThat(stack.isEmpty()).isFalse();
    }

    @Test
    void rejectsAValueTypeDifferentFromTheNextPhaseContractWithoutRemovingIt() {
        GitWireValueStack stack = new GitWireValueStack();
        stack.push(String.class, "acknowledgments");

        assertThatThrownBy(() -> stack.pop(GitCapabilitySet.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GitCapabilitySet")
                .hasMessageContaining("String");
        assertThat(stack.peek(String.class)).isEqualTo("acknowledgments");
    }

    @Test
    void rejectsPopFromAnEmptyStack() {
        GitWireValueStack stack = new GitWireValueStack();

        assertThatThrownBy(() -> stack.pop(String.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }
}
