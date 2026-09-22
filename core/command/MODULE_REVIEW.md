# Module Review: `core/command`

## 5. Equality predicates misinterpret inequality text inside their values

**Problem.** `/repository ls where name='a!=b'` is parsed as inequality on field `name=a`, rather than equality
on field `name` with value `a!=b`. Query validation then rejects the unknown field instead of filtering rows.

**Sources.** [`CommandLineParser.addPredicate`](src/main/java/pro/deta/orion/command/CommandLineParser.java)
searches the complete token for `!=` before considering `=`. Tokenization removes the value's quotes.
[`CommandRowQuery`](src/main/java/pro/deta/orion/command/CommandRowQuery.java) rejects the resulting field.
[`ReadOnlyDomainCommandCatalog`](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/command/ReadOnlyDomainCommandCatalog.java)
exposes the repository `name` column and filtering through SSH commands.

**Documented behavior.** Existing parser tests cover equality and inequality predicates, but not operator-like
text within a quoted value. No requirement restricting text values to exclude `!=` was found.

**Contract.** The predicate separator determines the operator; subsequent characters belong to the value.
Quoted text containing `!=` must remain usable as an equality-filter value, even when no rows match it.

**Minimal repair.** Locate the first `=` and recognize inequality only when its immediately preceding character
is `!`. Preserve the rest of the value. Cover both operators, embedded operator text, and malformed predicates.

**Alternatives and consequences.** Restricting string values would unnecessarily narrow the command contract.
A new query grammar or parser abstraction is unnecessary for this boundary correction.

**Confidence.** High from the tokenizer, predicate parser, and query-validation path; no new runtime test was
executed during the read-only audit.

**Priority signals.** Importance: medium because valid text filters fail. Repair ease: high; the defect is local
to predicate splitting and needs no command syntax or API changes.
