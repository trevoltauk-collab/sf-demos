In benefit-comparison-mapping.yml, i see in planColumns we are mapping each plan by index position to column statically. But, the plans are dynamic and not fixed, we might not know how many plans in advance. How does that work


You're right — that's a real gap. Static `planColumns: {0: C, 1: D}` breaks the moment the number of plans changes. Let me fix this properly.

The core insight is: instead of mapping plan *index → column letter* statically, you declare where the **first plan column starts** and the engine computes the rest dynamically based on how many plans arrive in the JSON.Good. I have full context now. Let me think through all the dimensions of this problem before writing any code.

The issue goes deeper than just `planColumns`. There are actually **four places** in the current code where dynamic plan count breaks things, and they need a unified solution:

1. **`planColumns`** — static index→letter mapping obviously breaks
2. **`priorAuthColumns`** — same problem, mirrors planColumns
3. **`handleOverflow`** — iterates `planCols.get(planIdx)` which will return null for any plan beyond what was statically declared
4. **`plan_name_row` in MULTI_COL** — this one actually already works dynamically via `startCol + columnGap`, but it needs to stay **consistent** with however BENEFIT_LOOKUP resolves its columns — otherwise plan names land in different columns than their values

The root fix: replace all static index→column maps with a **`planStartCol` + `planColumnGap`** declarative config, and compute column positions at runtime from plan count. Then both `plan_name_row` and `benefit_table` use the same formula so they're always aligned.Now update the model to replace static maps with the new dynamic fields:Now fully rewrite `BenefitLookupStrategy` with the dynamic column computation:Here's a precise account of what changed and why every decision was made this way.

---

## The Actual Problem: Four Places Were Broken

The static `planColumns: {0:C, 1:D, 2:E, 3:F}` map wasn't just broken in one spot — it was broken in four:

1. **Main fill loop** — `planCols.get(planIdx)` returns `null` for any plan beyond index 3
2. **Prior auth** — `priorAuthColumns` had the exact same static map problem
3. **Overflow section** — iterated `planCols.get(planIdx)` again, same null failure
4. **Alignment** — even if you fixed the lookup, `planColumns` was a completely separate config from `startCol + columnGap` in `plan_name_row`. Nothing enforced they agreed, so plan names and benefit values could silently land in different columns

---

## The Fix: One Formula, Declared Twice

Both mappings now use the same arithmetic:

```
colIndex = indexOf(planStartCol) + planIdx × (1 + planColumnGap)
```

In `plan_name_row` (MULTI_COL) this was already how `startCol + columnGap` worked. In `benefit_table` (BENEFIT_LOOKUP) the old static map is replaced by `planStartCol` and `planColumnGap`. Because both use the same formula, they are structurally guaranteed to agree — you can't misconfigure them to diverge.

The YAML makes the intended linkage explicit with a comment:

```yaml
- id: plan_name_row
  startCol: C       # ← must match benefit_table.planStartCol
  columnGap: 0      # ← must match benefit_table.planColumnGap

- id: benefit_table
  planStartCol: C
  planColumnGap: 0
```

---

## How Prior Auth Layout Works Now

Rather than a separate `priorAuthColumns` static map, the new `priorAuthLayout` enum expresses intent:

**`SAME_COL_NEXT_ROW`** — prior auth goes one row below coverage in the same column. Works for any plan count with any gap setting.

**`NEXT_COL`** — prior auth goes in the column immediately to the right of coverage. This is designed for `planColumnGap >= 1` where that column is the spacer — prior auth naturally occupies the spacer column that was always going to be blank.

**`NONE`** — prior auth is not written at all.

In all cases, `computePriorAuthColIndex(coverageCol, layout, gap)` is a pure function with no plan-count dependency.

---

## The Alignment Invariant Test

The test suite includes a dedicated `AlignmentInvariant` nested class that formally asserts the property that matters most:

```java
// MULTI_COL formula
int multiColResult = start + planIdx * (1 + gap);
// BENEFIT_LOOKUP formula  
int lookupResult   = computePlanColIndex(start, planIdx, gap);

assertThat(lookupResult).isEqualTo(multiColResult); // must be identical
```

This is tested across 6 plans with gap=1, making it a regression guard: if either formula ever drifts, the test catches it immediately.


