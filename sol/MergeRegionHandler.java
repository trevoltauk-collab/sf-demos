package com.insurance.excel.resolver;

import com.insurance.excel.model.TemplateMappingConfig.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

/**
 * Handles all merge-region operations:
 *
 *  applyMerge()       — merge+write a static or dynamic value (MERGE_HEADER type)
 *  applyRowMerge()    — merge a column range on a given row (per-item rowMerges)
 *  applyColDefMerge() — merge within a ColumnDef that has mergeCell=true
 *  removeMerge()      — remove an existing merge that covers a cell (needed before re-merging)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MergeRegionHandler {

    private final JsonValueResolver jsonResolver;
    private final CellValueWriter cellWriter;

    /**
     * Apply a full MERGE_HEADER mapping: resolve value, write to top-left cell,
     * merge the region, and centre-align.
     */
    public void applyMerge(Sheet sheet, MergeSpec spec, Object jsonData) {
        if (spec == null) return;

        CellReference startRef = new CellReference(spec.getStartCell());
        CellReference endRef   = new CellReference(spec.getEndCell());

        int startRowIdx = startRef.getRow();
        int endRowIdx   = endRef.getRow();
        int startColIdx = startRef.getCol();
        int endColIdx   = endRef.getCol();

        // Resolve the value
        Object value;
        if (spec.getJsonPath() != null) {
            value = jsonResolver.resolveScalar(jsonData, spec.getJsonPath());
        } else {
            value = spec.getValue();
        }

        // Remove any existing merge covering this region to avoid overlap errors
        removeExistingMerge(sheet, startRowIdx, startColIdx);

        // Create/get the anchor cell
        Row row = sheet.getRow(startRowIdx);
        if (row == null) row = sheet.createRow(startRowIdx);
        Cell cell = row.getCell(startColIdx);
        if (cell == null) cell = row.createCell(startColIdx);

        // Write the value
        cellWriter.write(cell, value, spec.getDataType(), spec.getFormat());

        // Apply alignment to the cell's style
        applyAlignment(cell, spec.getAlign(), sheet.getWorkbook());

        // Register the merge region
        if (startRowIdx != endRowIdx || startColIdx != endColIdx) {
            CellRangeAddress region = new CellRangeAddress(
                    startRowIdx, endRowIdx, startColIdx, endColIdx);
            sheet.addMergedRegion(region);
            log.debug("  MERGE [{}:{}] value='{}'", spec.getStartCell(), spec.getEndCell(), value);
        }
    }

    /**
     * Apply a RowMergeSpec to a specific Excel row (used per-item in LIST/GROUPED_LIST).
     * Only merges — does not write a value (value is written separately by the column mapping).
     */
    public void applyRowMerge(Sheet sheet, RowMergeSpec spec, int rowIndex) {
        int startColIdx = columnLetterToIndex(spec.getStartCol());
        int endColIdx   = columnLetterToIndex(spec.getEndCol());

        if (startColIdx >= endColIdx) {
            log.warn("RowMergeSpec has startCol >= endCol on row {}: {}..{}",
                    rowIndex, spec.getStartCol(), spec.getEndCol());
            return;
        }

        removeExistingMerge(sheet, rowIndex, startColIdx);

        CellRangeAddress region = new CellRangeAddress(rowIndex, rowIndex, startColIdx, endColIdx);
        sheet.addMergedRegion(region);
        log.debug("  ROW_MERGE row={} cols={}:{}", rowIndex + 1, spec.getStartCol(), spec.getEndCol());
    }

    /**
     * Apply column and/or row span for a ColumnDef that has mergeCell=true.
     * Called after the cell value is written.
     */
    public void applyColDefMerge(Sheet sheet, Cell cell, ColumnDef colDef) {
        if (!colDef.isMergeCell() && colDef.getRowSpan() <= 1) return;

        int rowIdx    = cell.getRowIndex();
        int colIdx    = cell.getColumnIndex();
        int endRowIdx = rowIdx + Math.max(colDef.getRowSpan() - 1, 0);
        int endColIdx = colIdx + Math.max(colDef.getColSpan() - 1, 0);

        if (rowIdx == endRowIdx && colIdx == endColIdx) return; // no actual span

        removeExistingMerge(sheet, rowIdx, colIdx);
        CellRangeAddress region = new CellRangeAddress(rowIdx, endRowIdx, colIdx, endColIdx);
        sheet.addMergedRegion(region);
        log.debug("  COLDEF_MERGE cell={}{} span={}r x {}c",
                colDef.getCol(), rowIdx + 1, colDef.getRowSpan(), colDef.getColSpan());
    }

    /**
     * Apply merge + centre for a group header row spanning mergeHeaderCols (e.g. "B:G").
     */
    public void applyGroupHeaderMerge(Sheet sheet, int rowIndex, String mergeColRange) {
        if (mergeColRange == null || !mergeColRange.contains(":")) return;
        String[] parts = mergeColRange.split(":");
        int startCol = columnLetterToIndex(parts[0].trim());
        int endCol   = columnLetterToIndex(parts[1].trim());

        removeExistingMerge(sheet, rowIndex, startCol);
        CellRangeAddress region = new CellRangeAddress(rowIndex, rowIndex, startCol, endCol);
        sheet.addMergedRegion(region);

        // Centre-align the header cell
        Row row = sheet.getRow(rowIndex);
        if (row != null) {
            Cell cell = row.getCell(startCol);
            if (cell != null) {
                applyAlignment(cell, "CENTER", sheet.getWorkbook());
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Remove any existing merged region that contains the given cell.
     * POI throws if you add a merge that overlaps an existing one.
     */
    private void removeExistingMerge(Sheet sheet, int rowIdx, int colIdx) {
        for (int i = sheet.getNumMergedRegions() - 1; i >= 0; i--) {
            CellRangeAddress existing = sheet.getMergedRegion(i);
            if (existing.isInRange(rowIdx, colIdx)) {
                sheet.removeMergedRegion(i);
                log.debug("  Removed existing merge region at index {}", i);
            }
        }
    }

    private void applyAlignment(Cell cell, String align, Workbook wb) {
        CellStyle newStyle = wb.createCellStyle();
        newStyle.cloneStyleFrom(cell.getCellStyle());

        HorizontalAlignment ha = switch (align != null ? align.toUpperCase() : "CENTER") {
            case "LEFT"  -> HorizontalAlignment.LEFT;
            case "RIGHT" -> HorizontalAlignment.RIGHT;
            default      -> HorizontalAlignment.CENTER;
        };
        newStyle.setAlignment(ha);
        newStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        cell.setCellStyle(newStyle);
    }

    public static int columnLetterToIndex(String col) {
        int index = 0;
        for (char c : col.toUpperCase().toCharArray()) {
            index = index * 26 + (c - 'A' + 1);
        }
        return index - 1;
    }
}