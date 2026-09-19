package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import java.nio.charset.StandardCharsets;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                assertEquals(Optional.of(ID), read(legacy, "want " + ID + " " + flag).value());
                assertTrue(legacy.capabilities().has(pro.deta.orion.git.parser.v2.capability.GitCapability
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
            assertEquals(Optional.of(ID), read(legacy, "want " + value).value());
            assertTrue(legacy.capabilities().has(MULTI_ACK));
            assertEquals(Optional.of("client/1"), legacy.capabilities().value(AGENT));
            assertEquals(Optional.of("a=b"), legacy.capabilities().getLast().value());
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
        var first = read(parser, "have " + ID);
        var second = read(parser, "have " + "cd".repeat(20));
        assertEquals(first.name(), second.name());
        assertEquals(Optional.of(ID), first.value());
        assertEquals(Optional.of("cd".repeat(20)), second.value());
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
        assertTrue(parser.parse(packet("want " + ID + "\n")));
        assertTrue(parser.parse(packet("done\n")));
        assertFalse(parser.parse(GitPktLine.Control.FLUSH));
        assertEquals(2, parser.capabilities().size());
        assertThrows(IllegalStateException.class, () -> parser.parse(packet("thin-pack")));
        assertThrows(IllegalStateException.class, () -> parser.parse(GitPktLine.Control.FLUSH));
        for (var version : new GitProtocolVersion[]{V0, V1}) {
            var legacy = new NegotiationCapabilityParser(version);
            assertTrue(legacy.parse(packet("have " + ID)));
            assertFalse(legacy.parse(packet("done")));
        }
        assertFalse(new NegotiationCapabilityParser(V0).parse(GitPktLine.Control.FLUSH));
    }

    @Test
    void rejectsUnexpectedControlPacketsAndInvalidText() {
        var parser = new NegotiationCapabilityParser(V2);
        assertThrows(IOException.class, () -> parser.parse(GitPktLine.Control.DELIMITER));
        assertThrows(IOException.class, () -> parser.parse(GitPktLine.Control.RESPONSE_END));
        assertThrows(IOException.class, () -> parser.parse(new GitPktLine.Data(new byte[]{(byte) 0xff})));
        assertThrows(IOException.class, () -> parser.parse(packet("")));
        assertTrue(parser.capabilities().isEmpty());
    }

    @Test
    void onlyFirstLegacyWantCanCarryCapabilitiesAndMalformedPacketAddsNothing() throws Exception {
        var parser = new NegotiationCapabilityParser(V1);
        assertThrows(IOException.class, () -> parser.parse(packet("want " + ID + " thin-pack agent=")));
        assertTrue(parser.capabilities().isEmpty());
        assertTrue(parser.parse(packet("want " + ID + " thin-pack agent=a agent=b")));
        assertEquals(java.util.List.of("a", "b"), parser.capabilities().values(AGENT));
        int count = parser.capabilities().size();
        assertThrows(IOException.class, () -> parser.parse(packet("want " + ID + " thin-pack")));
        assertEquals(count, parser.capabilities().size());
        assertTrue(parser.parse(packet("want " + ID)));
        assertFalse(parser.parse(GitPktLine.Control.FLUSH));
    }

    private static GitCapabilityValue read(NegotiationCapabilityParser parser, String line) throws IOException {
        int offset = parser.capabilities().size();
        parser.parse(packet(line));
        return parser.capabilities().get(offset);
    }

    private static GitPktLine.Data packet(String line) {
        return new GitPktLine.Data(line.getBytes(StandardCharsets.UTF_8));
    }
}
