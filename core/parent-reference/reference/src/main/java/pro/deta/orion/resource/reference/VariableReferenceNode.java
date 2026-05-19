package pro.deta.orion.resource.reference;

import java.util.Optional;

record VariableReferenceNode(
        String name,
        Optional<ParameterExpansionOperator> operator,
        Optional<String> operatorOperand) implements ReferenceNode {

    public VariableReferenceNode {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Variable name must not be empty");
        }
        operator = operator == null ? Optional.empty() : operator;
        operatorOperand = operatorOperand == null ? Optional.empty() : operatorOperand;
    }

    public VariableReferenceNode(String name) {
        this(name, Optional.empty(), Optional.empty());
    }

    public boolean isContextReference() {
        return name.contains(".");
    }
}
