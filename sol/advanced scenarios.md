# Advanced Template Scenarios — YAML Configuration Reference

This guide covers every complex real-world scenario that arises in healthcare insurance
Excel templates, with the exact YAML config and an explanation of how the engine handles it.

---

## Scenario Matrix

| Scenario | Mapping Type | Key YAML Properties |
|---|---|---|
| Column spacers between plans | `MULTI_COL` | `startCol` + `columnGap` |
| Blank rows between benefit groups | `LIST` / `GROUPED_LIST` | `gapRows` |
| Template formula cells in data region | Any | `skipFormulaCols` |
| Plan name spanning plan + spacer column | `MULTI_COL` column def | `mergeCell: true` + `colSpan` |
| Category header spanning full width | `GROUPED_LIST` | `mergeHeaderCols: "B:K"` |
| Benefit name spanning 2 columns | `LIST` / `GROUPED_LIST` | `rowMerges` |
| Static section title | `MERGE_HEADER` | `merge.startCell` / `merge.endCell` |
| Deductible shown as "Ind: $500 / Fam: $1,000" | Any column def | `composite.parts` |
| Age band shown as "Age 18 - 29" | Any column def | `composite.parts` |
| Plan detail block across 3 rows per plan | `MULTI_COL` column def | `rowOffset: 0/1/2` |
| Annual rate = monthly × 12 (stay live) | Any column def | `formula: "=C{ROW}*12"` |

---

## Scenario 1: Column Spacing Between Plans

### Problem
Your template has plans in columns C, E, G, I with blank spacer columns D, F, H between them
for visual separation and borders. You cannot write plan data into the spacer columns.

### Visual
```
     B          C              D      E              F      G
                PLAN 1        (gap)   PLAN 2        (gap)   PLAN 3
Row 8:  │  Plan Name │  Gold PPO   │       │ Silver HDHP │       │ Bronze HMO
Row 9:  │  Carrier   │  BlueCross  │       │ Aetna       │       │ Kaiser
Row 10: │  Type      │  PPO        │       │ HDHP        │       │ HMO
```

### YAML
```yaml
- id: plan_headers
  type: MULTI_COL
  jsonPath: "$.plans[*]"
  startRow: 8
  startCol: C        # first plan column
  columnGap: 1       # one blank spacer between each plan
  columns:
    - field: "planName"
      dataType: STRING
    - field: "carrier"
      dataType: STRING
    - field: "planType"
      dataType: STRING
```

**How it works:** instead of `columnMapping: {0:C, 1:D, 2:E}`, you set `startCol: C` and
`columnGap: 1`. The engine computes: plan 0 → col C, plan 1 → col E (C + 1 + 1 gap),
plan 2 → col G. The spacer columns D, F, H are never touched.

---

## Scenario 2: Row Spacing Between Benefit Items

### Problem
Each benefit row needs a blank line below it for visual separation, but the blank row
has template borders/shading that must be preserved.

### Visual
```
Row 26: │ Primary Care Visit │ $20 copay │ 20% after ded │
Row 27: │  (blank gap row — template borders intact)     │
Row 28: │ Specialist Visit   │ $40 copay │ 20% after ded │
Row 29: │  (blank gap row)                               │
```

### YAML
```yaml
- id: benefit_rows
  type: LIST
  jsonPath: "$.benefits[*]"
  startRow: 26
  gapRows: 1           # one blank row inserted after each item
  preserveRowStyle: true
  insertRowsIfNeeded: true
  columns:
    - col: B
      field: "benefitName"
      dataType: STRING
    - col: C
      field: "plan1Coverage"
      dataType: STRING
```

**How it works:** `gapRows: 1` means the stride between items is 2 (1 data row + 1 gap row).
The engine writes data on even rows (26, 28, 30, ...) and leaves odd rows (27, 29, 31, ...)
untouched, preserving whatever the template has there.

---

## Scenario 3: Formula Cells in the Data Region

### Problem
Your template has formula cells *inside* the data region. For example:
- Column J computes "# plans covering this benefit" (`=COUNTIF(D{ROW}:I{ROW},"<>N/A")`)
- Column H computes a weighted average (`=SUMPRODUCT(...)`)

The engine must skip these columns entirely to avoid overwriting the formulas.

### Visual
```
     B                C            D      ...   J (formula — DO NOT overwrite)
     Benefit Name     Plan 1 Cov.  ...          =COUNTIF(D26:I26,"<>N/A")
```

