package com.example.demo.docgen.renderer;

import com.example.demo.docgen.aspect.LogExecutionTime;
import com.example.demo.docgen.core.RenderContext;
import com.example.demo.docgen.exception.ResourceLoadingException;
import com.example.demo.docgen.mapper.FieldMappingStrategy;
import com.example.demo.docgen.model.FieldMappingGroup;
import com.example.demo.docgen.model.PageSection;
import com.example.demo.docgen.model.SectionType;
import com.example.demo.docgen.service.NamespaceResolver;
import com.example.demo.docgen.service.ResourceStorageClient;
import com.example.demo.docgen.service.TemplateLoader;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Renderer for Excel templates
 * Fills cells with data from the context using configurable mapping strategies
 * Supports both single mapping strategy and multiple mapping groups
 * Supports namespace-aware template loading
 * 
 * Note: Currently returns PDDocument per interface contract. 
 * Future enhancement will support native Excel file output through a separate service.
 */
@Slf4j
@Component
public class ExcelSectionRenderer implements SectionRenderer, ExcelRenderer {
    
    private final List<FieldMappingStrategy> mappingStrategies;
    private final TemplateLoader templateLoader;
    private final NamespaceResolver namespaceResolver;
    private ResourceStorageClient resourceStorageClient;
    
    @Autowired
    public ExcelSectionRenderer(List<FieldMappingStrategy> mappingStrategies, TemplateLoader templateLoader, NamespaceResolver namespaceResolver) {
        this.mappingStrategies = mappingStrategies;
        this.templateLoader = templateLoader;
        this.namespaceResolver = namespaceResolver;
    }

    @Autowired(required = false)
    public void setResourceStorageClient(ResourceStorageClient resourceStorageClient) {
        this.resourceStorageClient = resourceStorageClient;
    }

    // Backwards-compatible constructor for tests and legacy callers
    public ExcelSectionRenderer(List<FieldMappingStrategy> mappingStrategies, TemplateLoader templateLoader) {
        this(mappingStrategies, templateLoader, new NamespaceResolver());
    }
    
    @Override
    @LogExecutionTime("Excel Rendering")
    public PDDocument render(PageSection section, RenderContext context) {
        // Delegate to workbook renderer to keep PDF-based contract intact
        try {
            Workbook workbook = renderWorkbook(section, context);
            // Keep backward-compatible behavior: store workbook and return placeholder PDDocument
            if (workbook != null) context.setMetadata("excelWorkbook", workbook);
            return new PDDocument();
        } catch (ResourceLoadingException rle) {
            throw rle;
        }
    }

    @Override
    public Workbook renderWorkbook(PageSection section, RenderContext context) {
        try {
            log.info("Rendering Excel workbook: {} with template: {}", section.getSectionId(), section.getTemplatePath());
            
            // For multi-section rendering: check if a workbook already exists in the context
            // If it does and matches the current template path, reuse it instead of loading fresh
            Workbook workbook = null;
            Object existingWb = context.getMetadata("excelWorkbook");
            String lastTemplatePath = (String) context.getMetadata("excelTemplateLastPath");
            
            if (existingWb instanceof org.apache.poi.ss.usermodel.Workbook && 
                section.getTemplatePath().equals(lastTemplatePath)) {
                // Reuse existing workbook from previous section (same template)
                workbook = (org.apache.poi.ss.usermodel.Workbook) existingWb;
                log.debug("Reusing existing Excel workbook from previous section (same template: {})", section.getTemplatePath());

                // Decision: Clone sheet only for single-sheet templates
                // For multi-sheet templates, sheets already exist - don't clone
                boolean isMultiSheetTemplate = workbook.getNumberOfSheets() > 1;
                
                if (isMultiSheetTemplate) {
                    // Multi-sheet template: sheets are already pre-created in template
                    // Don't clone - instead, write to sheets by name using qualified cell refs
                    log.debug("Multi-sheet template detected ({} sheets). Skipping clone, will write to named sheets", 
                             workbook.getNumberOfSheets());
                    // Don't change excelCurrentSheetIndex - let fillExcelCells use qualified refs like "Summary!A1"
                    // If no qualified ref is used, it will fall back to Sheet0
                } else {
                    // Single-sheet template: must clone for each section
                    Sheet original = workbook.getSheetAt(0);
                    Sheet clone = workbook.cloneSheet(workbook.getSheetIndex(original));
                    // give it a meaningful name if possible (sectionId or fallback to index)
                    int newIdx = workbook.getSheetIndex(clone);
                    String newName = section.getSectionId() != null ? section.getSectionId() : "Sheet" + newIdx;
                    try {
                        workbook.setSheetName(newIdx, newName);
                    } catch (IllegalArgumentException e) {
                        // name already in use, ignore
                    }
                    // mark the active sheet index for mapping in fillExcelCells
                    context.setMetadata("excelCurrentSheetIndex", newIdx);
                    log.debug("Single-sheet template detected. Cloned sheet for section: {}", section.getSectionId());
                }

            } else {
                // Load fresh template (first section or different template path)
                workbook = loadTemplateAsWorkbook(section.getTemplatePath(), context);
                context.setMetadata("excelTemplateLastPath", section.getTemplatePath());
                // first sheet will be the one we fill
                context.setMetadata("excelCurrentSheetIndex", 0);
                log.debug("Loaded fresh Excel template: {}", section.getTemplatePath());
            }
            
            fillExcelCells(workbook, section, context);
            context.setMetadata("excelWorkbook", workbook);
            log.debug("Excel workbook rendered and stored in context. Current sheets: {}", workbook.getNumberOfSheets());
            return workbook;
        } catch (ResourceLoadingException rle) {
            throw rle;
        } catch (IOException e) {
            throw new RuntimeException("Failed to render Excel workbook: " + section.getSectionId(), e);
        }
    }
    
