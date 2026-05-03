package pro.deta.orion.event.type;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.ReceiveCommand;

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
    @DisplayName("prints git receive refs without object identity noise")
    void printsGitReceiveRefsWithoutObjectIdentityNoise() {
        GitReceiveOrionEvent event = new GitReceiveOrionEvent("project", "writer");
        event.addReceiveEventRef(
                "refs/heads/main",
                ObjectId.zeroId(),
                ObjectId.fromString("a971b22fe44d0a59636d70248c71872250e3687e"),
                ReceiveCommand.Type.CREATE,
                ReceiveCommand.Result.OK);

        assertThat(event.toString())
                .contains(
                        "repositoryName='project'",
                        "userName='writer'",
                        "GitReceiveEventRef{refName='refs/heads/main'",
                        "oldId=0000000000000000000000000000000000000000",
                        "newId=a971b22fe44d0a59636d70248c71872250e3687e",
                        "type=CREATE",
                        "result=OK")
                .doesNotContain("@");
    }
}
