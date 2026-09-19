package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetchRequestTest {
    private static final String WANT = "ab".repeat(20);
    private static final String HAVE = "cd".repeat(20);

    @Test
    void legacyPreservesCapabilityOccurrencesAndStopsBeforeNegotiation() throws Exception {
        for (var version : List.of(GitProtocolVersion.V0, GitProtocolVersion.V1)) {
            var reader = reader(packet("want " + WANT + " multi_ack_detailed agent=a agent=b")
                    + packet("shallow " + HAVE) + "0000" + packet("have " + HAVE), version);
            var request = FetchRequest.parseRequest(reader, version);
            assertEquals(Set.of(new ObjectId(WANT)), request.wants());
            assertEquals(Set.of(new ObjectId(HAVE)), request.shallowCommits());
            assertEquals(FetchRequest.Mode.MULTI_ACK_DETAILED, request.mode());
            assertEquals(List.of("a", "b"), request.capabilities().values(GitCapability.AGENT));
            assertTrue(request.initialMessages().isEmpty());
            assertEquals("have " + HAVE, ((GitPktLine.Data) reader.readGitPktLine()).text());
        }
    }

    @Test
    void valuedLegacyFlagDoesNotEnableAckMode() throws Exception {
        var request = parse(GitProtocolVersion.V1, packet("want " + WANT + " multi_ack=custom") + "0000");
        assertEquals(FetchRequest.Mode.SINGLE_ACK, request.mode());
        assertEquals("custom", request.capabilities().value(GitCapability.MULTI_ACK).orElseThrow());
    }

    @Test
    void v2CollectsArgumentsThroughFlushAfterDone() throws Exception {
        var reader = reader(packet("want " + WANT) + packet("have " + HAVE) + packet("done")
                + packet("wait-for-done") + packet("thin-pack") + packet("deepen 3")
                + packet("deepen-relative") + "0000" + packet("next"), GitProtocolVersion.V2);
        var request = FetchRequest.parseRequest(reader, GitProtocolVersion.V2);
        assertEquals(FetchRequest.Mode.PROTOCOL_V2, request.mode());
        assertTrue(request.waitForDone());
        assertTrue(request.capabilities().has(GitCapability.THIN_PACK));
        assertEquals(3, request.depth().orElseThrow());
        assertEquals(List.of(new NegotiationMessage.Have(new ObjectId(HAVE)),
                NegotiationMessage.Control.DONE, NegotiationMessage.Control.END_ROUND), request.initialMessages());
        assertEquals("next", ((GitPktLine.Data) reader.readGitPktLine()).text());
    }

    @Test
    void recognizesEveryV2FetchFlag() throws Exception {
        for (var capability : List.of(GitCapability.THIN_PACK, GitCapability.OFS_DELTA,
                GitCapability.INCLUDE_TAG, GitCapability.NO_PROGRESS, GitCapability.WAIT_FOR_DONE,
                GitCapability.SIDEBAND_ALL, GitCapability.DEEPEN_RELATIVE)) {
            var request = parse(GitProtocolVersion.V2, packet("want " + WANT) + packet("deepen 2")
                    + packet(capability.wireName()) + "0000");
            assertTrue(request.capabilities().has(capability));
        }
    }

    @Test
    void preservesRefWantsAndCompatibleDeepeningOptions() throws Exception {
        var request = parse(GitProtocolVersion.V2, packet("want-ref refs/heads/main")
                + packet("deepen-since 123") + packet("deepen-not refs/heads/old")
                + packet("filter blob:none") + packet("packfile-uris https,http") + "0000");
        assertEquals(Set.of("refs/heads/main"), request.wantRefs());
        assertEquals(123, request.deepenSince().orElseThrow());
        assertEquals(Set.of("refs/heads/old"), request.deepenNot());
        assertEquals("blob:none", request.filter().orElseThrow());
        assertEquals(Set.of("https", "http"), request.packfileUriProtocols());
        assertFalse(request.waitForDone());
    }

    @Test
    void rejectsInvalidAndConflictingRequestArguments() {
        for (String arguments : List.of(packet("deepen 2147483648"), packet("deepen 0"),
                packet("deepen 2") + packet("deepen-since 123"), packet("deepen-relative"),
                packet("done") + packet("done"), packet("want-ref refs/heads/../bad"),
                packet("want invalid"), "0001", "0002")) {
            assertThrows(IOException.class,
                    () -> parse(GitProtocolVersion.V2, packet("want " + WANT) + arguments + "0000"));
        }
        for (String arguments : List.of(packet("have " + HAVE), packet("done"),
                packet("want " + HAVE + " thin-pack"), packet("deepen 2") + packet("want " + HAVE))) {
            assertThrows(IOException.class,
                    () -> parse(GitProtocolVersion.V0, packet("want " + WANT) + arguments + "0000"));
        }
    }

    @Test
    void handlesEmptyAndTruncatedRequests() throws Exception {
        assertTrue(parse(GitProtocolVersion.V0, "0000").wants().isEmpty());
        assertThrows(IOException.class, () -> parse(GitProtocolVersion.V2, "0000"));
        for (var version : GitProtocolVersion.values()) {
            assertThrows(IOException.class, () -> parse(version, packet("want " + WANT)));
        }
    }

    private static FetchRequest parse(GitProtocolVersion version, String wire) throws IOException {
        return FetchRequest.parseRequest(reader(wire, version), version);
    }

    private static GitProtocolContext.Reader reader(String wire, GitProtocolVersion version) {
        var input = new BufferedByteInputV2(
                new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
        var output = new OutputStreamBufferedByteOutput(OutputStream.nullOutputStream());
        return new GitProtocolContext(input, output, version, GitTransport.SSH).reader();
    }

    private static String packet(String text) {
        return "%04x".formatted(text.getBytes(StandardCharsets.UTF_8).length + 4) + text;
    }
}
