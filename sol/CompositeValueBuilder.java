package com.insurance.excel.resolver;

import com.insurance.excel.model.TemplateMappingConfig.CompositeSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles a composite string value from multiple JSON fields and literal parts.
 *
 * Parts syntax in YAML:
 *   "$fieldName"      resolved from the JSON item using dot-notation
 *   "literal text"    written verbatim
 *
 * Examples:
 *   parts: ["$ageFrom", " - ", "$ageTo"]                → "18 - 29"
 *   parts: ["$planType", " (", "$carrier", ")"]          → "PPO (BlueCross)"
 *   parts: ["In-Network: ", "$inNetwork", " | OON: ", "$outOfNetwork"] → "In-Network: $20 | OON: 40%"
 *   parts: ["$deductible.individual", " / ", "$deductible.family"]     → "500 / 1000"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompositeValueBuilder {

    private final JsonValueResolver jsonResolver;

    /**
     * Build a composite string value from the spec and the JSON item.
     *
     * @param spec  the CompositeSpec from YAML
     * @param item  the current array item (Map, JsonNode, etc.)
     * @return assembled string, or null if all dynamic parts resolved to null
     */
    public String build(CompositeSpec spec, Object item) {
        if (spec == null || spec.getParts() == null || spec.getParts().isEmpty()) {
            return null;
        }

        List<String> resolvedParts = new ArrayList<>();
        boolean hasAnyValue = false;

        for (String part : spec.getParts()) {
            if (part.startsWith("$")) {
                // Dynamic part: resolve from JSON item
                String fieldPath = part.substring(1); // strip the "$"
                Object value = jsonResolver.resolveField(item, fieldPath);

                if (value == null) {
                    resolvedParts.add(""); // preserve position so separator logic works
                } else {
                    resolvedParts.add(formatValue(value, spec.getNumericFormat()));
                    hasAnyValue = true;
                }
            } else {
                // Literal part
                resolvedParts.add(part);
                // Literals don't count toward "has a value" — only dynamic fields do
            }
        }

        if (!hasAnyValue) return null;

        // Join parts: if separator is empty, concatenate directly
        if (spec.getSeparator() == null || spec.getSeparator().isEmpty()) {
            return String.join("", resolvedParts);
        } else {
            // Insert separator only between non-empty adjacent resolved ($) parts
            return joinWithSeparator(spec.getParts(), resolvedParts, spec.getSeparator());
        }
    }

    /**
     * Insert separator only between non-empty dynamic parts (not between literals).
     */
    private String joinWithSeparator(List<String> originalParts,
                                     List<String> resolvedParts,
                                     String separator) {
        StringBuilder sb = new StringBuilder();
        boolean lastWasNonEmptyDynamic = false;

        for (int i = 0; i < originalParts.size(); i++) {
            boolean isDynamic = originalParts.get(i).startsWith("$");
            String resolved = resolvedParts.get(i);

            if (isDynamic && !resolved.isEmpty()) {
                if (lastWasNonEmptyDynamic) sb.append(separator);
                sb.append(resolved);
                lastWasNonEmptyDynamic = true;
            } else if (!isDynamic) {
                sb.append(resolved); // literals always appended as-is
            }
            // empty dynamic parts: skip (don't append separator)
        }

        return sb.toString();
    }

    private String formatValue(Object value, String numericFormat) {
        if (value instanceof Number num) {
            if (numericFormat != null && !numericFormat.isEmpty()) {
                try {
                    return new DecimalFormat(numericFormat).format(num.doubleValue());
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid numericFormat '{}': {}", numericFormat, e.getMessage());
                }
            }
            // Default: strip trailing .0 for whole numbers
            double d = num.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return value.toString();
    }
}