    @Override
    public boolean supports(SectionType type) {
        return type == SectionType.EXCEL;
    }
    
    /**
     * Map field values using either single strategy or multiple groups
     */
    private Map<String, String> mapFieldValues(PageSection section, RenderContext context) {
        if (section.hasMultipleMappingGroups()) {
            return mapWithMultipleGroups(section, context);
        } else {
            return mapWithSingleStrategy(section, context);
        }
    }
    
    /**
     * Traditional single-strategy mapping (backward compatible)
     */
    private Map<String, String> mapWithSingleStrategy(PageSection section, RenderContext context) {
        log.info("Rendering Excel section: {} with single mapping type: {}", 
                section.getSectionId(), section.getMappingType());
        
        FieldMappingStrategy strategy = findMappingStrategy(section.getMappingType());
        return strategy.mapData(context.getData(), section.getFieldMappings());
    }
    
    /**
     * Multi-group mapping - merges results from multiple strategies
     * Supports basePath optimization to avoid repeated filter executions
     */
    private Map<String, String> mapWithMultipleGroups(PageSection section, RenderContext context) {
        log.info("Rendering Excel section: {} with {} mapping groups", 
                section.getSectionId(), section.getFieldMappingGroups().size());
        
        Map<String, String> allFieldValues = new HashMap<>();
        
        for (FieldMappingGroup group : section.getFieldMappingGroups()) {
            log.debug("Processing Excel mapping group with type: {}, fields: {}, basePath: {}", 
                     group.getMappingType(), group.getFields().size(), group.getBasePath());
            
            FieldMappingStrategy strategy = findMappingStrategy(group.getMappingType());
            
            Map<String, String> groupValues;
            if (group.getRepeatingGroup() != null) {
                // Handle repeating group (e.g., array of items)
                groupValues = mapRepeatingGroup(group, context, strategy);
            } else if (group.getBasePath() != null && !group.getBasePath().isEmpty()) {
                // Optimize: evaluate basePath ONCE, then map fields relative to that result
                groupValues = strategy.mapDataWithBasePath(
                    context.getData(), 
                    group.getBasePath(), 
                    group.getFields()
                );
            } else {
                // Standard mapping without basePath optimization
                groupValues = strategy.mapData(context.getData(), group.getFields());
            }
            
            // Merge into results (later groups override earlier ones for same field)
            allFieldValues.putAll(groupValues);
            
            log.debug("Mapped {} cells using {} strategy", 
                     groupValues.size(), group.getMappingType());
        }
        
        log.info("Total cells mapped: {}", allFieldValues.size());
        return allFieldValues;
    }

