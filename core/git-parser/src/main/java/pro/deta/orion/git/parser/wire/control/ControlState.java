package pro.deta.orion.git.parser.wire.control;

import io.netty.buffer.ByteBuf;

public sealed interface ControlState permits ControlState.ControlEmpty, ControlState.ControlSuccess, ControlState.MoreDataNeeded {

    record ControlSuccess(ControlType type, int length) implements ControlState {
    }

    record MoreDataNeeded(ByteBuf fragment) implements ControlState {
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
