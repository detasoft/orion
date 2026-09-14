package pro.deta.orion.git.parser.v2.id;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class GitIdTest {
    private static final String HEX = "0123456789abcdef0123456789abcdef01234567";
    private static final List<Function<String, GitId>> HEX_FACTORIES =
            List.of(PackId::new, CommitId::new, ObjectId::new);
    private static final List<Function<byte[], GitId>> BYTE_FACTORIES =
            List.of(PackId::new, CommitId::new, ObjectId::new);

    @Test
    void formatsEveryIdTypeAsCanonicalHex() {
        for (Function<String, GitId> factory : HEX_FACTORIES) {
            GitId id = factory.apply(HEX.toUpperCase(java.util.Locale.ROOT));
            assertEquals(HEX, id.toHex());
            assertEquals(HEX, id.toString());
            assertArrayEquals(HexFormat.of().parseHex(HEX), id.toBytes());
        }
    }

    @Test
    void retainsAllTwentyBytesIncludingLeadingZerosAndHighBits() {
        byte[] bytes = new byte[20];
        bytes[8] = (byte) 0x80;
        bytes[19] = (byte) 0xff;
        for (int index = 0; index < BYTE_FACTORIES.size(); index++) {
            GitId id = BYTE_FACTORIES.get(index).apply(bytes);
            assertEquals("00000000000000008000000000000000000000ff", id.toHex());
            assertEquals(id, HEX_FACTORIES.get(index).apply(id.toHex()));
            assertArrayEquals(bytes, id.toBytes());
        }
    }

    @Test
    void ownsItsBytesAndDoesNotExposeMutableStorage() {
        for (Function<byte[], GitId> factory : BYTE_FACTORIES) {
            byte[] bytes = HexFormat.of().parseHex(HEX);
            GitId id = factory.apply(bytes);
            int hash = id.hashCode();
            bytes[0] ^= 0x7f;
            byte[] returned = id.toBytes();
            returned[19] ^= 0x7f;
            assertEquals(HEX, id.toHex());
            assertEquals(hash, id.hashCode());
            assertArrayEquals(HexFormat.of().parseHex(HEX), id.toBytes());
        }
    }

    @Test
    void equalityAndHashingUseTypeAndContent() {
        for (Function<String, GitId> factory : HEX_FACTORIES) {
            GitId first = factory.apply(HEX);
            GitId second = factory.apply(HEX.toUpperCase(java.util.Locale.ROOT));
            assertEquals(first, second);
            assertEquals(first.hashCode(), second.hashCode());
            assertEquals("found", java.util.Map.of(first, "found").get(second));
            assertNotEquals(first, factory.apply("f" + HEX.substring(1)));
            assertNotEquals(first, null);
            assertNotEquals(first, HEX);
        }
        List<GitId> ids = List.of(new PackId(HEX), new CommitId(HEX), new ObjectId(HEX));
        for (int first = 0; first < ids.size(); first++) {
            for (int second = 0; second < ids.size(); second++) {
                assertEquals(first == second, ids.get(first).equals(ids.get(second)));
            }
        }
    }

    @Test
    void rejectsInvalidBinaryLengthsAndNull() {
        for (Function<byte[], GitId> factory : BYTE_FACTORIES) {
            for (int length : new int[] {0, 8, 19, 21, 32}) {
                assertThrows(IllegalArgumentException.class, () -> factory.apply(new byte[length]));
            }
            assertThrows(NullPointerException.class, () -> factory.apply(null));
        }
    }

    @Test
    void rejectsMalformedHexAndNull() {
        for (Function<String, GitId> factory : HEX_FACTORIES) {
            for (String invalid : List.of("", HEX.substring(1), HEX + "0", "g" + HEX.substring(1),
                    " " + HEX.substring(1), "0".repeat(64))) {
                assertThrows(IllegalArgumentException.class, () -> factory.apply(invalid));
            }
            assertThrows(NullPointerException.class, () -> factory.apply(null));
        }
    }
}