### YAML
```yaml
- id: benefit_rows
  type: LIST
  jsonPath: "$.benefits[*]"
  startRow: 26
  skipFormulaCols:
    - J              # skip col J for ALL rows of this mapping
    - H              # skip col H for ALL rows
    - I:1            # skip col I only on rowOffset 1 (second row of each item block)
  columns:
    - col: B
      field: "benefitName"
    # ... other columns, but NOT J or H
```

**Syntax variants:**
```yaml
skipFormulaCols:
  - E          # skip col E on all rows
  - G:0        # skip col G only on rowOffset 0 (primary row)
  - G:2        # skip col G on rowOffset 2 (third row of multi-row item)
```

---

## Scenario 4: Plan Name Spans Plan + Spacer Column (Merge)

### Problem
When using `columnGap`, the plan name should visually span both the plan column and the
adjacent spacer column (e.g., "Gold PPO 500" merges C8:D8).

### Visual
```
     C              D      E              F
   ┌──────────────────┐  ┌──────────────────┐
   │  Gold PPO 500    │  │  Silver HDHP 1500 │
   └──────────────────┘  └──────────────────┘
     BlueCross             Aetna
```

### YAML
```yaml
- id: plan_name_headers
  type: MULTI_COL
  jsonPath: "$.plans[*]"
  startRow: 8
  startCol: C
  columnGap: 1
  columns:
    - field: "planName"
      dataType: STRING
      mergeCell: true
      colSpan: 2          # merges C8:D8 for plan 0, E8:F8 for plan 1, etc.
    - field: "carrier"
      dataType: STRING
      # no mergeCell — stays in the plan column only
```

**How it works:** when `mergeCell: true` and `colSpan: 2`, after writing the value the engine
calls `sheet.addMergedRegion(C8:D8)`. The merge region is automatically adjusted for each
plan's column position.

---

## Scenario 5: Category Header Spanning Full Table Width

### Problem
Benefits are grouped by category (Medical, Pharmacy, etc.). Each category needs a bold
merged header row spanning columns B through J before its benefits are listed.

### Visual
```
┌─────────────────────────────── MEDICAL ────────────────────────────────┐
│ Primary Care Visit  │ $20 copay │ ... │
│ Specialist Visit    │ $40 copay │ ... │
├─────────────────────────────── PHARMACY ───────────────────────────────┤
│ Tier 1 — Generic    │ $10 copay │ ... │
```

### YAML
```yaml
- id: benefits_grouped
  type: GROUPED_LIST
  jsonPath: "$.benefits[*]"
  startRow: 25
  groupByField: "category"     # field in each item that determines the group
  mergeHeaderCols: "B:J"       # each category header merges this column range
  groupHeaderStyleRow: 25      # copy style from this template row for headers

  groupHeaderColumns:
    - col: B
      field: "$groupKey"       # "$groupKey" = the grouped field value (e.g. "Medical")
      dataType: STRING

  columns:                     # detail row columns (same as a LIST mapping)
    - col: B
      field: "benefitName"
    - col: D
      field: "plan1Coverage"
    # ...
```

**How it works:** the engine iterates the array, tracks when `category` changes, emits a
header row with the category label merged across B:J, then emits detail rows for that group.

---

## Scenario 6: Benefit Name Spanning Two Columns

### Problem
The benefit name column (B) should span into column C because B is narrow.
Every benefit detail row should have B:C merged for the name.

### Visual
```
     B                    C      D              E
   ┌─────────────────────────┐  ┌──────────────┐
   │  Primary Care Visit     │  │  $20 copay   │
   └─────────────────────────┘  └──────────────┘
   ┌─────────────────────────┐
   │  Specialist Visit       │
   └─────────────────────────┘
```

### YAML
```yaml
- id: benefit_rows
  type: LIST
  jsonPath: "$.benefits[*]"
  startRow: 26
  rowMerges:
    - rowOffset: 0     # applies on every item's primary row
      startCol: B
      endCol: C        # merge B:C on each item's row
  columns:
    - col: B
      field: "benefitName"
      dataType: STRING
    # col C is merged into B — no column def needed for C
    - col: D
      field: "plan1Coverage"
```

---

## Scenario 7: Composite Multi-Field Cell Values

### Problem
You need a single cell to show values assembled from multiple JSON fields:
- Age range: `"Age 18 - 29"` from `ageFrom=18` and `ageTo=29`
- Deductible summary: `"Ind: $500 | Fam: $1,000"` from nested `deductible.individual` and `deductible.family`
- Plan type + network: `"PPO (Large Network)"` from `planType` and `networkType`

