package com.example.demo.docgen.model;

import com.example.demo.docgen.mapper.MappingType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a group of field mappings using a specific mapping strategy.
 * Allows multiple mapping strategies within a single section.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldMappingGroup {
    /**
     * The mapping strategy to use for this group of fields
     */
    private MappingType mappingType;
    
    /**
     * Optional base path to evaluate ONCE and use as context for all field mappings.
     * This optimizes performance when multiple fields need to access the same filtered/nested object.
     * 
     * Example:
     * - basePath: "applicants[type='PRIMARY']"
     * - fields.firstName: "demographic.firstName" (relative to basePath result)
     * 
     * Without basePath, you would write:
     * - fields.firstName: "applicants[type='PRIMARY'].demographic.firstName" (filter runs every time)
     */
    private String basePath;
    
    /**
     * Field mappings for this group
     * Key: PDF form field name
     * Value: Source data path/expression (interpretation depends on mappingType)
     * 
     * If basePath is specified:
     * - Paths are relative to the basePath result
     * - basePath is evaluated ONCE, then all field paths are applied to that result
     * 
     * If basePath is null:
     * - Paths are absolute from the root data object
     */
    @Builder.Default
    private Map<String, String> fields = new HashMap<>();

    /**
     * Optional configuration for repeating a set of fields for each item in a collection.
     * Requires basePath to be specified and to evaluate to a collection.
     */
    private RepeatingGroupConfig repeatingGroup;
    
    /**
     * Optional styling configuration for individual fields
     * Key: PDF form field name
     * Value: FieldStyling containing font size, colors, alignment, etc.
     * 
     * Example:
     * fieldStyles:
     *   requiredField1:
     *     bold: true
     *     fontSize: 12
     *     textColor: 0xFF0000  # Red
     *   readOnlyField:
     *     readOnly: true
     *     backgroundColor: 0xCCCCCC  # Light gray
     */
    @Builder.Default
    private Map<String, FieldStyling> fieldStyles = new HashMap<>();
    
    /**
     * Optional default styling to apply to all fields in this group
     * Can be overridden by individual field styles in fieldStyles map
     */
    private FieldStyling defaultStyle;
    
    /**
     * When rendering 2D range mappings (e.g., benefit comparison matrices):
     * - true: Expected template has benefit names in first column; use name-based row matching
     * - false (default): Auto-generate benefit names in first column from data; use positional filling
     * 
     * Only applies to 2D list mappings in Excel sections.
     * When true, the first cell of each incoming row is treated as a benefit name
     * and is matched against existing names in the template's first column.
     * When false, incoming names are written to the first column and data is filled sequentially.
     */
    @Builder.Default
    private Boolean matchBenefitNamesInTemplate = false;
}
