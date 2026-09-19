package pro.deta.orion.git.parser.v2.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitObjectTypeTest {
    @Test
    void decodesGitWireCodesIndependentlyOfEnumOrdinals() throws Exception {
        assertThat(GitObjectType.valueOf(1)).isEqualTo(GitObjectType.COMMIT);
        assertThat(GitObjectType.valueOf(2)).isEqualTo(GitObjectType.TREE);
        assertThat(GitObjectType.valueOf(3)).isEqualTo(GitObjectType.BLOB);
        assertThat(GitObjectType.valueOf(4)).isEqualTo(GitObjectType.TAG);
        assertThat(GitObjectType.valueOf(6)).isEqualTo(GitObjectType.OFS_DELTA);
        assertThat(GitObjectType.valueOf(7)).isEqualTo(GitObjectType.REF_DELTA);
    }

    @Test
    void rejectsReservedAndOutOfRangeCodesAsInvalidInput() {
        for (int code : new int[]{-1, 0, 5, 8, 255}) {
            assertThatThrownBy(() -> GitObjectType.valueOf(code)).isInstanceOf(IOException.class);
        }
    }
}