    /**
     * Maps a repeating group of data (e.g., an array of items) to numbered Excel cells.
     */
    private Map<String, String> mapRepeatingGroup(FieldMappingGroup group, RenderContext context, FieldMappingStrategy strategy) {
        Map<String, String> result = new HashMap<>();
        com.example.demo.docgen.model.RepeatingGroupConfig config = group.getRepeatingGroup();
        
        if (group.getBasePath() == null || group.getBasePath().isEmpty()) {
            log.warn("Repeating group specified without basePath in section {}", group);
            return result;
        }
        
        // 1. Evaluate basePath to get the collection
        Object collection = strategy.evaluatePath(context.getData(), group.getBasePath());
        
        if (!(collection instanceof List)) {
            log.warn("Repeating group basePath '{}' did not evaluate to a List. Got: {}", 
                    group.getBasePath(), collection != null ? collection.getClass().getName() : "null");
            return result;
        }
        
        List<?> items = (List<?>) collection;
        int startIndex = config.getStartIndex();
        int maxItems = config.getMaxItems() != null ? config.getMaxItems() : items.size();
        int count = Math.min(items.size(), maxItems);
        
        log.debug("Mapping Excel repeating group with {} items (max: {})", count, maxItems);
        
        // 2. Iterate over items and map fields
        for (int i = 0; i < count; i++) {
            Object item = items.get(i);
            int displayIndex = startIndex + i;
            
            // Construct Excel cell references for this item
            Map<String, String> itemFieldMappings = new HashMap<>();
            for (Map.Entry<String, String> fieldEntry : config.getFields().entrySet()) {
                String baseCellRef = fieldEntry.getKey();
                String dataPath = fieldEntry.getValue();
                
                // Construct cell reference based on position and separator
                StringBuilder cellRef = new StringBuilder();
                if (config.getPrefix() != null) cellRef.append(config.getPrefix());
                
                if (config.getIndexPosition() == com.example.demo.docgen.model.RepeatingGroupConfig.IndexPosition.BEFORE_FIELD) {
                    cellRef.append(displayIndex);
                    if (config.getIndexSeparator() != null) cellRef.append(config.getIndexSeparator());
                    cellRef.append(baseCellRef);
                } else {
                    cellRef.append(baseCellRef);
                    if (config.getIndexSeparator() != null) cellRef.append(config.getIndexSeparator());
                    cellRef.append(displayIndex);
                }
                
                if (config.getSuffix() != null) cellRef.append(config.getSuffix());
                
                itemFieldMappings.put(cellRef.toString(), dataPath);
            }
            
            // Map fields for this single item
            Map<String, String> itemValues = strategy.mapFromContext(item, itemFieldMappings);
            result.putAll(itemValues);
        }
        
        return result;
    }

