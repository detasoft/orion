package pro.deta.orion.git.parser.v2.storage;

import org.h2.mvstore.WriteBuffer;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.pack.mv.ObjectIdDataType;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectIdDataTypeTest {
    @Test
    void encodesExactlyTwentyBytesAndPreservesLeadingZeroesAndHighBits() {
        var type = ObjectIdDataType.INSTANCE;
        var id = new ObjectId("000102030405060708090a0b0c0d0e0f807fffff");
        var output = new WriteBuffer();
        type.write(output, id);
        var bytes = output.getBuffer().flip();
        assertThat(bytes.remaining()).isEqualTo(20);
        assertThat(type.read(bytes)).isEqualTo(id);
        assertThat(bytes.hasRemaining()).isFalse();
    }

    @Test
    void comparesUnsignedBytesIncludingTheLastByte() {
        var type = ObjectIdDataType.INSTANCE;
        var lower = new ObjectId("7f" + "ff".repeat(19));
        var higher = new ObjectId("80" + "00".repeat(19));
        var lastByte = new ObjectId("80" + "00".repeat(18) + "01");
        assertThat(type.compare(lower, higher)).isNegative();
        assertThat(type.compare(higher, lower)).isPositive();
        assertThat(type.compare(higher, lastByte)).isNegative();
        assertThat(type.compare(lastByte, new ObjectId(lastByte.toBytes()))).isZero();
    }
}
