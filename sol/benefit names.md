In the case of a plan benefit comparison table, the benefit names would need to be shown vertically in a single column with a "Benefits" header. The plan names would be in the top row next to "Benefits" column each in a separate column. The plan column values show the benefit values corresponding toeach benefit shown in benefit column. Would it be better to prepopulate benefit names in first column in the template itself before merging or dynamically populate them during merging by computing unique list of benefit names across all plans. If prepopulateing, I think we may need to a lookup by benefit name against benefit name in plan benefit list and fill accordingly, but  not sure .

Great architectural question. Let me think through both approaches properly before recommending.

## The Core Problem

The table structure is:

```
┌──────────────┬────────────┬────────────┬────────────┐
│   Benefits   │  Gold PPO  │ Silver HDHP│ Bronze HMO │
├──────────────┼────────────┼────────────┼────────────┤
│ PCP Visit    │ $20 copay  │ 20% ded    │ $30 copay  │
│ Specialist   │ $40 copay  │ 20% ded    │ N/A        │
│ Emergency    │ $250 copay │ 20% ded    │ 20% ded    │
└──────────────┴────────────┴────────────┴────────────┘
```

The critical challenge: **plans may not offer every benefit, and benefit order may differ per plan.** You need a lookup, not a sequential write.

---

## Approach A — Pre-populate Benefit Names in Template

The template ships with benefit names already typed in column B. The engine only fills plan columns.

**How the lookup works:**

```
For each plan column:
  For each row in template col B (benefit names):
    Find matching benefit in this plan's benefit list
    Write coverage value to this plan's column on that row
    If no match → write "Not Covered"
```

**Strengths:**
- Business controls the canonical benefit list, order, and display names independently of data
- Template designer controls grouping, merged headers, row heights, conditional formatting per benefit row — all set up before data lands
- Benefit rows never shift; formula cells, subtotals, and merged regions stay anchored
- Easy to add a benefit to all reports: just add a row to the template

**Weaknesses:**
- Template goes stale if a new benefit appears in JSON that isn't in the template — it silently gets skipped
- Requires strict name matching (case, whitespace) or a name→key mapping

---

## Approach B — Dynamically Compute Unique Benefit List

The engine collects all benefit names across all plans, deduplicates them, writes them to column B, then fills plan columns.

**How it works:**

```
Step 1: Compute union of all benefit names across all plans (preserving order)
Step 2: Write benefit names to col B starting at startRow
Step 3: For each plan column, for each benefit row, lookup and write value
```

**Strengths:**
- Automatically handles new benefits — nothing to maintain
- JSON is the single source of truth for what appears

**Weaknesses:**
- Engine must insert rows dynamically, which breaks any formula cells, merged regions, or conditional formatting that the template had below the data area
- Business loses control of benefit ordering, grouping, display names
- Cannot have pre-styled rows per benefit (e.g. a subtotal row after "Inpatient")

---

## Recommendation: Pre-populate with a Key-Based Lookup

Pre-populate is the right answer for a production insurance template, **but solve the name-matching brittleness** by having the YAML config map a stable `benefitKey` (from JSON) to template row positions rather than matching on display names directly.

The template benefit names are display labels only — the engine never tries to string-match them. Instead, the YAML declares which row each `benefitKey` lands on:

```yaml
benefitKeyColumn: A      # hidden col A holds machine keys; col B has display names
```

Or more robustly, the YAML explicitly maps benefit keys to row numbers:

```yaml
benefitRowMap:
  pcp_visit:          26
  specialist_visit:   27
  emergency_room:     28
  inpatient_hospital: 29
```

This means the template owner controls everything about layout, and the data just fills values into the correct pre-mapped rows — robust against typos and reordering.

Let me build this out fully:Now add `BENEFIT_LOOKUP` to the engine's dispatch and wire the strategy:Now make `BenefitLookupStrategy` a Spring `@Component` so it can be injected:Good. Now create the integration test and scenario doc:## The Recommendation and Why

**Pre-populate benefit names in the template, with key-based lookup** — not string matching. Here's the full reasoning:

### Why not dynamic benefit name computation

If the engine computes the unique benefit list from JSON and writes it to column B dynamically, it must insert rows into the sheet. That breaks every anchor below it: formula cells shift, merged regions de-anchor, conditional formatting rules point at wrong rows, and any subtotal rows the template designer put between benefit groups get displaced. For a production insurance template with category headers like "MEDICAL", "PHARMACY", "BEHAVIORAL HEALTH" already styled and positioned, this is effectively unusable.

### Why pre-populate works well here

The benefit catalogue for a plan year is stable. An insurer doesn't add "emergency room" mid-year. The template designer already knows exactly which benefits appear and in what order — that knowledge lives in the template. The engine's job is to fill values, not to reconstruct the structure.

### Why key-based lookup, not display name matching

String matching against benefit display names is fragile. "Primary Care Visit" vs "Primary Care (PCP)" vs "PCP Visit" — any of these mismatches silently leaves cells empty. The `benefitRowMap` in YAML maps a stable machine key (`pcp_visit`) to an Excel row number (12). The JSON carries the same `benefitKey`, and the template has whatever display label the designer wants in column B. The key and the label are decoupled.

### How it all fits together

The YAML `benefitRowMap` is the contract between the template designer and the data:

```yaml
benefitRowMap:
  pcp_visit:          12    # template row 12 has "Primary Care Visit" in col B
  specialist_visit:   13    # template row 13 has "Specialist Visit"
  emergency_room:     15    # row 14 is a merged sub-header, row 15 is ER
```

The engine iterates `$.plans[*]`, builds an O(1) `key → benefit` index per plan, then for each entry in `benefitRowMap` writes the matching coverage value or "Not Covered" to that exact row. **Benefit names in column B are never touched.** Template formula cells stay exactly where they are.

The `overflowStartRow` handles the safety valve: if a new benefit appears in JSON that has no row mapping yet, it's written to a clearly labelled section at the bottom of the sheet rather than silently dropped — so the QA reviewer can see what needs to be added to the template for next year.