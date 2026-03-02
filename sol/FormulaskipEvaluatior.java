package com.insurance.excel.resolver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Evaluates whether a given column (and optionally row-within-item-block) should be
 * skipped because it contains a template formula that must not be overwritten.
 *
 * Parses the skipFormulaCols YAML list which supports two formats:
 *
 *   "E"      → skip column E on ALL rows of the item block
 *   "E:0"    → skip column E only on row offset 0 within the item block
 *   "E:2"    → skip column E only on row offset 2 within the item block
 *
 * Examples in YAML:
 *   skipFormulaCols:
 *     - E           # subtotal formula column — skip always
 *     - G:1         # running-total formula on second row of each item block only
 */
@Slf4j
@Component
public class FormulaSkipEvaluator {

    /**
     * Parse the raw YAML skipFormulaCols list into a usable lookup structure.
     *
     * @return Map of colLetter(uppercase) → set of row offsets to skip (-1 means ALL offsets)
     */
    public Map<String, Set<Integer>> parse(List<String> skipFormulaCols) {
        Map<String, Set<Integer>> result = new HashMap<>();
        if (skipFormulaCols == null) return result;

        for (String entry : skipFormulaCols) {
            if (entry == null || entry.isBlank()) continue;

            String[] parts = entry.trim().split(":");
            String col = parts[0].trim().toUpperCase();

            if (parts.length == 1) {
                // Skip this column on ALL row offsets
                result.computeIfAbsent(col, k -> new HashSet<>()).add(-1);
            } else {
                try {
                    int rowOffset = Integer.parseInt(parts[1].trim());
                    result.computeIfAbsent(col, k -> new HashSet<>()).add(rowOffset);
                } catch (NumberFormatException e) {
                    log.warn("Invalid skipFormulaCols entry '{}' — expected 'COL' or 'COL:rowOffset'", entry);
                }
            }
        }

        return result;
    }

    /**
     * Determine if a specific column + rowOffset combination should be skipped.
     *
     * @param skipMap   result of parse()
     * @param colLetter column letter e.g. "E"
     * @param rowOffset 0-based row offset within the current item block
     */
    public boolean shouldSkip(Map<String, Set<Integer>> skipMap,
                               String colLetter,
                               int rowOffset) {
        if (skipMap == null || skipMap.isEmpty()) return false;
        Set<Integer> offsets = skipMap.get(colLetter.toUpperCase());
        if (offsets == null) return false;

        // -1 means "skip for ALL row offsets"
        return offsets.contains(-1) || offsets.contains(rowOffset);
    }
}