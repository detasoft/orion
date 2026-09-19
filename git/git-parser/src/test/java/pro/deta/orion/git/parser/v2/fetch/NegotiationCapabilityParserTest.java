package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import java.nio.charset.StandardCharsets;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pro.deta.orion.git.parser.v2.capability.GitCapability.*;
import static pro.deta.orion.git.parser.v2.data.GitProtocolVersion.*;

class NegotiationCapabilityParserTest {
    private static final String ID = "ab".repeat(20);

    @Test
    void parsesSharedArgumentsInEveryProtocolVersion() throws Exception {
        for (var version : GitProtocolVersion.values()) {
            var parser = new NegotiationCapabilityParser(version);
            for (String line : new String[]{"want " + ID, "have " + ID, "shallow " + ID,
                    "deepen 10", "deepen-since 123", "deepen-not refs/heads/main", "filter blob:none"}) {
                int separator = line.indexOf(' ');
                assertEquals(new GitCapabilityValue(line.substring(0, separator),
                        Optional.of(line.substring(separator + 1))), read(parser, line));
            }
            assertEquals(new GitCapabilityValue("done", Optional.empty()), read(parser, "done"));
        }
    }

    @Test
    void distinguishesV2ArgumentsFromLegacyCapabilitySuffixes() throws Exception {
        var v2 = new NegotiationCapabilityParser(V2);
        for (String flag : new String[]{"thin-pack", "ofs-delta", "include-tag", "no-progress",
                "wait-for-done", "sideband-all", "deepen-relative"}) {
            assertEquals(new GitCapabilityValue(flag, Optional.empty()), read(v2, flag));
            assertThrows(IOException.class, () -> read(v2, flag + " extra"));
            for (var version : new GitProtocolVersion[]{V0, V1}) {
                var legacy = new NegotiationCapabilityParser(version);
                assertThrows(IOException.class, () -> read(legacy, flag));
                var arguments = legacy.parse(packet("want " + ID + " " + flag));
                assertEquals(Optional.of(ID), arguments.getFirst().value());
                assertEquals(2, arguments.size());
                assertTrue(arguments.has(pro.deta.orion.git.parser.v2.capability.GitCapability
                        .findByWireName(flag).orElseThrow()));
            }
        }
        for (String line : new String[]{"want-ref refs/heads/main", "packfile-uris https,http"}) {
            assertEquals(Optional.of(line.substring(line.indexOf(' ') + 1)), read(v2, line).value());
            for (var version : new GitProtocolVersion[]{V0, V1}) {
                assertThrows(IOException.class, () -> read(new NegotiationCapabilityParser(version), line));
            }
        }
    }

    @Test
    void preservesLegacyWantSuffixAndUnicodeRefsWithoutTrimming() throws Exception {
        String value = ID + " multi_ack agent=client/1 custom=a=b";
        for (var version : new GitProtocolVersion[]{V0, V1}) {
            var legacy = new NegotiationCapabilityParser(version);
            var arguments = legacy.parse(packet("want " + value));
            assertEquals(Optional.of(ID), arguments.getFirst().value());
            assertTrue(arguments.has(MULTI_ACK));
            assertEquals(Optional.of("client/1"), arguments.value(AGENT));
            assertEquals(Optional.of("a=b"), arguments.getLast().value());
        }
        var parser = new NegotiationCapabilityParser(V2);
        assertEquals(Optional.of("refs/heads/ветка"), read(parser, "want-ref refs/heads/ветка").value());
        assertEquals(Optional.of("ветка"), read(parser, "deepen-not ветка").value());
        assertThrows(IOException.class, () -> read(parser, "want " + value));
    }

    @Test
    void rejectsUnknownNamesMissingValuesAndFlagValues() {
        var parser = new NegotiationCapabilityParser(V2);
        for (String line : new String[]{"", "future-option value", " want id", "want=id", "done ",
                "done extra", "agent=client", "agent client", "atomic", "want", "want ", "have",
                "deepen", "shallow", "filter", "deepen-since", "deepen-not", "want-ref", "packfile-uris"}) {
            assertThrows(IOException.class, () -> read(parser, line), line);
        }
    }

    @Test
    void rejectsExtraTokensAndControlCharacters() {
        for (var version : GitProtocolVersion.values()) {
            var parser = new NegotiationCapabilityParser(version);
            for (String line : new String[]{"want  " + ID, "want " + ID + " ", "want " + ID + "  thin-pack",
                    "have " + ID + " extra", "filter blob:none extra", "deepen 1 2", "want\t" + ID,
                    "have " + ID + "\nextra", "want " + ID + "\0", "deepen-not ref\tname", "deepen-not ref\rname",
                    "deepen-not ref\u007fname"}) {
                assertThrows(IOException.class, () -> read(parser, line), line);
            }
        }
    }