    /**
     * Populate a repeating group as an Excel table when RepeatingGroupConfig.startCell is provided.
     * The config.fields map is interpreted as columnKey -> dataPath (columnKey can be 'A' or 'B' or column index as string)
     */
    private void populateRepeatingGroupAsTable(Workbook workbook, FieldMappingGroup group, RenderContext context, FieldMappingStrategy strategy) {
        com.example.demo.docgen.model.RepeatingGroupConfig config = group.getRepeatingGroup();
        if (config == null) return;

        if (group.getBasePath() == null || group.getBasePath().isEmpty()) {
            log.warn("Repeating group specified without basePath in section {}", group);
            return;
        }

        Object collection = strategy.evaluatePath(context.getData(), group.getBasePath());
        if (!(collection instanceof List)) {
            log.warn("Repeating group basePath '{}' did not evaluate to a List. Got: {}", group.getBasePath(), collection != null ? collection.getClass().getName() : "null");
            return;
        }

        List<?> items = (List<?>) collection;
        int maxItems = config.getMaxItems() != null ? config.getMaxItems() : items.size();
        int count = Math.min(items.size(), maxItems);

        if (config.getStartCell() == null || config.getStartCell().isEmpty()) {
            // No startCell configured: fall back to previous behavior (numbered field names)
            mapRepeatingGroup(group, context, strategy);
            return;
        }

        // Parse start cell (may include sheet)
        String sheetName = null;
        String startCellRef = config.getStartCell();
        if (startCellRef.contains("!")) {
            String[] parts = startCellRef.split("!");
            sheetName = parts[0].trim();
            startCellRef = parts[1].trim();
        }

        Sheet sheet = sheetName != null ? workbook.getSheet(sheetName) : workbook.getSheetAt(0);
        if (sheet == null) {
            log.warn("Sheet '{}' not found for repeating group table", sheetName);
            return;
        }

        CellReference startRef = new CellReference(startCellRef);
        int startRow = startRef.getRow();
        int startCol = startRef.getCol();

        // For each item, write columns as per config.fields map
        for (int i = 0; i < count; i++) {
            Object item = items.get(i);
            int targetRowIndex = startRow + i;

            // If insertRows true and a row already exists at target, shift rows down
            if (Boolean.TRUE.equals(config.getInsertRows())) {
                sheet.shiftRows(targetRowIndex, sheet.getLastRowNum(), 1);
            }

            Row row = sheet.getRow(targetRowIndex);
            if (row == null) row = sheet.createRow(targetRowIndex);

            for (Map.Entry<String, String> colEntry : config.getFields().entrySet()) {
                String colKey = colEntry.getKey();
                String dataPath = colEntry.getValue();

                int colIndex;
                try {
                    // If key is a letter like A or AB
                    colIndex = CellReference.convertColStringToIndex(colKey);
                } catch (Exception ex) {
                    // Fallback: try parse as integer (1-based)
                    try {
                        colIndex = Integer.parseInt(colKey) - 1;
                    } catch (Exception parseEx) {
                        log.warn("Invalid column key '{}' in repeating group; skipping", colKey);
                        continue;
                    }
                }

                // Map value from item context
                Map<String, String> single = Collections.singletonMap("_tmp", dataPath);
                Map<String, String> mapped = strategy.mapFromContext(item, single);
                String value = mapped.getOrDefault("_tmp", "");

                // Write cell with overwrite control
                setCellValueAt(sheet, targetRowIndex, colIndex, value, Boolean.TRUE.equals(config.getOverwrite()));
            }
        }
    }
    