### YAML
```yaml
# Age range
- col: C
  composite:
    parts: ["Age ", "$ageFrom", " - ", "$ageTo"]
    separator: ""
    numericFormat: "0"          # whole numbers, no decimal

# Deductible summary
- col: D
  composite:
    parts: ["Ind: $", "$deductible.individual", "  |  Fam: $", "$deductible.family"]
    separator: ""
    numericFormat: "#,##0"      # thousands separator

# Plan description
- col: E
  composite:
    parts: ["$planType", " (", "$networkType", ")"]
    separator: ""
```

**Parts syntax:**
- `"$fieldName"` → resolved from JSON item (dot-notation: `"$deductible.individual"`)
- `"literal text"` → written as-is (e.g. `"Age "`, `"  |  "`, `" ("`)

---

## Scenario 8: Multi-Row Item Blocks

### Problem
Each plan occupies 3 rows in the header block:
- Row 0: plan name (merged across 2 columns)
- Row 1: carrier name
- Row 2: composite type + network descriptor

### Visual
```
     C                    D      E
   ┌────────────────────────────────┐
   │      Gold PPO 500              │  ← row 0, merged C:D
   ├──────────────────┬─────────────┤
   │  BlueCross       │             │  ← row 1
   ├──────────────────┴─────────────┤
   │  PPO | Large Network           │  ← row 2, composite
   └────────────────────────────────┘
```

### YAML
```yaml
- id: plan_detail_header
  type: MULTI_COL
  jsonPath: "$.plans[*]"
  startRow: 70
  startCol: C
  columnGap: 1
  columns:
    - field: "planName"
      rowOffset: 0         # written on the item's primary row
      mergeCell: true
      colSpan: 2           # spans plan col + spacer col

    - field: "carrier"
      rowOffset: 1         # written one row below the primary row

    - composite:
        parts: ["$planType", " | ", "$networkType"]
        separator: ""
      rowOffset: 2         # written two rows below the primary row
```

The engine detects that `max(rowOffset)=2` → each item block is 3 rows tall, so
consecutive plans start at rows 70, 73, 76, etc.

---

## Combined Scenario: Rating Schedule

A complete real-world example combining gap rows, composite age bands, formula columns,
and formula skip:

```yaml
- id: rate_table
  type: LIST
  jsonPath: "$.rates[*]"
  startRow: 10
  gapRows: 0
  insertRowsIfNeeded: true
  skipFormulaCols:
    - H            # weighted average formula — skip all rows
    - I:0          # variance formula — skip row offset 0 only
  columns:
    - col: A
      field: "tier"
      dataType: STRING

    - col: B
      composite:
        parts: ["$ageFrom", " - ", "$ageTo"]
        separator: ""
      dataType: STRING

    - col: C
      field: "monthlyRate"
      dataType: CURRENCY

    - col: D
      formula: "=C{ROW}*12"      # annual rate — stays live in Excel

    - col: E
      field: "tobaccoSurcharge"
      dataType: CURRENCY

    - col: F
      formula: "=C{ROW}+E{ROW}"  # total with surcharge

    - col: G
      field: "enrolledCount"
      dataType: NUMBER

    # col H: skip (weighted average formula in template — not listed here)
    # col I: skip rowOffset 0 (variance formula — not listed for offset 0)
```

---

## Quick Decision Guide

```
Need to put plans in non-adjacent columns?
  → MULTI_COL with startCol + columnGap

Need blank rows between items?
  → LIST with gapRows: N

Have a formula cell inside the data area?
  → Add to skipFormulaCols: ["E"] or ["E:rowOffset"]

Need a section title or category header spanning multiple columns?
  → MERGE_HEADER (static) or GROUPED_LIST with mergeHeaderCols (dynamic)

Need a benefit name to span two columns?
  → LIST rowMerges: [{startCol: B, endCol: C}]

Need to show "Ind: $500 / Fam: $1,000" in one cell?
  → ColumnDef composite with parts: ["Ind: $", "$deductible.individual", ...]

Need one plan to occupy multiple rows (name, carrier, type)?
  → MULTI_COL columns with rowOffset: 0, 1, 2

Need benefits grouped with a bold category divider row?
  → GROUPED_LIST with groupByField + mergeHeaderCols
```