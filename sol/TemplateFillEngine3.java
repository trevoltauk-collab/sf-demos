package com.insurance.excel.service;

import com.insurance.excel.model.TemplateMappingConfig;
import com.insurance.excel.model.TemplateMappingConfig.*;
import com.insurance.excel.resolver.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core engine applying all six mapping strategies to a POI Workbook.
 *
 *  CELL          scalar → single cell
 *  CELL_EXPR     expression → single cell
 *  MERGE_HEADER  value → merge-centred region
 *  LIST          array → repeating rows with gap/spacing, formula-skip, per-item merges, composite fields
 *  MULTI_COL     array → parallel columns with column gap support
 *  GROUPED_LIST  array grouped by field → category header row + detail rows
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateFillEngine {

    private final JsonValueResolver     jsonResolver;
    private final CellValueWriter       cellWriter;
    private final MergeRegionHandler    mergeHandler;
    private final CompositeValueBuilder compositeBuilder;
    private final FormulaSkipEvaluator  skipEvaluator;
    private final BenefitLookupStrategy  benefitLookup;

    public void fill(Workbook workbook, TemplateMappingConfig config, Object jsonData) {
        cellWriter.clearCache();

        for (SheetMapping sheetMapping : config.getSheets()) {
            Sheet sheet = workbook.getSheet(sheetMapping.getName());
            if (sheet == null) {
                log.warn("Sheet '{}' not found — skipping", sheetMapping.getName());
                continue;
            }
            log.debug("Processing sheet '{}'", sheetMapping.getName());

            for (CellMapping mapping : sheetMapping.getMappings()) {
                try {
                    applyMapping(sheet, mapping, jsonData);
                } catch (Exception e) {
                    log.error("Error in mapping id='{}' sheet='{}': {}",
                            mapping.getId(), sheetMapping.getName(), e.getMessage(), e);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Dispatch
    // ═══════════════════════════════════════════════════════════════════════════

    private void applyMapping(Sheet sheet, CellMapping mapping, Object jsonData) {
        switch (mapping.getType()) {
            case CELL         -> applyCell(sheet, mapping, jsonData);
            case CELL_EXPR    -> applyCellExpr(sheet, mapping, jsonData);
            case MERGE_HEADER -> mergeHandler.applyMerge(sheet, mapping.getMerge(), jsonData);
            case LIST         -> applyList(sheet, mapping, jsonData);
            case MULTI_COL    -> applyMultiCol(sheet, mapping, jsonData);
            case GROUPED_LIST   -> applyGroupedList(sheet, mapping, jsonData);
            case BENEFIT_LOOKUP -> benefitLookup.apply(sheet, mapping, jsonData);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CELL — scalar value into a single cell
    // ═══════════════════════════════════════════════════════════════════════════

    private void applyCell(Sheet sheet, CellMapping mapping, Object jsonData) {
        Object value = jsonResolver.resolveScalar(jsonData, mapping.getJsonPath());
        Cell cell = getOrCreateCell(sheet, mapping.getCell());
        cellWriter.write(cell, value, mapping.getDataType(), mapping.getFormat());
        log.debug("  CELL [{}]='{}' path={}", mapping.getCell(), value, mapping.getJsonPath());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CELL_EXPR
    // ═══════════════════════════════════════════════════════════════════════════

    private void applyCellExpr(Sheet sheet, CellMapping mapping, Object jsonData) {
        Object value = resolveExpression(mapping.getExpression(), jsonData);
        Cell cell = getOrCreateCell(sheet, mapping.getCell());
        cellWriter.write(cell, value, mapping.getDataType(), mapping.getFormat());
        log.debug("  CELL_EXPR [{}]='{}' expr={}", mapping.getCell(), value, mapping.getExpression());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIST — array → rows with spacing, formula-skip, composite, per-item merges
    // ═══════════════════════════════════════════════════════════════════════════

    private void applyList(Sheet sheet, CellMapping mapping, Object jsonData) {
        List<Object> items = jsonResolver.resolveList(jsonData, mapping.getJsonPath());
        if (items.isEmpty()) { log.debug("  LIST '{}': empty", mapping.getId()); return; }

        // rowsPerItem = how many Excel rows each item occupies
        // = max rowOffset across all columns + 1
        int rowsPerItem = computeRowsPerItem(mapping.getColumns());
        int gapRows     = mapping.getGapRows();

        // Total rows each item consumes including the gap after it:
        // e.g. 2 data rows + 1 gap row = stride of 3
        int stride = rowsPerItem + gapRows;

        // Override: if rowStepSize is explicitly set, use that instead
        if (mapping.getRowStepSize() != null && mapping.getRowStepSize() > 0) {
            stride = mapping.getRowStepSize();
        }

        int startRow0 = mapping.getStartRow() - 1; // convert to 0-based

        // Ensure enough rows exist in the sheet
        if (mapping.isInsertRowsIfNeeded() && items.size() > 1) {
            int existingRows = countExistingDataRows(sheet, startRow0, mapping.getColumns());
            int extra = (items.size() - existingRows) * stride;
            if (extra > 0) {
                sheet.shiftRows(startRow0 + existingRows * stride,
                                sheet.getLastRowNum(), extra);
            }
        }

        // Parse formula-skip config once
        Map<String, Set<Integer>> skipMap = skipEvaluator.parse(mapping.getSkipFormulaCols());

        // Grab template rows for style cloning (one per rowOffset)
        Map<Integer, Row> templateRows = new HashMap<>();
        if (mapping.isPreserveRowStyle()) {
            for (int offset = 0; offset < rowsPerItem; offset++) {
                Row tr = sheet.getRow(startRow0 + offset);
                if (tr != null) templateRows.put(offset, tr);
            }
        }

        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            int itemBaseRow = startRow0 + (i * stride);

            // Write all column defs for this item
            writeItemColumns(sheet, mapping.getColumns(), item, itemBaseRow,
                             skipMap, templateRows);

            // Apply per-item row merges (e.g. benefit name spanning cols B:C)
            if (mapping.getRowMerges() != null) {
                for (RowMergeSpec rms : mapping.getRowMerges()) {
                    mergeHandler.applyRowMerge(sheet, rms, itemBaseRow + rms.getRowOffset());
                }
            }
        }

        log.debug("  LIST '{}': wrote {} items (stride={})", mapping.getId(), items.size(), stride);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI_COL — array → parallel columns with optional column gap
    //
    // Template visual:
    //   Col B: benefit label
    //   Col C: plan 1 value    ← columnGap=0
    //   Col D: plan 2 value
    //
    // With columnGap=1:
    //   Col C: plan 1 value
    //   Col D: ← spacer (left as-is by engine)
    //   Col E: plan 2 value
    //   Col F: ← spacer
    //   Col G: plan 3 value
    // ═══════════════════════════════════════════════════════════════════════════

    private void applyMultiCol(Sheet sheet, CellMapping mapping, Object jsonData) {
        List<Object> items = jsonResolver.resolveList(jsonData, mapping.getJsonPath());
        if (items.isEmpty()) return;

        Map<Integer, String> colMap = resolveColumnMapping(mapping, items.size());
        int startRow0 = mapping.getStartRow() - 1;
        int rowsPerField = computeRowsPerItem(mapping.getColumns());

        Map<String, Set<Integer>> skipMap = skipEvaluator.parse(mapping.getSkipFormulaCols());

        for (int itemIdx = 0; itemIdx < items.size(); itemIdx++) {
            Object item = items.get(itemIdx);
            String colLetter = colMap.get(itemIdx);
            if (colLetter == null) {
                log.debug("  MULTI_COL '{}': no column for item {}", mapping.getId(), itemIdx);
                continue;
            }

            int colIndex = columnLetterToIndex(colLetter);

            for (int fIdx = 0; fIdx < mapping.getColumns().size(); fIdx++) {
                ColumnDef colDef = mapping.getColumns().get(fIdx);
                int rowIndex = startRow0 + fIdx + colDef.getRowOffset();

                // Check skip
                if (skipEvaluator.shouldSkip(skipMap, colLetter, fIdx)) {
                    log.debug("  MULTI_COL skip formula cell col={} rowOffset={}", colLetter, fIdx);
                    continue;
                }

                Row row = sheet.getRow(rowIndex);
                if (row == null) row = sheet.createRow(rowIndex);
                Cell cell = row.getCell(colIndex);
                if (cell == null) cell = row.createCell(colIndex);

                writeCellValue(cell, colDef, item, sheet);

                // Handle per-field merges (e.g. plan name spans 2 columns)
                if (colDef.isMergeCell() || colDef.getColSpan() > 1 || colDef.getRowSpan() > 1) {
                    mergeHandler.applyColDefMerge(sheet, cell, colDef);
                }
            }
        }

        log.debug("  MULTI_COL '{}': wrote {} plans", mapping.getId(), items.size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GROUPED_LIST — array grouped by a field → header row + detail rows per group
    //
    // Example JSON:
    //   benefits: [
    //     { category: "Medical",   benefitName: "PCP Visit",   plan1: "$20 copay" },
    //     { category: "Medical",   benefitName: "Specialist",  plan1: "$40 copay" },
    //     { category: "Pharmacy",  benefitName: "Generic",     plan1: "$10 copay" },
    //   ]
    //
    // Output Excel:
    //   ┌── MEDICAL (merged B:F, bold header row) ──┐
    //   │ PCP Visit      │ $20 copay │ ...           │
    //   │ Specialist     │ $40 copay │ ...           │
    //   ├── PHARMACY ────────────────────────────────┤
    //   │ Generic        │ $10 copay │ ...           │
    // ═══════════════════════════════════════════════════════════════════════════

    private void applyGroupedList(Sheet sheet, CellMapping mapping, Object jsonData) {
        List<Object> items = jsonResolver.resolveList(jsonData, mapping.getJsonPath());
        if (items.isEmpty()) return;

        String groupField = mapping.getGroupByField();
        if (groupField == null || groupField.isBlank()) {
            // No grouping — fall back to plain LIST behaviour
            applyList(sheet, mapping, jsonData);
            return;
        }

        // Group items preserving insertion order
        LinkedHashMap<String, List<Object>> grouped = new LinkedHashMap<>();
        for (Object item : items) {
            Object keyObj = jsonResolver.resolveField(item, groupField);
            String key = keyObj != null ? keyObj.toString() : "__UNGROUPED__";
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        int gapRows     = mapping.getGapRows();
        int rowsPerItem = computeRowsPerItem(mapping.getColumns());
        int stride      = rowsPerItem + gapRows;
        if (mapping.getRowStepSize() != null) stride = mapping.getRowStepSize();

        int currentRow0 = mapping.getStartRow() - 1;

        // Template rows for style cloning
        Map<Integer, Row> templateDataRows   = new HashMap<>();
        Row templateHeaderRow = null;
        if (mapping.isPreserveRowStyle()) {
            if (mapping.getGroupHeaderStyleRow() != null) {
                templateHeaderRow = sheet.getRow(mapping.getGroupHeaderStyleRow() - 1);
            }
            for (int offset = 0; offset < rowsPerItem; offset++) {
                Row tr = sheet.getRow(currentRow0 + offset);
                if (tr != null) templateDataRows.put(offset, tr);
            }
        }

        Map<String, Set<Integer>> skipMap = skipEvaluator.parse(mapping.getSkipFormulaCols());

        for (Map.Entry<String, List<Object>> groupEntry : grouped.entrySet()) {
            String groupLabel = groupEntry.getKey();
            List<Object> groupItems = groupEntry.getValue();

            // ── Emit the group header row ────────────────────────────────
            Row headerRow = sheet.getRow(currentRow0);
            if (headerRow == null) headerRow = sheet.createRow(currentRow0);

            // Copy header style if available
            if (templateHeaderRow != null) {
                copyRowStyle(templateHeaderRow, headerRow, sheet.getWorkbook());
            }

            // Write group header columns (or default: write label in first defined column)
            if (mapping.getGroupHeaderColumns() != null && !mapping.getGroupHeaderColumns().isEmpty()) {
                for (ColumnDef hCol : mapping.getGroupHeaderColumns()) {
                    int colIdx = columnLetterToIndex(hCol.getCol());
                    Cell hCell = headerRow.getCell(colIdx);
                    if (hCell == null) hCell = headerRow.createCell(colIdx);

                    // Header column value may reference the group key via "$groupKey" convention
                    if ("$groupKey".equals(hCol.getField())) {
                        cellWriter.write(hCell, groupLabel, hCol.getDataType(), hCol.getFormat());
                    } else if (hCol.getComposite() != null) {
                        // Composite within header — build from a synthetic group-key object
                        Map<String, Object> groupContext = Map.of(
                                "groupKey", groupLabel,
                                "itemCount", groupItems.size());
                        String composed = compositeBuilder.build(hCol.getComposite(), groupContext);
                        cellWriter.write(hCell, composed, DataType.STRING, null);
                    } else {
                        cellWriter.write(hCell, groupLabel, DataType.STRING, null);
                    }
                }
            } else {
                // Default: write group label in the first column of the mapping
                if (mapping.getColumns() != null && !mapping.getColumns().isEmpty()) {
                    int firstCol = columnLetterToIndex(mapping.getColumns().get(0).getCol());
                    Cell hCell = headerRow.getCell(firstCol);
                    if (hCell == null) hCell = headerRow.createCell(firstCol);
                    cellWriter.write(hCell, groupLabel, DataType.STRING, null);
                }
            }

            // Merge header row if requested
            if (mapping.getMergeHeaderCols() != null) {
                mergeHandler.applyGroupHeaderMerge(sheet, currentRow0, mapping.getMergeHeaderCols());
            }

            currentRow0++; // advance past header row

            // ── Emit detail rows for this group ─────────────────────────
            for (Object item : groupItems) {
                writeItemColumns(sheet, mapping.getColumns(), item, currentRow0,
                                 skipMap, templateDataRows);

                if (mapping.getRowMerges() != null) {
                    for (RowMergeSpec rms : mapping.getRowMerges()) {
                        mergeHandler.applyRowMerge(sheet, rms, currentRow0 + rms.getRowOffset());
                    }
                }

                currentRow0 += stride;
            }
        }

        log.debug("  GROUPED_LIST '{}': {} groups, {} total items",
                mapping.getId(), grouped.size(), items.size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Shared: write all column defs for one item at a given base row
    // ═══════════════════════════════════════════════════════════════════════════

    private void writeItemColumns(Sheet sheet,
                                   List<ColumnDef> columns,
                                   Object item,
                                   int itemBaseRow,
                                   Map<String, Set<Integer>> skipMap,
                                   Map<Integer, Row> templateRows) {
        if (columns == null) return;

        for (ColumnDef colDef : columns) {
            int rowOffset = colDef.getRowOffset();
            int rowIndex  = itemBaseRow + rowOffset;
            String colLetter = colDef.getCol().toUpperCase();

            // Formula skip check
            if (skipEvaluator.shouldSkip(skipMap, colLetter, rowOffset)) {
                log.debug("  SKIP formula col={} row={}", colLetter, rowIndex + 1);
                continue;
            }

            int colIndex = columnLetterToIndex(colLetter);

            Row row = sheet.getRow(rowIndex);
            if (row == null) row = sheet.createRow(rowIndex);

            Cell cell = row.getCell(colIndex);
            if (cell == null) cell = row.createCell(colIndex);

            // Clone style from template row
            if (!templateRows.isEmpty()) {
                Row tr = templateRows.get(rowOffset);
                if (tr != null) {
                    Cell tc = tr.getCell(colIndex);
                    if (tc != null) cellWriter.copyStyle(tc, cell);
                }
            }

            writeCellValue(cell, colDef, item, sheet);

            // Handle col/row span merges defined on the ColumnDef
            if (colDef.isMergeCell() || colDef.getColSpan() > 1 || colDef.getRowSpan() > 1) {
                mergeHandler.applyColDefMerge(sheet, cell, colDef);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cell value dispatch: formula | composite | regular field
    // ═══════════════════════════════════════════════════════════════════════════

    private void writeCellValue(Cell cell, ColumnDef colDef, Object item, Sheet sheet) {
        if (colDef.getFormula() != null) {
            cellWriter.writeFormula(cell, colDef.getFormula());

        } else if (colDef.getComposite() != null) {
            String composed = compositeBuilder.build(colDef.getComposite(), item);
            cellWriter.write(cell, composed, DataType.STRING, null);

        } else if (colDef.getField() != null) {
            Object value = jsonResolver.resolveField(item, colDef.getField());
            cellWriter.write(cell, value, colDef.getDataType(), colDef.getFormat());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Resolve columnMapping for MULTI_COL.
     * Uses explicit map if provided; otherwise auto-calculates from startCol + columnGap.
     */
    private Map<Integer, String> resolveColumnMapping(CellMapping mapping, int itemCount) {
        if (mapping.getColumnMapping() != null && !mapping.getColumnMapping().isEmpty()) {
            return mapping.getColumnMapping();
        }

        // Auto-calculate from startCol + columnGap
        Map<Integer, String> result = new LinkedHashMap<>();
        if (mapping.getStartCol() == null) return result;

        int startColIdx = columnLetterToIndex(mapping.getStartCol());
        int gap = mapping.getColumnGap(); // 0 = adjacent, 1 = one spacer col between plans

        for (int i = 0; i < itemCount; i++) {
            int colIdx = startColIdx + i * (1 + gap);
            result.