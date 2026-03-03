package com.insurance.excel.service;

import com.insurance.excel.model.TemplateMappingConfig.CellMapping;
import com.insurance.excel.model.TemplateMappingConfig.PriorAuthLayout;
import com.insurance.excel.resolver.CellValueWriter;
import com.insurance.excel.resolver.JsonValueResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * BENEFIT_LOOKUP strategy — fills a pre-populated benefit comparison table.
 *
 * Column layout is computed DYNAMICALLY at runtime from planStartCol + planColumnGap
 * and the actual number of plans in the JSON payload.
 *
 * No static index-to-column maps. Works for 1 plan, 5 plans, or 10 plans
 * without any YAML change.
 *
 *  planIdx 0 → column  indexOf(planStartCol)
 *  planIdx 1 → column  indexOf(planStartCol) + 1*(1 + planColumnGap)
 *  planIdx 2 → column  indexOf(planStartCol) + 2*(1 + planColumnGap)
 *  planIdx N → column  indexOf(planStartCol) + N*(1 + planColumnGap)
 *
 * Example with planStartCol=C, planColumnGap=0:
 *   3 plans → columns C, D, E
 *   5 plans → columns C, D, E, F, G
 *
 * Example with planStartCol=C, planColumnGap=1 (spacer between each plan):
 *   3 plans → columns C, E, G  (D, F are spacers)
 *   5 plans → columns C, E, G, I, K
 *
 * Prior auth placement (priorAuthLayout):
 *   SAME_COL_NEXT_ROW — one row below coverage in the same plan column
 *   NEXT_COL          — column immediately to the right of coverage
 *                       (natural fit when planColumnGap >= 1 — uses the spacer)
 *   NONE              — not written
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BenefitLookupStrategy {

    private final JsonValueResolver jsonResolver;
    private final CellValueWriter   cellWriter;

    // ── Public entry point ────────────────────────────────────────────────────

    public void apply(Sheet sheet, CellMapping mapping, Object jsonData) {

        // 1. Resolve plans from JSON
        List<Object> plans = jsonResolver.resolveList(jsonData, "$.plans[*]");
        if (plans.isEmpty()) {
            log.warn("BENEFIT_LOOKUP '{}': no plans found at $.plans[*]", mapping.getId());
            return;
        }

        // 2. Resolve config with safe defaults
        String planStartCol      = coalesce(mapping.getPlanStartCol(), "C");
        int    planColumnGap     = mapping.getPlanColumnGap();           // default 0
        String coverageField     = coalesce(mapping.getCoverageField(), "coverage");
        String keyField          = coalesce(mapping.getBenefitKeyField(), "benefitKey");
        String fallback          = coalesce(mapping.getFallbackValue(), "Not Covered");
        String benefitsPath      = coalesce(mapping.getPlansBenefitsPath(), "$.benefits[*]");
        PriorAuthLayout paLayout = mapping.getPriorAuthLayout() != null
                                   ? mapping.getPriorAuthLayout() : PriorAuthLayout.NONE;
        Map<String, Integer> rowMap = mapping.getBenefitRowMap();
        int startColIdx = columnLetterToIndex(planStartCol);

        // 3. Log the computed layout so it's visible in logs for debugging
        logComputedLayout(mapping.getId(), plans.size(), startColIdx, planColumnGap);

        // 4. Track keys that appear in JSON but have no row in benefitRowMap
        Set<String> unmappedKeys = new LinkedHashSet<>();

        // 5. Fill plan columns
        for (int planIdx = 0; planIdx < plans.size(); planIdx++) {
            Object plan       = plans.get(planIdx);
            int    colIdx     = computePlanColIndex(startColIdx, planIdx, planColumnGap);
            int    paColIdx   = computePriorAuthColIndex(colIdx, paLayout, planColumnGap);

            List<Object> planBenefits = jsonResolver.resolveList(plan, benefitsPath);
            Map<String, Object> benefitByKey = buildBenefitIndex(planBenefits, keyField);

            log.debug("  Plan[{}] col={} ({} benefits indexed)",
                    planIdx, columnIndexToLetter(colIdx), benefitByKey.size());

            // Fill every row declared in benefitRowMap
            if (rowMap != null) {
                for (Map.Entry<String, Integer> entry : rowMap.entrySet()) {
                    String benefitKey = entry.getKey();
                    int    rowIdx     = entry.getValue() - 1; // 1-based → 0-based

                    Object benefitItem = benefitByKey.get(benefitKey.toLowerCase());
                    String value = resolveCoverage(benefitItem, coverageField, fallback);

                    writeCellPreservingStyle(sheet, rowIdx, colIdx, value);

                    // Write prior auth
                    if (paLayout != PriorAuthLayout.NONE
                            && mapping.getPriorAuthField() != null
                            && benefitItem != null) {
                        writePriorAuth(sheet, mapping, benefitItem,
                                       rowIdx, colIdx, paColIdx, paLayout);
                    }
                }
            }

            // Collect unmapped keys from this plan
            for (String key : benefitByKey.keySet()) {
                if (rowMap == null || !rowMap.containsKey(key)) {
                    unmappedKeys.add(key);
                }
            }
        }

        // 6. Overflow — write benefits that had no row mapping
        if (!unmappedKeys.isEmpty()) {
            handleOverflow(sheet, mapping, plans, unmappedKeys,
                           keyField, coverageField, fallback,
                           benefitsPath, startColIdx, planColumnGap);
        }
    }

    // ── Column computation — the heart of the dynamic layout ─────────────────

    /**
     * Column index for the coverage value of planIdx.
     *
     *   planIdx=0: startColIdx
     *   planIdx=1: startColIdx + (1 + gap)
     *   planIdx=2: startColIdx + 2*(1 + gap)
     */
    static int computePlanColIndex(int startColIdx, int planIdx, int gap) {
        return startColIdx + planIdx * (1 + gap);
    }

    /**
     * Column index for the prior auth value, based on the chosen layout.
     *
     *   NEXT_COL          → coverage col + 1  (fits in spacer when gap >= 1)
     *   SAME_COL_NEXT_ROW → same column as coverage (row is shifted, not col)
     *   NONE              → not used
     */
    static int computePriorAuthColIndex(int coverageColIdx,
                                         PriorAuthLayout layout,
                                         int gap) {
        return switch (layout) {
            case NEXT_COL          -> coverageColIdx + 1;
            case SAME_COL_NEXT_ROW -> coverageColIdx;   // same col, different row
            case NONE              -> -1;               // sentinel: won't be used
        };
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, Object> buildBenefitIndex(List<Object> benefits, String keyField) {
        Map<String, Object> index = new LinkedHashMap<>();
        for (Object benefit : benefits) {
            Object keyObj = jsonResolver.resolveField(benefit, keyField);
            if (keyObj != null) {
                index.put(keyObj.toString().trim().toLowerCase(), benefit);
            }
        }
        return index;
    }

    private String resolveCoverage(Object benefitItem, String coverageField, String fallback) {
        if (benefitItem == null) return fallback;
        Object raw = jsonResolver.resolveField(benefitItem, coverageField);
        return raw != null ? raw.toString() : fallback;
    }

    private void writePriorAuth(Sheet sheet, CellMapping mapping, Object benefitItem,
                                 int coverageRowIdx, int coverageColIdx,
                                 int paColIdx, PriorAuthLayout layout) {
        Object paValue = jsonResolver.resolveField(benefitItem, mapping.getPriorAuthField());
        if (paValue == null) return;

        int targetRow = (layout == PriorAuthLayout.SAME_COL_NEXT_ROW)
                ? coverageRowIdx + 1
                : coverageRowIdx;
        int targetCol = paColIdx;

        writeCellPreservingStyle(sheet, targetRow, targetCol, paValue.toString());
    }

    private void writeCellPreservingStyle(Sheet sheet, int rowIdx, int colIdx, String value) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);

        Cell cell = row.getCell(colIdx);
        boolean isNew = (cell == null);
        if (isNew) cell = row.createCell(colIdx);

        cell.setCellValue(value);

        // For newly created cells (plan columns that weren't pre-formatted),
        // copy style from column B on the same row — preserves row borders/shading
        if (isNew) {
            Cell styleSource = row.getCell(1); // col B = index 1
            if (styleSource != null) cellWriter.copyStyle(styleSource, cell);
        }
    }

    private void handleOverflow(Sheet sheet, CellMapping mapping,
                                 List<Object> plans,
                                 Set<String> unmappedKeys,
                                 String keyField, String coverageField, String fallback,
                                 String benefitsPath, int startColIdx, int gap) {
        Integer overflowRow = mapping.getOverflowStartRow();
        if (overflowRow == null) {
            if (mapping.isWarnOnUnmappedBenefits()) {
                log.warn("BENEFIT_LOOKUP '{}': {} key(s) have no row mapping and " +
                         "overflowStartRow is not set — not written: {}",
                         mapping.getId(), unmappedKeys.size(), unmappedKeys);
            }
            return;
        }

        log.info("BENEFIT_LOOKUP '{}': {} unmapped benefit(s) → overflow at row {}",
                mapping.getId(), unmappedKeys.size(), overflowRow);

        int nameColIdx  = columnLetterToIndex(coalesce(mapping.getOverflowCol(), "B"));
        int currentRow0 = overflowRow - 1; // 0-based

        writeCellPreservingStyle(sheet, currentRow0, nameColIdx,
                "── Additional Benefits (not in template) ──");
        currentRow0++;

        for (String unmappedKey : unmappedKeys) {
            writeCellPreservingStyle(sheet, currentRow0, nameColIdx, unmappedKey);

            for (int planIdx = 0; planIdx < plans.size(); planIdx++) {
                int planColIdx = computePlanColIndex(startColIdx, planIdx, gap);

                List<Object> planBenefits = jsonResolver.resolveList(plans.get(planIdx), benefitsPath);
                Map<String, Object> idx   = buildBenefitIndex(planBenefits, keyField);

                Object benefit = idx.get(unmappedKey.toLowerCase());
                String value   = resolveCoverage(benefit, coverageField, fallback);

                writeCellPreservingStyle(sheet, currentRow0, planColIdx, value);
            }
            currentRow0++;
        }
    }

    private void logComputedLayout(String id, int planCount,
                                    int startColIdx, int gap) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("BENEFIT_LOOKUP '").append(id).append("': ")
          .append(planCount).append(" plan(s) → columns ");
        for (int i = 0; i < planCount; i++) {
            if (i > 0) sb.append(", ");
            sb.append(columnIndexToLetter(computePlanColIndex(startColIdx, i, gap)));
        }
        log.debug(sb.toString());
    }

    // ── Column letter ↔ index utilities ─────────────────────────────────────

    static int columnLetterToIndex(String col) {
        int index = 0;
        for (char c : col.toUpperCase().toCharArray()) {
            index = index * 26 + (c - 'A' + 1);
        }
        return index - 1;
    }

    static String columnIndexToLetter(int colIdx) {
        StringBuilder sb = new StringBuilder();
        int n = colIdx + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private static String coalesce(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }
}