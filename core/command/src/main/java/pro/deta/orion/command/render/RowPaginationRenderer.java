package pro.deta.orion.command.render;

import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.RowPage;

public final class RowPaginationRenderer {
    private RowPaginationRenderer() {}

    public static void appendWhenRelevant(StringBuilder output, CommandResult.Rows rows) {
        rows.page().filter(RowPage::shouldRender).ifPresent(page -> output
                .append("# page=")
                .append(page.number())
                .append(" page-size=")
                .append(page.size())
                .append(" matched=")
                .append(page.matched())
                .append(" next-page=")
                .append(page.next().isPresent() ? page.next().getAsInt() : "null")
                .append('\n'));
    }
}
