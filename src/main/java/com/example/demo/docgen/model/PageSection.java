package com.example.demo.docgen.model;

import com.example.demo.docgen.mapper.MappingType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a single section within a document template
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageSection {
    /**
     * Unique identifier for this section
     */
    private String sectionId;
    
    /**
     * Type of renderer to use for this section
     */
    private SectionType type;
    
    /**
     * Path to the template file (PDF form, FreeMarker template, etc.)
     */
    private String templatePath;
    
    /**
     * Field mapping strategy type (DIRECT, JSONPATH, or JSONATA)
     * Defaults to JSONPATH for backward compatibility
     * Note: If fieldMappingGroups is specified, this field is ignored
     */
    @Builder.Default
    private MappingType mappingType = MappingType.JSONPATH;
    
    /**
     * Field mappings from form fields to data paths/expressions
     * The interpretation depends on mappingType:
     * - DIRECT: Simple dot notation paths (e.g., "address.street")
     * - JSONPATH: JSONPath expressions (e.g., "$.address.street")
     * - JSONATA: JSONata expressions (e.g., "firstName & ' ' & lastName")
     * 
     * Note: If fieldMappingGroups is specified, this field is ignored
     */
    @Builder.Default
    private Map<String, String> fieldMappings = new HashMap<>();
    
    /**
     * Multiple field mapping groups with different strategies.
     * Allows mixing DIRECT, JSONPATH, and JSONATA within a single section.
     * If specified, this takes precedence over mappingType and fieldMappings.
     * 
     * Example:
     * - Group 1: DIRECT for simple fields (fast)
     * - Group 2: JSONATA for calculations (powerful)
     */
    private List<FieldMappingGroup> fieldMappingGroups;
    
    /**
     * Order of this section in the final document
     */
    private int order;

    /**
     * ViewModel type for pre-processing data before rendering (primarily for FreeMarker)
     */
    private String viewModelType;

    /**
     * Optional condition to determine if this section should be rendered.
     * Evaluated using JSONPath or JSONata based on the mappingType.
     * If null or empty, the section is always rendered.
     */
    private String condition;
    
    /**
     * Overflow configurations for repeating data
     */
    @Builder.Default
    private List<OverflowConfig> overflowConfigs = new ArrayList<>();
    
    /**
     * Control whether range mappings should overwrite existing non-blank cells.
     * - true (default): overwrite all cells in the range
     * - false: preserve existing non-blank cells and formulas
     * Note: Formulas are always preserved regardless of this setting
     */
    @Builder.Default
    private Boolean overwrite = true;
    
    /**
     * When rendering 2D range mappings (e.g., benefit comparison matrices) in non-grouped mode:
     * - true: Expected template has benefit names in first column; use name-based row matching
     * - false (default): Auto-generate benefit names in first column from data; use positional filling
     * 
     * Only applies when fieldMappingGroups is not specified (single mapping mode).
     * When fieldMappingGroups is specified, use the flag in FieldMappingGroup instead.
     */
    @Builder.Default
    private Boolean matchBenefitNamesInTemplate = false;
    
    /**
     * Check if this section uses multiple mapping groups
     */
    public boolean hasMultipleMappingGroups() {
        return fieldMappingGroups != null && !fieldMappingGroups.isEmpty();
    }
}
