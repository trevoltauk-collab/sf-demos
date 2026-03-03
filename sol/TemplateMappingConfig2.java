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

        // CELL / CELL_EXPR
        private String cell;
        private String jsonPath;
        private String expression;
        private DataType dataType;
        private String format;

        // LIST / GROUPED_LIST / MULTI_COL shared
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
        private Map<Integer, String> columnMapping;

        // GROUPED_LIST
        private String groupByField;
        private List<ColumnDef> groupHeaderColumns;
        private Integer groupHeaderStyleRow;
        private String mergeHeaderCols;

        // BENEFIT_LOOKUP
        private String benefitKeyField;
        private String coverageField;
        private String priorAuthField;
        private Map<Integer, String> planColumns;
        private Map<Integer, String> priorAuthColumns;
        private int priorAuthRowOffset = 0;
        private String fallbackValue = "Not Covered";
        private Map<String, Integer> benefitRowMap;
        private Integer overflowStartRow;
        private String overflowCol;
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
}