    @Test
    void keepsValuesIndependentAcrossMessages() throws Exception {
        var parser = new NegotiationCapabilityParser(V2);
        var first = parser.parse(packet("have " + ID));
        var second = parser.parse(packet("have " + "cd".repeat(20)));
        assertEquals(List.of(new GitCapabilityValue("have", Optional.of(ID))), first);
        assertEquals(List.of(new GitCapabilityValue("have", Optional.of("cd".repeat(20)))), second);
        first.clear();
        assertEquals(1, second.size());
    }

    @Test
    void capabilityWireTokensStillRejectSpacesAndEmptyValues() throws Exception {
        for (String token : new String[]{"agent=", "agent=two words", "agent=ветка"}) {
            assertThrows(IOException.class, () -> GitCapabilityValue.parse(token, GitHashAlgorithm.SHA1));
        }
        assertEquals(Optional.of("a=b"), GitCapabilityValue.parse("custom=a=b", GitHashAlgorithm.SHA1).value());
        assertThrows(IOException.class,
                () -> GitCapabilityValue.parse("object-format=sha256", GitHashAlgorithm.SHA1));
        var argument = read(new NegotiationCapabilityParser(V0), "want " + ID + " thin-pack");
        assertEquals(Optional.of(ID), argument.value());
    }

    @Test
    void finishesAtFlushAndWaitsForFlushAfterV2Done() throws Exception {
        var parser = new NegotiationCapabilityParser(V2);
        assertEquals(List.of(new GitCapabilityValue("want", Optional.of(ID))),
                parser.parse(packet("want " + ID + "\n")));
        assertEquals(List.of(new GitCapabilityValue("done", Optional.empty())), parser.parse(packet("done\n")));
        assertTrue(parser.parse(GitPktLine.Control.FLUSH).isEmpty());
        assertThrows(IllegalStateException.class, () -> parser.parse(packet("thin-pack")));
        assertThrows(IllegalStateException.class, () -> parser.parse(GitPktLine.Control.FLUSH));
        for (var version : new GitProtocolVersion[]{V0, V1}) {
            var legacy = new NegotiationCapabilityParser(version);
            assertEquals(1, legacy.parse(packet("have " + ID)).size());
            assertEquals(List.of(new GitCapabilityValue("done", Optional.empty())), legacy.parse(packet("done")));
            assertThrows(IllegalStateException.class, () -> legacy.parse(packet("have " + ID)));
            assertThrows(IllegalStateException.class, () -> legacy.parse(GitPktLine.Control.FLUSH));
        }
        assertTrue(new NegotiationCapabilityParser(V0).parse(GitPktLine.Control.FLUSH).isEmpty());
    }

    @Test
    void rejectsUnexpectedControlPacketsAndInvalidText() {
        var parser = new NegotiationCapabilityParser(V2);
        assertThrows(IOException.class, () -> parser.parse(GitPktLine.Control.DELIMITER));
        assertThrows(IOException.class, () -> parser.parse(GitPktLine.Control.RESPONSE_END));
        assertThrows(IOException.class, () -> parser.parse(new GitPktLine.Data(new byte[]{(byte) 0xff})));
        assertThrows(IOException.class, () -> parser.parse(packet("")));
    }

    @Test
    void onlyFirstLegacyWantCanCarryCapabilitiesAndMalformedPacketDoesNotAdvanceState() throws Exception {
        var parser = new NegotiationCapabilityParser(V1);
        assertThrows(IOException.class, () -> parser.parse(packet("want " + ID + " thin-pack agent=")));
        var first = parser.parse(packet("want " + ID + " thin-pack agent=a agent=b"));
        assertEquals(List.of("a", "b"), first.values(AGENT));
        first.clear();
        assertThrows(IOException.class, () -> parser.parse(packet("want " + ID + " thin-pack")));
        assertEquals(List.of(new GitCapabilityValue("want", Optional.of(ID))),
                parser.parse(packet("want " + ID)));
        assertTrue(parser.parse(GitPktLine.Control.FLUSH).isEmpty());
    }

    private static GitCapabilityValue read(NegotiationCapabilityParser parser, String line) throws IOException {
        return parser.parse(packet(line)).getFirst();
    }

    private static GitPktLine.Data packet(String line) {
        return new GitPktLine.Data(line.getBytes(StandardCharsets.UTF_8));
    }
}
