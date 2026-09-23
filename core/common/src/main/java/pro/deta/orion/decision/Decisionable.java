package pro.deta.orion.decision;

/** An error that carries a ready decision, including the actions needed to resolve it. */
public interface Decisionable {
    Decision decision();
}
