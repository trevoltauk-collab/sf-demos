package com.insurance.excel.service;

import com.insurance.excel.model.TemplateMappingConfig.CellMapping;
import com.insurance.excel.model.TemplateMappingConfig.DataType;
import com.insurance.excel.resolver.CellValueWriter;
import com.insurance.excel.resolver.JsonValueResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.apache.poi.ss.usermodel.*;

import java.util.*;

/**
 * BENEFIT_LOOKUP strategy.
 *
 * Fills a benefit comparison table where:
 *   - Benefit names are PRE-POPULATED in column B of the template.
 *   - The engine only fills plan coverage values (columns C, D, E, F, ...).
 *   - Each benefit's row is located via benefitRowMap (benefitKey → Excel row).
 *   - Plans that don't cover a benefit get the fallbackValue.
 *   - Benefits in JSON that have no row mapping go to an overflow section.
 *
 * JSON structure expected:
 * {
 *   "plans": [
 *     {
 *       "planName": "Gold PPO",
 *       "benefits": [
 *         { "benefitKey": "pcp_visit",   "coverage": "$20 copay",  "priorAuth": "No" },
 *         { "benefitKey": "specialist",  "coverage": "$40 copay",  "priorAuth": "Yes" }
 *       ]
 *     }
 *   ]
 * }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BenefitLookupStrategy {

    private final JsonValueResolver jsonResolver;
    private final CellValueWriter   cellWriter;

    public void apply(Sheet sheet, CellMapping mapping, Object jsonData) {

        List<Object> plans = jsonResolver.resolveList(jsonData, "$.plans[*]");
        if (plans.isEmpty()) {
            log.warn("BENEFIT_LOOKUP '{}': no plans found at $.plans[*]", mapping.getId());
            return;
        }

        Map<String, Integer> rowMap      = mapping.getBenefitRowMap();
        Map<Integer, String> planCols    = mapping.getPlanColumns();
        String coverageField             = mapping.getCoverageField();
        String keyField                  = coalesce(mapping.getBenefitKeyField(), "benefitKey");
        String fallback                  = coalesce(mapping.getFallbackValue(), "Not Covered");

        // Track which benefit keys appear in JSON but have no row mapping
        Set<String> unmappedKeys = new LinkedHashSet<>();

        for (int planIdx = 0; planIdx < plans.size(); planIdx++) {
            Object plan = plans.get(planIdx);

            String colLetter = planCols != null ? planCols.get(planIdx) : null;
            if (colLetter == null) {
                log.warn("BENEFIT_LOOKUP '{}': no planColumns entry for plan index {}",
                        mapping.getId(), planIdx);
                continue;
            }
            int colIndex = columnLetterToIndex(colLetter);

            // Build a key→benefit lookup for this plan
            List<Object> planBenefits = jsonResolver.resolveList(plan, "$.benefits[*]");
            Map<String, Object> benefitByKey = buildBenefitIndex(planBenefits, keyField);

            log.debug("  Plan [{}] col={}: {} benefits indexed",
                    planIdx, colLetter, benefitByKey.size());

            // Step 1: Fill every row in benefitRowMap with this plan's value (or fallback)
            if (rowMap != null) {
                for (Map.Entry<String, Integer> entry : rowMap.entrySet()) {
                    String benefitKey  = entry.getKey();
                    int    excelRow    = entry.getValue() - 1; // convert to 0-based

                    Object benefitItem = benefitByKey.get(benefitKey);
                    String value;

                    if (benefitItem != null) {
                        Object raw = jsonResolver.resolveField(benefitItem, coverageField);
                        value = raw != null ? raw.toString() : fallback;
                    } else {
                        value = fallback;
                    }

                    writeCellPreservingStyle(sheet, excelRow, colIndex, value);

                    // Write prior auth if configured
                    if (mapping.getPriorAuthField() != null && benefitItem != null) {
                        writePriorAuth(sheet, mapping, planIdx, excelRow, colIndex, benefitItem);
                    }
                }
            }

            // Step 2: Collect benefits in JSON that aren't in rowMap
            for (String key : benefitByKey.keySet()) {
                if (rowMap == null || !rowMap.containsKey(key)) {
                    unmappedKeys.add(key);
                }
            }
        }

        // Step 3: Write overflow section for unmapped benefits
        if (!unmappedKeys.isEmpty()) {
            handleOverflow(sheet, mapping, plans, unmappedKeys, keyField,
                           coverageField, fallback);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Build a Map of benefitKey → benefit item for fast O(1) lookups within a plan.
     */
    private Map<String, Object> buildBenefitIndex(List<Object> benefits, String keyField) {
        Map<String, Object> index = new LinkedHashMap<>();
        for (Object benefit : benefits) {
            Object keyObj = jsonResolver.resolveField(benefit, keyField);
            if (keyObj != null) {
                String key = keyObj.toString().trim().toLowerCase();
                index.put(key, benefit);
            }
        }
        return index;
    }

    /**
     * Write the coverage value to the target cell while preserving the
     * template cell's existing style (borders, fills, fonts set by designer).
     */
    private void writeCellPreservingStyle(Sheet sheet, int rowIdx, int colIdx, String value) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);

        Cell cell = row.getCell(colIdx);
        boolean cellExisted = (cell != null);
        if (cell == null) cell = row.createCell(colIdx);

        // Preserve existing style — critical for pre-formatted templates
        // (the style is already set in the template; we only change the value)
        cell.setCellValue(value);

        if (!cellExisted) {
            // New cell: try to copy style from the corresponding row's first data column
            // so borders and shading match even on dynamically created cells
            Row templateRow = sheet.getRow(rowIdx);
            if (templateRow != null) {
                Cell styleSource = templateRow.getCell(1); // col B has the benefit name style
                if (styleSource != null) {
                    cellWriter.copyStyle(styleSource, cell);
                }
            }
        }
    }

    /**
     * Write prior auth value, handling row offset (same row or one row below).
     */
    private void writePriorAuth(Sheet sheet, CellMapping mapping, int planIdx,
                                 int baseRowIdx, int coverageColIdx, Object benefitItem) {
        Map<Integer, String> paColMap = mapping.getPriorAuthColumns();
        int rowOffset = mapping.getPriorAuthRowOffset();

        // Determine the column: use dedicated priorAuthColumns if configured,
        // otherwise write into the same coverage column at a row offset
        int targetCol = coverageColIdx; // default: same column
        if (paColMap != null && paColMap.containsKey(planIdx)) {
            targetCol = columnLetterToIndex(paColMap.get(planIdx));
        }

        int targetRow = baseRowIdx + rowOffset;
        Object paValue = jsonResolver.resolveField(benefitItem, mapping.getPriorAuthField());

        if (paValue != null) {
            writeCellPreservingStyle(sheet, targetRow, targetCol, paValue.toString());
        }
    }

    /**
     * Write benefits that appeared in JSON but have no row in benefitRowMap.
     * Writes to the overflow section so nothing is silently lost.
     */
    private void handleOverflow(Sheet sheet, CellMapping mapping,
                                 List<Object> plans,
                                 Set<String> unmappedKeys,
                                 String keyField, String coverageField, String fallback) {
        Integer overflowRow = mapping.getOverflowStartRow();
        if (overflowRow == null) {
            if (mapping.isWarnOnUnmappedBenefits()) {
                log.warn("BENEFIT_LOOKUP '{}': {} benefit key(s) have no row mapping and " +
                         "overflowStartRow is not set — these benefits are NOT written: {}",
                         mapping.getId(), unmappedKeys.size(), unmappedKeys);
            }
            return;
        }

        log.info("BENEFIT_LOOKUP '{}': writing {} unmapped benefits to overflow at row {}",
                mapping.getId(), unmappedKeys.size(), overflowRow);

        String overflowColLetter = coalesce(mapping.getOverflowCol(), "B");
        int nameColIdx = columnLetterToIndex(overflowColLetter);
        Map<Integer, String> planCols = mapping.getPlanColumns();

        int currentRow = overflowRow - 1; // 0-based

        // Write a section header
        writeCellPreservingStyle(sheet, currentRow, nameColIdx,
                "── Additional Benefits (not in template) ──");
        currentRow++;

        for (String unmappedKey : unmappedKeys) {
            // Write the benefit key as the row label
            writeCellPreservingStyle(sheet, currentRow, nameColIdx, unmappedKey);

            // Fill plan values across the row
            for (int planIdx = 0; planIdx < plans.size(); planIdx++) {
                String colLetter = planCols != null ? planCols.get(planIdx) : null;
                if (colLetter == null) continue;

                List<Object> planBenefits = jsonResolver.resolveList(
                        plans.get(planIdx), "$.benefits[*]");
                Map<String, Object> idx = buildBenefitIndex(planBenefits, keyField);

                Object benefit = idx.get(unmappedKey.toLowerCase());
                String value = benefit != null
                        ? coalesce(
                            asString(jsonResolver.resolveField(benefit, coverageField)),
                            fallback)
                        : fallback;

                writeCellPreservingStyle(sheet, currentRow,
                        columnLetterToIndex(colLetter), value);
            }
            currentRow++;
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static int columnLetterToIndex(String col) {
        int index = 0;
        for (char c : col.toUpperCase().toCharArray()) {
            index = index * 26 + (c - 'A' + 1);
        }
        return index - 1;
    }

    private static String coalesce(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    private static String asString(Object o) {
        return o != null ? o.toString() : null;
    }
}