    /**
     * Find a mapping strategy by type
     */
    private FieldMappingStrategy findMappingStrategy(com.example.demo.docgen.mapper.MappingType mappingType) {
        return mappingStrategies.stream()
            .filter(strategy -> strategy.supports(mappingType))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "No mapping strategy found for type: " + mappingType));
    }
    
    /**
     * Load Excel template as a Workbook
     */
    @LogExecutionTime("Loading Excel Template")
    private Workbook loadTemplateAsWorkbook(String templatePath, RenderContext context) throws IOException, ResourceLoadingException {
        // Resolve namespace-aware template path
        String effectiveTemplatePath = templatePath;
        if (effectiveTemplatePath == null && context != null && context.getTemplate() != null) {
            // 1) Check explicit section overrides
            if (context.getTemplate().getSectionOverrides() != null) {
                String override = context.getTemplate().getSectionOverrides().get(context.getCurrentSectionId());
                if (override != null && !override.isEmpty()) {
                    effectiveTemplatePath = override;
                }
            }

            // 2) As a last resort, try to find the section entry in the template and use its templatePath
            if (effectiveTemplatePath == null && context.getTemplate().getSections() != null) {
                for (com.example.demo.docgen.model.PageSection s : context.getTemplate().getSections()) {
                    if (s != null && s.getSectionId() != null && s.getSectionId().equals(context.getCurrentSectionId())) {
                        if (s.getTemplatePath() != null && !s.getTemplatePath().isEmpty()) {
                            effectiveTemplatePath = s.getTemplatePath();
                            break;
                        }
                    }
                }
            }
        }

        String resolvedPath = namespaceResolver.resolveResourcePath(effectiveTemplatePath, context.getNamespace());

        if (resolvedPath == null) {
            throw new ResourceLoadingException(
                "RESOURCE_RESOLUTION_FAILED",
                "Failed to resolve Excel template path: " + effectiveTemplatePath + " in namespace: " + context.getNamespace()
            );
        }

        log.debug("Resolved Excel template path: {} -> {}", effectiveTemplatePath, resolvedPath);

        // Attempt external resource storage first (if configured), then fall back to TemplateLoader
        byte[] templateBytes = null;
        if (resourceStorageClient != null && resourceStorageClient.isEnabled()) {
            String storageNamespace = namespaceResolver.extractNamespaceFromPath(resolvedPath);
            String relativePath;
            String prefix = storageNamespace + "/templates/";
            if (resolvedPath.startsWith(prefix)) {
                relativePath = resolvedPath.substring(prefix.length());
            } else {
                int idx = resolvedPath.indexOf("/templates/");
                if (idx >= 0) {
                    relativePath = resolvedPath.substring(idx + "/templates/".length());
                } else {
                    relativePath = resolvedPath;
                }
            }
            log.info("Attempting to load Excel template from external storage: namespace={}, path={}", storageNamespace, relativePath);
            try {
                templateBytes = resourceStorageClient.getResourceBytes(storageNamespace, relativePath);
            } catch (Exception e) {
                log.warn("Failed to load from external storage, falling back to TemplateLoader: {}", e.getMessage());
            }
        }

        // Fallback to TemplateLoader
        if (templateBytes == null) {
            log.info("Loading Excel template from TemplateLoader: {}", resolvedPath);
            try {
                templateBytes = templateLoader.getResourceBytes(resolvedPath);
            } catch (Exception e) {
                throw new ResourceLoadingException(
                    "TEMPLATE_LOAD_FAILED",
                    "Failed to load Excel template: " + resolvedPath + ". Error: " + e.getMessage()
                );
            }
        }

        // Load workbook from bytes
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(templateBytes));
        } catch (Exception e) {
            throw new ResourceLoadingException(
                "TEMPLATE_PARSE_FAILED",
                "Failed to parse Excel template: " + resolvedPath + ". Error: " + e.getMessage()
            );
        }
    }
    
    /**
     * Fill Excel cells with values from field mappings
     * Supports cell references like "A1", "B5", "Sheet2!C10" or named ranges
     */
    private void fillExcelCells(Workbook workbook, PageSection section, RenderContext context) {
        log.debug("fillExcelCells: sectionId={} mappingType={} fieldMappings={} groups={}",
            section.getSectionId(), section.getMappingType(), section.getFieldMappings(), section.getFieldMappingGroups());
        // If multiple mapping groups are defined, process each group (gives access to mappingType and repeatingGroup)
        if (section.hasMultipleMappingGroups()) {
            for (FieldMappingGroup group : section.getFieldMappingGroups()) {
                FieldMappingStrategy strategy = findMappingStrategy(group.getMappingType());

                // Handle repeating groups as tables when configured
                if (group.getRepeatingGroup() != null) {
                    populateRepeatingGroupAsTable(workbook, group, context, strategy);
                    continue;
                }

                // Non-repeating group: iterate mappings
                for (Map.Entry<String, String> mapping : group.getFields().entrySet()) {
                    String key = mapping.getKey();
                    String expression = mapping.getValue();
                    try {
                        if (key.contains(":")) {
                            // Range mapping (e.g., Sheet1!A2:A6 or A2:A6)
                            boolean matchNames = Boolean.TRUE.equals(group.getMatchBenefitNamesInTemplate());
                            applyRangeMapping(workbook, key, strategy.evaluatePath(context.getData(), expression), Boolean.TRUE.equals(section.getOverwrite()), matchNames, context);
                        } else {
                            // Single cell mapping
                            String value = strategy.mapFromContext(context.getData(), Collections.singletonMap(key, expression)).getOrDefault(key, "");
                            setCellValue(workbook, key, value, context);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to apply mapping {} -> {}: {}", key, expression, e.getMessage());
                    }
                }
            }
            return;
        }

        // Single mapping block (legacy): section.getFieldMappings with mappingType
        FieldMappingStrategy strategy = findMappingStrategy(section.getMappingType());
        for (Map.Entry<String, String> mapping : section.getFieldMappings().entrySet()) {
            String key = mapping.getKey();
            String expression = mapping.getValue();
            log.debug("Processing mapping key='{}' expr='{}'", key, expression);
            try {
                if (key.contains(":")) {
                    boolean matchNames = Boolean.TRUE.equals(section.getMatchBenefitNamesInTemplate());
                    Object eval = strategy.evaluatePath(context.getData(), expression);
                    log.debug("Evaluated expression '{}' => {} (class={})", expression, eval, eval != null ? eval.getClass().getName() : "null");
                    applyRangeMapping(workbook, key, eval, Boolean.TRUE.equals(section.getOverwrite()), matchNames, context);
                } else {
                    String value = strategy.mapFromContext(context.getData(), Collections.singletonMap(key, expression)).getOrDefault(key, "");
                    log.debug("Single-cell value for key '{}' = '{}'", key, value);
                    setCellValue(workbook, key, value, context);
                }
            } catch (Exception e) {
                log.warn("Failed to apply mapping {} -> {}: {}", key, expression, e.getMessage());
            }
        }
    }

    /**
     * Apply a range mapping. The 'value' object may be a List of primitives (fill sequentially),
     * or a List of List for two-dimensional fills.
     * Respects the overwrite flag: if false, skips non-blank cells and formulas.
     * 
     * @param matchBenefitNamesInTemplate if true, use name-based row matching (template has benefit names);
     *                                     if false, auto-generate benefit names from incoming data (first column)
     */
    @SuppressWarnings("unchecked")
    // now accepts context so that sheet selection can honor the active index
    private void applyRangeMapping(Workbook workbook, String rangeKey, Object value, boolean overwrite, boolean matchBenefitNamesInTemplate, RenderContext context) {
        // Parse sheet and start/end cell
        String sheetName = null;
        String range = rangeKey;
        if (rangeKey.contains("!")) {
            String[] parts = rangeKey.split("!");
            sheetName = parts[0].trim();
            range = parts[1].trim();
        }

        String[] parts = range.split(":");
        if (parts.length != 2) {
            log.warn("Invalid range '{}', expected start:end", rangeKey);
            return;
        }

        String start = parts[0].trim();
        String end = parts[1].trim();

        Sheet sheet;
        if (sheetName != null) {
            sheet = workbook.getSheet(sheetName);
        } else {
            // if a specific sheet index was stored in context (due to cloning), use that
            Object idxObj = context.getMetadata("excelCurrentSheetIndex");
            if (idxObj instanceof Integer) {
                int idx = (Integer) idxObj;
                if (idx >= 0 && idx < workbook.getNumberOfSheets()) {
                    sheet = workbook.getSheetAt(idx);
                } else {
                    sheet = workbook.getSheetAt(0);
                }
            } else {
                sheet = workbook.getSheetAt(0);
            }
        }
        if (sheet == null) {
            log.warn("Sheet '{}' not found for range {}", sheetName, rangeKey);
            return;
        }

        CellReference startRef = new CellReference(start);
        CellReference endRef = new CellReference(end);
        int rows = endRef.getRow() - startRef.getRow() + 1;
        int cols = endRef.getCol() - startRef.getCol() + 1;

        if (value == null) return;

        // If value is a 2D array (List of List)
        if (value instanceof List && !((List<?>) value).isEmpty() && ((List<?>) value).get(0) instanceof List) {
            List<List<?>> grid = (List<List<?>>) value;

            if (matchBenefitNamesInTemplate) {
                // Mode: Match benefit names in template (template has prefilled benefit names in first column)
                applyRangeMappingWithNameMatching(sheet, startRef, grid, rows, cols, overwrite);
            } else {
                // Mode: Auto-generate benefit names (write incoming names to first column, values fill sequentially)
                applyRangeMappingWithAutoGeneratedNames(sheet, startRef, grid, rows, cols, overwrite);
            }
            return;
        }

        // If value is a List of primitives: fill row-major across the range
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            int idx = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (idx >= list.size()) return;
                    Object cellValue = list.get(idx++);
                    setCellValueAt(sheet, startRef.getRow() + r, startRef.getCol() + c, cellValue == null ? "" : cellValue.toString(), overwrite);
                }
            }
            return;
        }

        // Fallback: single value - set to start cell
        setCellValueAt(sheet, startRef.getRow(), startRef.getCol(), value.toString(), overwrite);
    }

    /**
     * Apply 2D range mapping with name-based matching (template has benefit names in first column).
     * Incoming grid rows are matched by benefit name and values are written to matching rows.
     */
    private void applyRangeMappingWithNameMatching(Sheet sheet, CellReference startRef, List<List<?>> grid, int rows, int cols, boolean overwrite) {
        // Detect template benefit names in first column
        boolean templateHasNames = false;
        Map<String, Integer> templateNameToRow = new HashMap<>();
        for (int r = 0; r < rows; r++) {
            Row existingRow = sheet.getRow(startRef.getRow() + r);
            if (existingRow == null) continue;
            Cell nameCell = existingRow.getCell(startRef.getCol());
            if (nameCell != null) {
                String txt = null;
                try {
                    if (nameCell.getCellType() == CellType.STRING) txt = nameCell.getStringCellValue();
                    else if (nameCell.getCellType() == CellType.NUMERIC) txt = Double.toString(nameCell.getNumericCellValue());
                    else if (nameCell.getCellType() == CellType.BOOLEAN) txt = Boolean.toString(nameCell.getBooleanCellValue());
                } catch (Exception ignored) {
                }
                if (txt != null && !txt.trim().isEmpty()) {
                    templateHasNames = true;
                    templateNameToRow.put(txt.trim().toLowerCase(), startRef.getRow() + r);
                }
            }
        }

        boolean incomingHasNames = false;
        if (!grid.isEmpty()) {
            List<?> firstRow = grid.get(0);
            if (firstRow != null && !firstRow.isEmpty() && firstRow.get(0) != null && firstRow.get(0) instanceof String) {
                incomingHasNames = true;
            }
        }

        boolean useNameMatching = templateHasNames && incomingHasNames;

        for (int r = 0; r < Math.min(grid.size(), rows); r++) {
            List<?> row = grid.get(r);
            int targetRow = startRef.getRow() + r; // default positional

            if (useNameMatching && row != null && !row.isEmpty() && row.get(0) != null) {
                String incomingName = row.get(0).toString().trim().toLowerCase();
                Integer matched = templateNameToRow.get(incomingName);
                if (matched != null) {
                    targetRow = matched;
                }
            }

            for (int c = 0; c < Math.min(row == null ? 0 : row.size(), cols); c++) {
                Object cellValue = row.get(c);

                // When using name-matching, avoid overwriting the template's name column unless it was blank.
                if (useNameMatching && c == 0) {
                    Row existing = sheet.getRow(targetRow);
                    Cell existingNameCell = existing == null ? null : existing.getCell(startRef.getCol());
                    boolean existingBlank = existingNameCell == null || existingNameCell.getCellType() == CellType.BLANK ||
                            (existingNameCell.getCellType() == CellType.STRING && existingNameCell.getStringCellValue().trim().isEmpty());
                    if (!existingBlank) {
                        // skip writing incoming name to preserve template ordering
                        continue;
                    }
                }

                setCellValueAt(sheet, targetRow, startRef.getCol() + c, cellValue == null ? "" : cellValue.toString(), overwrite);
            }
        }
    }

    /**
     * Apply 2D range mapping with auto-generated benefit names (write incoming names to first column).
     * Incoming rows are written sequentially, with benefit names from first column of each row.
     */
    private void applyRangeMappingWithAutoGeneratedNames(Sheet sheet, CellReference startRef, List<List<?>> grid, int rows, int cols, boolean overwrite) {
        for (int r = 0; r < Math.min(grid.size(), rows); r++) {
            List<?> row = grid.get(r);
            if (row == null || row.isEmpty()) continue;

            int targetRow = startRef.getRow() + r;

            // Write all columns including the benefit name in first column
            for (int c = 0; c < Math.min(row.size(), cols); c++) {
                Object cellValue = row.get(c);
                setCellValueAt(sheet, targetRow, startRef.getCol() + c, cellValue == null ? "" : cellValue.toString(), overwrite);
            }
        }
    }

    private void setCellValueAt(Sheet sheet, int rowIndex, int colIndex, String value, boolean overwrite) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) row = sheet.createRow(rowIndex);
        Cell cell = row.getCell(colIndex);

        // If this cell is part of a merged region but not the top-left cell,
        // do not write to it. Previously we redirected writes to the
        // top-left cell, but that causes later entries in a range fill to
        // overwrite earlier values in the leading cell. Tests expect
        // non-leading merged cells to be skipped instead.
        if (isPartOfMergedRegion(sheet, rowIndex, colIndex)) {
            CellRangeAddress merged = getMergedRegion(sheet, rowIndex, colIndex);
            if (merged != null && (merged.getFirstRow() != rowIndex || merged.getFirstColumn() != colIndex)) {
                return;
            }
        }

        // Preserve template formulas: do not overwrite cells that contain formulas
        if (cell != null && cell.getCellType() == CellType.FORMULA) {
            return;
        }

        // Skip overwriting non-blank cells if overwrite flag is false
        if (!overwrite && cell != null && cell.getCellType() != CellType.BLANK) {
            // If the cell contains an empty string (often created by templates),
            // treat it as blank so we can overwrite it. This preserves existing
            // non-empty template content while allowing intended replacements.
            if (cell.getCellType() == CellType.STRING) {
                String v = cell.getStringCellValue();
                if (v == null || v.trim().isEmpty()) {
                    // allow overwrite
                } else {
                    return;
                }
            } else {
                return;
            }
        }

        if (cell == null) cell = row.createCell(colIndex);

        try {
            double num = Double.parseDouble(value);
            cell.setCellValue(num);
        } catch (NumberFormatException e) {
            cell.setCellValue(value);
        }
    }

    /**
     * Helper to determine if a position falls inside any merged region.
     */
    private boolean isPartOfMergedRegion(Sheet sheet, int row, int col) {
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            if (range.isInRange(row, col)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the merged region containing the cell, or null if none.
     */
    private CellRangeAddress getMergedRegion(Sheet sheet, int row, int col) {
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            if (range.isInRange(row, col)) {
                return range;
            }
        }
        return null;
    }

    /**
     * Overload for backward compatibility: default to overwrite = true
     */
    private void setCellValueAt(Sheet sheet, int rowIndex, int colIndex, String value) {
        setCellValueAt(sheet, rowIndex, colIndex, value, true);
    }
    
    /**
     * Set a cell value by reference
     * Supports formats: "A1", "Sheet1!A1", "Sheet1.A1"
     */
    /**
     * Set a single cell value, respecting the current sheet index stored in the
     * context when no sheet name is provided. The legacy overload delegates here
     * with a null context (no indexing).
     */
    private void setCellValue(Workbook workbook, String cellRef, String value, RenderContext context) {
        // Parse cell reference
        String sheetName = null;
        String cellAddress = cellRef;

        // Handle sheet-qualified references like "Sheet1!A1" or "Sheet1.A1"
        if (cellRef.contains("!")) {
            String[] parts = cellRef.split("!");
            sheetName = parts[0].trim();
            cellAddress = parts[1].trim();
        } else if (cellRef.contains(".")) {
            String[] parts = cellRef.split("\\.");
            sheetName = parts[0].trim();
            cellAddress = parts[1].trim();
        }

        // Get sheet (default to first sheet if not specified)
        Sheet sheet;
        if (sheetName != null) {
            sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                log.warn("Sheet '{}' not found in workbook", sheetName);
                return;
            }
        } else {
            if (context != null) {
                Object idxObj = context.getMetadata("excelCurrentSheetIndex");
                if (idxObj instanceof Integer) {
                    int idx = (Integer) idxObj;
                    if (idx >= 0 && idx < workbook.getNumberOfSheets()) {
                        sheet = workbook.getSheetAt(idx);
                    } else {
                        sheet = workbook.getSheetAt(0);
                    }
                } else {
                    sheet = workbook.getSheetAt(0);
                }
            } else {
                sheet = workbook.getSheetAt(0);
            }
        }

        // Parse cell address
        try {
            CellReference cellReference = new CellReference(cellAddress);
            Row row = sheet.getRow(cellReference.getRow());
            if (row == null) {
                row = sheet.createRow(cellReference.getRow());
            }

            Cell cell = row.getCell(cellReference.getCol());
            if (cell == null) {
                cell = row.createCell(cellReference.getCol());
            }

            // Set value (try to parse as number if possible)
            try {
                double numValue = Double.parseDouble(value);
                cell.setCellValue(numValue);
            } catch (NumberFormatException e) {
                // Value is not a number, set as string
                cell.setCellValue(value);
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid cell reference '{}': {}", cellRef, e.getMessage());
        }
    }

    // backward-compatible overload
    private void setCellValue(Workbook workbook, String cellRef, String value) {
        setCellValue(workbook, cellRef, value, null);
    }
}
