package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningMaterialSetTest {
    @Test
    void selectsFullIdentitiesWithoutOrderingDifferentAliases() {
        KeyMaterialId active = new KeyMaterialId("signing", 2);
        KeyMaterialId retained = new KeyMaterialId("signing", 1);
        KeyMaterialId unrelated = new KeyMaterialId("other", 20);
        SigningMaterialSet selected = new SigningMaterialSet(active,
                List.of(retained, unrelated, retained, active));
        assertThat(selected.verification()).containsExactly(retained, unrelated);
        assertThat(selected.verificationIncludingActive()).containsExactly(active, retained, unrelated);
    }

    @Test
    void rejectsMissingReferences() {
        assertThatThrownBy(() -> new SigningMaterialSet(null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SigningMaterialSet(new KeyMaterialId("signing", 1),
                java.util.Arrays.asList((KeyMaterialId) null)))
                .isInstanceOf(NullPointerException.class);
    }
}
