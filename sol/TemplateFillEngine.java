package com.insurance.excel.service;

import com.insurance.excel.model.TemplateMappingConfig;
import com.insurance.excel.model.TemplateMappingConfig.*;
import com.insurance.excel.resolver.CellValueWriter;
import com.insurance.excel.resolver.JsonValueResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Core engine that applies a TemplateMappingConfig to an open Apache POI Workbook,
 * reading values from a JSON document.
 *
 * Supports four mapping strategies:
 *   CELL      — scalar JSON path → single Excel cell
 *   CELL_EXPR — computed expression → single Excel cell
 *   LIST      — JSON array → repeating rows (one item per row)
 *   MULTI_COL — JSON array → parallel columns (one item per column)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateFillEngine {

    private final JsonValueResolver jsonResolver;
    private final CellValueWriter cellWriter;

    /**
     * Fill the workbook using the given mapping config and JSON data.
     *
     * @param workbook  open POI workbook loaded from template
     * @param config    mapping config loaded from YAML
     * @param jsonData  root JSON document (Map, List, or raw JSON string)
     */
    public void fill(Workbook workbook, TemplateMappingConfig config, Object jsonData) {
        cellWriter.clearCache();

        for (SheetMapping sheetMapping : config.getSheets()) {
            Sheet sheet = workbook.getSheet(sheetMapping.getName());
            if (sheet == null) {
                log.warn("Sheet '{}' not found in workbook — skipping", sheetMapping.getName());
                continue;
            }

            log.debug("Processing sheet '{}'", sheetMapping.getName());

            for (CellMapping mapping : sheetMapping.getMappings()) {
                try {
                    applyMapping(sheet, mapping, jsonData);
                } catch (Exception e) {
                    log.error("Error applying mapping id='{}' on sheet '{}': {}",
                            mapping.getId(), sheetMapping.getName(), e.getMessage(), e);
                }
            }
        }
    }

    // ── Dispatches to the correct strategy ──────────────────────────────────

    private void applyMapping(Sheet sheet, CellMapping mapping, Object jsonData) {
        switch (mapping.getType()) {
            case CELL      -> applyCell(sheet, mapping, jsonData);
            case CELL_EXPR -> applyCellExpr(sheet, mapping, jsonData);
            case LIST      -> applyList(sheet, mapping, jsonData);
            case MULTI_COL -> applyMultiCol(sheet, mapping, jsonData);
        }
    }

    // ── CELL: scalar JsonPath → single cell ─────────────────────────────────

    private void applyCell(Sheet sheet, CellMapping mapping, Object jsonData) {
        Object value = jsonResolver.resolveScalar(jsonData, mapping.getJsonPath());
        Cell cell = getOrCreateCell(sheet, mapping.getCell());
        cellWriter.write(cell, value, mapping.getDataType(), mapping.getFormat());
        log.debug("  CELL [{}] = '{}' (path={})", mapping.getCell(), value, mapping.getJsonPath());
    }

    // ── CELL_EXPR: expression result → single cell ──────────────────────────

    private void applyCellExpr(Sheet sheet, CellMapping mapping, Object jsonData) {
        // Currently supports simple "$.fieldName.size()" style or a literal formula
        Object value = resolveExpression(mapping.getExpression(), jsonData);
        Cell cell = getOrCreateCell(sheet, mapping.getCell());
        cellWriter.write(cell, value, mapping.getDataType(), mapping.getFormat());
        log.debug("  CELL_EXPR [{}] = '{}' (expr={})", mapping.getCell(), value, mapping.getExpression());
    }

    // ── LIST: JSON array → one row per item ─────────────────────────────────

    private void applyList(Sheet sheet, CellMapping mapping, Object jsonData) {
        List<Object> items = jsonResolver.resolveList(jsonData, mapping.getJsonPath());
        if (items.isEmpty()) {
            log.debug("  LIST '{}': empty array, nothing to write", mapping.getId());
            return;
        }

        int startRow = mapping.getStartRow() - 1; // convert to 0-based
        int step = mapping.getRowStepSize() != null ? mapping.getRowStepSize() : 1;

        // Insert extra rows if template has fewer than we need
        if (mapping.isInsertRowsIfNeeded() && items.size() > 1) {
            int existingDataRows = countExistingDataRows(sheet, startRow, mapping.getColumns());
            int extraRowsNeeded = items.size() - existingDataRows;
            if (extraRowsNeeded > 0) {
                sheet.shiftRows(startRow + existingDataRows,
                                sheet.getLastRowNum(),
                                extraRowsNeeded * step);
                log.debug("  LIST '{}': inserted {} extra rows", mapping.getId(), extraRowsNeeded);
            }
        }

        Row templateRow = sheet.getRow(startRow); // use as style source

        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            int rowIndex = startRow + (i * step);
            Row row = sheet.getRow(rowIndex);
            if (row == null) row = sheet.createRow(rowIndex);

            for (ColumnDef colDef : mapping.getColumns()) {
                int colIndex = columnLetterToIndex(colDef.getCol());
                Cell cell = row.getCell(colIndex);
                if (cell == null) cell = row.createCell(colIndex);

                // Preserve template row's cell style if requested
                if (mapping.isPreserveRowStyle() && templateRow != null) {
                    Cell templateCell = templateRow.getCell(colIndex);
                    if (templateCell != null) cellWriter.copyStyle(templateCell, cell);
                }

                if (colDef.getFormula() != null) {
                    cellWriter.writeFormula(cell, colDef.getFormula());
                } else {
                    Object value = jsonResolver.resolveField(item, colDef.getField());
                    cellWriter.write(cell, value, colDef.getDataType(), colDef.getFormat());
                }
            }

            log.debug("  LIST '{}' row {}: filled {} columns", mapping.getId(), rowIndex + 1, mapping.getColumns().size());
        }
    }

    // ── MULTI_COL: JSON array → parallel columns (plan comparison style) ────

    private void applyMultiCol(Sheet sheet, CellMapping mapping, Object jsonData) {
        List<Object> items = jsonResolver.resolveList(jsonData, mapping.getJsonPath());
        if (items.isEmpty()) return;

        Map<Integer, String> columnMapping = mapping.getColumnMapping();
        int startRow = mapping.getStartRow() - 1; // 0-based

        for (int itemIdx = 0; itemIdx < items.size(); itemIdx++) {
            Object item = items.get(itemIdx);
            String colLetter = columnMapping.get(itemIdx);
            if (colLetter == null) {
                log.debug("  MULTI_COL '{}': no column defined for item index {}", mapping.getId(), itemIdx);
                continue;
            }

            int colIndex = columnLetterToIndex(colLetter);

            for (int fieldIdx = 0; fieldIdx < mapping.getColumns().size(); fieldIdx++) {
                ColumnDef colDef = mapping.getColumns().get(fieldIdx);
                int rowIndex = startRow + fieldIdx;

                Row row = sheet.getRow(rowIndex);
                if (row == null) row = sheet.createRow(rowIndex);
                Cell cell = row.getCell(colIndex);
                if (cell == null) cell = row.createCell(colIndex);

                Object value = jsonResolver.resolveField(item, colDef.getField());
                cellWriter.write(cell, value, colDef.getDataType(), colDef.getFormat());
            }

            log.debug("  MULTI_COL '{}' col {}: filled {} fields", mapping.getId(), colLetter, mapping.getColumns().size());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Parse a cell reference like "B3" and return the POI Cell, creating row/cell if needed. */
    private Cell getOrCreateCell(Sheet sheet, String cellRef) {
        CellReference ref = new CellReference(cellRef);
        Row row = sheet.getRow(ref.getRow());
        if (row == null) row = sheet.createRow(ref.getRow());
        Cell cell = row.getCell(ref.getCol());
        if (cell == null) cell = row.createCell(ref.getCol());
        return cell;
    }

    /** Convert column letter(s) to 0-based index. A=0, B=1, Z=25, AA=26 etc. */
    private int columnLetterToIndex(String col) {
        int index = 0;
        for (char c : col.toUpperCase().toCharArray()) {
            index = index * 26 + (c - 'A' + 1);
        }
        return index - 1;
    }

    /** Count how many rows of data are already present in the template for a LIST region. */
    private int countExistingDataRows(Sheet sheet, int startRowIndex, List<ColumnDef> columns) {
        if (columns == null || columns.isEmpty()) return 1;
        int colIndex = columnLetterToIndex(columns.get(0).getCol());
        int count = 0;
        for (int r = startRowIndex; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) break;
            Cell cell = row.getCell(colIndex);
            if (cell == null || cell.getCellType() == CellType.BLANK) break;
            count++;
        }
        return Math.max(count, 1); // always at least 1 template row
    }

    /** Minimal expression evaluator — extend with SpEL for more complex use cases. */
    private Object resolveExpression(String expression, Object jsonData) {
        if (expression == null) return null;
        // Support "$.field.size()" style
        if (expression.endsWith(".size()")) {
            String path = expression.replace(".size()", "");
            List<Object> list = jsonResolver.resolveList(jsonData, path);
            return list.size();
        }
        // Direct JsonPath fallback
        return jsonResolver.resolveScalar(jsonData, expression);
    }
}