package pro.deta.orion.agent.protocol;

import java.util.HexFormat;

final class Hex {
    private Hex() {
    }

    static byte[] parse(String value) {
        return HexFormat.of().parseHex(value);
    }
}
