package pro.deta.orion.agentd.terminal;

record TerminalSize(int columns, int rows) {
    TerminalSize {
        if (columns < 1 || columns > 0xffff || rows < 1 || rows > 0xffff) {
            throw new IllegalArgumentException("terminal dimensions must be between 1 and 65535");
        }
    }
}
