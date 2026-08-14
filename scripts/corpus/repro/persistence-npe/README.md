# F23 — six unguarded call sites behind one NullPointerException

    java -cp <runner>/target/classes:$(cat cp.txt) perf.ParseMain *.pure

Both files report:

    An exception of type 'NullPointerException' occurred, please notify developer

No source information, no field name, no construct named.

## Cause

`serviceOutputValue: (identifier | dslNavigationPath)` is one grammar rule with two
meanings. The walker chooses which accessor to call based on the *dataset kind* — TDS takes
`.identifier()`, graph-fetch takes `.dslNavigationPath()` — and never null-checks the result.
Write the other arm and the accessor returns null, which is then dereferenced.

## Why the fixture count matters

It is **six call sites, not one**, so a fix that patches `keys` is incomplete:

| # | where | input that triggers it |
|---|---|---|
| 1 | `visitTdsDatasetKeys` → `fromIdentifier` | TDS output, `keys: [ #/path/prop# ]` |
| 2 | `visitPath` | graph-fetch output, `keys: [ identifier ]` |
| 3 | `visitDeleteIndicatorForTds` | TDS `actionIndicator: DeleteIndicator { deleteField: #/…# }` |
| 4 | `visitFieldBasedForTds` | TDS `partitioning: FieldBased { partitionFields: [ #/…# ] }` |
| 5 | `visitMaxDeduplicationForTds` | TDS `deduplication: MaxVersion { versionField: #/…# }` |
| 6 | same rule, graph-fetch side | the mirror of each of the above |

Sites 1 and 2 are pinned here; the rest were reproduced during the same sweep.

## The pattern this belongs to

Three unrelated grammars, one defect shape — an optional or alternative grammar element
dereferenced without a null check, surfacing as a stack-trace-grade message:

* F17 `Class X projects Y` — `visitClass` dereferences `ctx.classBody()`, null for the
  projection form
* `HostedService actions: [ MyAction ]` — the walker dereferences
  `spec.actionBody().actionValue()` while the grammar makes `actionBody` optional
* this — the alternative arm, six times over

Eight sites across three grammars. Each is reachable by typing into a `.pure` file, and each
gives the author nothing to act on.
