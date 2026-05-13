package pro.deta.orion.event.type;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.common.GitRefUpdate;
import pro.deta.orion.git.common.GitRefUpdateResult;
import pro.deta.orion.git.common.GitRefUpdateType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Orion event")
class OrionEventTest {
    @Test
    @DisplayName("records creation time")
    void recordsCreationTime() {
        Instant beforeCreation = Instant.now();

        RequestToAclUpdate event = new RequestToAclUpdate("test");

        assertThat(event.getCreatedAt()).isBetween(beforeCreation, Instant.now());
    }

    @Test
    @DisplayName("prints base event state and payload")
    void printsBaseEventStateAndPayload() {
        RequestToAclUpdate event = new RequestToAclUpdate("acl-refresh");

        assertThat(event.toString())
                .startsWith("RequestToAclUpdate{")
                .contains("createdAt=", "processed=false", "initiator='acl-refresh'");

        event.setProcessed();

        assertThat(event.toString()).contains("processed=true");
    }

    @Test
    @DisplayName("prints shutdown request source")
    void printsShutdownRequestSource() {
        ApplicationShutdownRequestedEvent event = new ApplicationShutdownRequestedEvent("http-admin");

        assertThat(event.toString())
                .startsWith("ApplicationShutdownRequestedEvent{")
                .contains("source='http-admin'");
    }

    @Test
    @DisplayName("prints git receive refs without object identity noise")
    void printsGitReceiveRefsWithoutObjectIdentityNoise() {
        GitReceiveOrionEvent event = new GitReceiveOrionEvent("project", "writer");
        event.addReceiveEventRef(new GitRefUpdate(
                "refs/heads/main",
                GitObjectId.of("0000000000000000000000000000000000000000"),
                GitObjectId.of("a971b22fe44d0a59636d70248c71872250e3687e"),
                GitRefUpdateType.CREATE,
                GitRefUpdateResult.OK));

        assertThat(event.toString())
                .contains(
                        "repositoryName='project'",
                        "userName='writer'",
                        "GitRefUpdate[refName=refs/heads/main",
                        "oldId=0000000000000000000000000000000000000000",
                        "newId=a971b22fe44d0a59636d70248c71872250e3687e",
                        "type=CREATE",
                        "result=OK")
                .doesNotContain("@");
    }

}
