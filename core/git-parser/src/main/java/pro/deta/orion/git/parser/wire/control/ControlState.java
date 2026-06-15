package pro.deta.orion.git.parser.wire.control;

import pro.deta.orion.git.parser.wire.CachingByteBuf;

public sealed interface ControlState permits ControlState.ControlEmpty, ControlState.ControlSuccess, ControlState.MoreDataNeeded {
    static final int PKT_LINE_HEADER_SIZE = 4;

    record ControlSuccess(ControlType type, int length) implements ControlState {
        public int payloadLength() {
            return length - PKT_LINE_HEADER_SIZE;
        }
    }

    record MoreDataNeeded(CachingByteBuf fragment) implements ControlState {
    }

    final class ControlEmpty implements ControlState {
        public static final ControlEmpty INSTANCE = new ControlEmpty();
    }

    enum ControlType {
        DATA,
        FLUSH,
        DELIMITER,
        RESPONSE_END
    }
}
