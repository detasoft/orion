package pro.deta.orion.command;

import pro.deta.orion.command.resource.ScopedResourceResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class CommandNode {
    private final Map<String, CommandNode> children;
    private final Map<String, CommandDefinition> actions;
    private final DynamicChild dynamicChild;

    private CommandNode(
            Map<String, CommandNode> children,
            Map<String, CommandDefinition> actions,
            DynamicChild dynamicChild) {
        this.children = Collections.unmodifiableMap(new LinkedHashMap<>(children));
        this.actions = Collections.unmodifiableMap(new LinkedHashMap<>(actions));
        this.dynamicChild = dynamicChild;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, CommandNode> children() {
        return children;
    }

    public Map<String, CommandDefinition> actions() {
        return actions;
    }

    public DynamicChild dynamicChild() {
        return dynamicChild;
    }

    public record DynamicChild(ScopedResourceResolver<?> resolver, CommandNode node) {
        public DynamicChild {
            Objects.requireNonNull(resolver, "resolver");
            Objects.requireNonNull(node, "node");
        }
    }

    public static final class Builder {
        private final Map<String, CommandNode> children = new LinkedHashMap<>();
        private final Map<String, CommandDefinition> actions = new LinkedHashMap<>();
        private DynamicChild dynamicChild;

        public Builder child(String name, CommandNode node) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(node, "node");
            if (name.isEmpty() || children.putIfAbsent(name, node) != null) {
                throw new IllegalArgumentException("child name must be non-empty and unique");
            }
            return this;
        }

        public Builder action(CommandDefinition definition) {
            Objects.requireNonNull(definition, "definition");
            for (String action : actions.keySet()) {
                if (action.equalsIgnoreCase(definition.action())) {
                    throw new IllegalArgumentException("action must be unique: " + definition.action());
                }
            }
            if (actions.putIfAbsent(definition.action(), definition) != null) {
                throw new IllegalArgumentException("action must be unique: " + definition.action());
            }
            return this;
        }

        public Builder dynamicChild(ScopedResourceResolver<?> resolver, CommandNode node) {
            if (dynamicChild != null) {
                throw new IllegalStateException("dynamic child is already defined");
            }
            dynamicChild = new DynamicChild(resolver, node);
            return this;
        }

        public CommandNode build() {
            return new CommandNode(children, actions, dynamicChild);
        }
    }
}
