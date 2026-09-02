package pro.deta.orion.git.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class GitRemoteAdvertisementTest {
    private static final String FIRST_ID = "1".repeat(40);
    private static final String SECOND_ID = "2".repeat(40);

    @Test
    void findsTheFirstAdvertisedRefWithTheRequestedName() {
        GitRemoteAdvertisement.Ref first = ref(FIRST_ID, "refs/heads/main");
        GitRemoteAdvertisement.Ref duplicate = ref(SECOND_ID, "refs/heads/main");
        GitRemoteAdvertisement advertisement = new GitRemoteAdvertisement(
                Set.of(),
                List.of(first, duplicate));

        assertThat(advertisement.findRef("refs/heads/main")).containsSame(first);
        assertThat(advertisement.findRef("refs/heads/missing")).isEmpty();
    }

    @Test
    void rejectsANullRefName() {
        GitRemoteAdvertisement advertisement = new GitRemoteAdvertisement(Set.of(), List.of());

        assertThatNullPointerException()
                .isThrownBy(() -> advertisement.findRef(null))
                .withMessage("name");
    }

    private static GitRemoteAdvertisement.Ref ref(String objectId, String name) {
        return new GitRemoteAdvertisement.Ref(objectId, name, Optional.empty());
    }
}
