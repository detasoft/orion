package pro.deta.orion.git.parser.v2.command;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.fetch.NegotiationMessage;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.GitTransport.HTTP;
import static pro.deta.orion.git.parser.v2.GitTransport.SSH;

class FetchCommandTest {
    @Test
    void preparesParsedRequestWithoutProcessingItsNegotiationMessages() throws Exception {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.capabilities().add(GitCapability.WAIT_FOR_DONE.entry());
        request.wants().add(new ObjectId("1".repeat(40)));
        request.initialMessages().add(new NegotiationMessage.Have(new ObjectId("2".repeat(40))));
        request.initialMessages().add(NegotiationMessage.Control.DONE);
        var command = new FetchCommand(new GitStorageApi(), Set.of(GitCapability.WAIT_FOR_DONE));

        var iterator = command.prepareNegotiation(request, HTTP);

        assertThat(iterator.getContext().request()).isSameAs(request);
        assertThat(iterator.getContext().commonObjects()).isEmpty();
        assertThat(iterator.getContext().doneReceived()).isFalse();
        assertThat(iterator.getContext().ready()).isFalse();
        assertThat(iterator.getResponsesToSend()).isEmpty();
    }

    @Test
    void rejectsUnadvertisedCapabilitiesDuringPreparation() {
        var request = new FetchRequest();
        request.capabilities().add(GitCapability.THIN_PACK.entry());
        var command = new FetchCommand(new GitStorageApi(), Set.of());
        assertThatThrownBy(() -> command.prepareNegotiation(request, SSH))
                .isInstanceOf(IOException.class).hasMessageContaining("thin-pack");
    }

    @Test
    void separateRequestsGetIndependentNegotiationState() throws Exception {
        var command = new FetchCommand(new GitStorageApi(), Set.of());
        var first = command.prepareNegotiation(new FetchRequest(), SSH);
        first.next(NegotiationMessage.Control.DONE);
        var second = command.prepareNegotiation(new FetchRequest(), SSH);

        assertThat(first.getContext().doneReceived()).isTrue();
        assertThat(second.getContext()).isNotSameAs(first.getContext());
        assertThat(second.getContext().doneReceived()).isFalse();
        assertThat(second.getResponsesToSend()).isEmpty();
    }
}
