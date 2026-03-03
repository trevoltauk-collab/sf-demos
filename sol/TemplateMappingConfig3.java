package com.insurance.excel.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class TemplateMappingConfig {

    private TemplateInfo template;
    private List<SheetMapping> sheets;

    @Data
    public static class TemplateInfo {
        private String id;
        private String file;
        private String description;
    }

    @Data
    public static class SheetMapping {
        private String name;
        private List<CellMapping> mappings;
    }

    @Data
    public static class CellMapping {
        private String id;
        private MappingType type;

        // ── CELL / CELL_EXPR ──────────────────────────────────────────────
        private String cell;
        private String jsonPath;
        private String expression;
        private DataType dataType;
        private String format;

        // ── LIST / GROUPED_LIST / MULTI_COL shared ────────────────────────
        private Integer startRow;
        private Integer rowStepSize;
        private boolean preserveRowStyle = true;
        private boolean insertRowsIfNeeded = false;
        private List<ColumnDef> columns;
        private int gapRows = 0;
        private int columnGap = 0;
        private String startCol;
        private List<String> skipFormulaCols;
        private MergeSpec merge;
        private List<RowMergeSpec> rowMerges;
        private Map<Integer, String> columnMapping;  // explicit override for MULTI_COL

        // ── GROUPED_LIST ──────────────────────────────────────────────────
        private String groupByField;
        private List<ColumnDef> groupHeaderColumns;
        private Integer groupHeaderStyleRow;
        private String mergeHeaderCols;

        // ── BENEFIT_LOOKUP ────────────────────────────────────────────────

        /**
         * Column letter where the first plan's data lands.
         * Subsequent plan columns are computed as:
         *   colIndex = indexOf(planStartCol) + planIdx * (1 + planColumnGap)
         * This replaces the old static planColumns map entirely.
         */
        private String planStartCol;

        /**
         * Number of spacer columns between consecutive plan columns.
         *   0 → plans in C, D, E, F  (adjacent)
         *   1 → plans in C, E, G, I  (one blank spacer between each)
         * Must match columnGap in the plan_name_row MULTI_COL mapping.
         */
        private int planColumnGap = 0;

        /**
         * JsonPath within each plan object to its benefits array.
         * Default: "$.benefits[*]"
         */
        private String plansBenefitsPath;

        /**
         * Field within each benefit carrying the stable machine key.
         * Default: "benefitKey"
         */
        private String benefitKeyField;

        /** Field within each benefit carrying the coverage display value. */
        private String coverageField;

        /** Field within each benefit carrying the prior auth flag. */
        private String priorAuthField;

        /**
         * Controls where prior auth values are written relative to coverage:
         *   SAME_COL_NEXT_ROW — one row below coverage, same column
         *   NEXT_COL          — same row, column immediately to the right
         *                       (works naturally when planColumnGap >= 1)
         *   NONE              — prior auth not written
         */
        private PriorAuthLayout priorAuthLayout = PriorAuthLayout.NONE;

        /** Value written when a plan has no entry for a benefit. Default: "Not Covered" */
        private String fallbackValue = "Not Covered";

        /** benefitKey → 1-based Excel row. Owned by the template designer. */
        private Map<String, Integer> benefitRowMap;

        /** Row where benefits with no benefitRowMap entry are written. */
        private Integer overflowStartRow;

        /** Column where overflow benefit names are written. */
        private String overflowCol;

        /** If true, emit a WARN log for each unmapped benefit key. */
        private boolean warnOnUnmappedBenefits = true;
    }

    @Data
    public static class ColumnDef {
        private String col;
        private String field;
        private DataType dataType;
        private String format;
        private String formula;
        private CompositeSpec composite;
        private int rowOffset = 0;
        private boolean mergeCell = false;
        private int colSpan = 1;
        private int rowSpan = 1;
    }

    @Data
    public static class CompositeSpec {
        private List<String> parts;
        private String separator = "";
        private String numericFormat;
    }

    @Data
    public static class MergeSpec {
        private String startCell;
        private String endCell;
        private String value;
        private String jsonPath;
        private DataType dataType;
        private String format;
        private String align = "CENTER";
    }

    @Data
    public static class RowMergeSpec {
        private int rowOffset = 0;
        private String startCol;
        private String endCol;
    }

    public enum MappingType {
        CELL, CELL_EXPR, LIST, MULTI_COL, GROUPED_LIST, MERGE_HEADER, BENEFIT_LOOKUP
    }

    public enum DataType {
        STRING, NUMBER, CURRENCY, PERCENTAGE, DATE, BOOLEAN
    }

    public enum PriorAuthLayout {
        SAME_COL_NEXT_ROW,   // written one row below coverage in the same plan column
        NEXT_COL,            // written in the column immediately to the right of coverage
        NONE                 // prior auth not written
    }
}