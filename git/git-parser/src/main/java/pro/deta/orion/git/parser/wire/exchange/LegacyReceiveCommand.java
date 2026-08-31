package pro.deta.orion.git.parser.wire.exchange;

import pro.deta.orion.git.nativestorage.GitObjectId;

import java.util.Objects;

public record LegacyReceiveCommand(
        GitObjectId oldObjectId,
        GitObjectId newObjectId,
        String refName,
        Type type) {
    private static final String NULL_ID = "0".repeat(40);

    public LegacyReceiveCommand(
            GitObjectId oldObjectId,
            GitObjectId newObjectId,
            String refName) {
        this(
                oldObjectId,
                newObjectId,
                refName,
                typeOf(oldObjectId, newObjectId));
    }

    public LegacyReceiveCommand {
        Objects.requireNonNull(oldObjectId, "oldObjectId");
        Objects.requireNonNull(newObjectId, "newObjectId");
        validateObjectId(oldObjectId);
        validateObjectId(newObjectId);
        String checkedRefName = Objects.requireNonNull(
                refName,
                "refName");
        Objects.requireNonNull(type, "type");
        if (checkedRefName.isBlank()) {
            throw new IllegalArgumentException(
                    "Receive command ref name must not be blank");
        }
        for (int index = 0; index < checkedRefName.length(); index++) {
            char character = checkedRefName.charAt(index);
            if (character <= 32 || character == 127) {
                throw new IllegalArgumentException(
                        "Receive command ref name must not contain control characters or spaces");
            }
        }
        if (type != typeOf(oldObjectId, newObjectId)) {
            throw new IllegalArgumentException(
                    "Receive command type does not match its object IDs");
        }
    }

    private static void validateObjectId(GitObjectId objectId) {
        String value = objectId.value();
        if (value.length() != 40) {
            throw new IllegalArgumentException(
                    "Receive command object ID must contain 40 hexadecimal digits");
        }
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            boolean hexadecimal = digit >= '0' && digit <= '9'
                    || digit >= 'a' && digit <= 'f'
                    || digit >= 'A' && digit <= 'F';
            if (!hexadecimal) {
                throw new IllegalArgumentException(
                        "Receive command object ID must contain 40 hexadecimal digits");
            }
        }
    }

    private static Type typeOf(
            GitObjectId oldObjectId,
            GitObjectId newObjectId) {
        boolean oldIsNull = NULL_ID.equals(
                Objects.requireNonNull(oldObjectId, "oldObjectId").value());
        boolean newIsNull = NULL_ID.equals(
                Objects.requireNonNull(newObjectId, "newObjectId").value());
        if (oldIsNull && newIsNull) {
            throw new IllegalArgumentException(
                    "Receive command cannot have two zero object IDs");
        }
        if (oldIsNull) {
            return Type.CREATE;
        }
        if (newIsNull) {
            return Type.DELETE;
        }
        return Type.UPDATE;
    }

    public enum Type {
        CREATE,
        UPDATE,
        DELETE
    }
}
