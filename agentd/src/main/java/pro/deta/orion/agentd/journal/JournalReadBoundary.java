package pro.deta.orion.agentd.journal;

public enum JournalReadBoundary {
    COMPLETE,
    PAGE_LIMIT,
    INCOMPLETE_TAIL,
    GAP,
    ISSUE
}
