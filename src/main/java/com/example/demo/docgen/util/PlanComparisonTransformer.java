package com.example.demo.docgen.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility to transform nested plan/benefits data into a 2D matrix for Excel comparison tables.
 * 
 * Input: plans with benefits in different orders
 * Output: 2D matrix with benefits in rows, plans in columns (with 1-column spacing)
 */
public class PlanComparisonTransformer {

    /**
     * Transform plans data with benefits into a 2D comparison matrix.
     * 
     * Example input:
     * {
     *   "plans": [
     *     { "planName": "Basic", "benefits": [ { "name": "Doctor Visits", "value": "$20 copay" }, ... ] },
     *     { "planName": "Premium", "benefits": [ { "name": "Doctor Visits", "value": "Covered 100%" }, ... ] }
     *   ]
     * }
     * 
     * Example output matrix:
     * [
     *   ["Benefit", "", "Basic", "", "Premium"],
     *   ["Doctor Visits", "", "$20 copay", "", "Covered 100%"],
     *   ["Prescriptions", "", "$10 copay", "", "$5 copay"]
     * ]
     * 
     * @param plans List of plan objects with planName and benefits array
     * @param benefitNameField Field name for benefit name (default: "name")
     * @param benefitValueField Field name for benefit value (default: "value")
     * @param columnSpacingWidth Number of empty columns between plan columns (default: 1)
     * @param rowSpacingHeight Number of empty rows to insert after each benefit row (default: 0)
     * @return 2D array (List<List<String>>) suitable for 2D array Excel mapping
     */
    public static List<List<Object>> transformPlansToMatrix(
            List<Map<String, Object>> plans,
            String benefitNameField,
            String benefitValueField,
            int columnSpacingWidth,
            int rowSpacingHeight) {

        if (plans == null || plans.isEmpty()) {
            return new ArrayList<>();
        }

        // Step 1: Collect all unique benefit names (normalized) and their order
        Map<String, String> benefitNameMap = new LinkedHashMap<>(); // normalized -> display name
        for (Map<String, Object> plan : plans) {
            List<Map<String, Object>> benefits = (List<Map<String, Object>>) plan.get("benefits");
            if (benefits != null) {
                for (Map<String, Object> benefit : benefits) {
                    String name = (String) benefit.get(benefitNameField);
                    if (name != null) {
                        String normalized = name.toLowerCase().trim();
                        benefitNameMap.putIfAbsent(normalized, name); // Use first occurrence
                    }
                }
            }
        }

        List<String> benefitNames = new ArrayList<>(benefitNameMap.values());

        // Step 2: Create plan name list
        List<String> planNames = plans.stream()
                .map(p -> (String) p.get("planName"))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // Step 3: Build the benefit-to-value map for each plan
        Map<String, Map<String, Object>> planBenefitMap = new HashMap<>();
        for (Map<String, Object> plan : plans) {
            String planName = (String) plan.get("planName");
            Map<String, Object> benefitMap = new HashMap<>();

            List<Map<String, Object>> benefits = (List<Map<String, Object>>) plan.get("benefits");
            if (benefits != null) {
                for (Map<String, Object> benefit : benefits) {
                    String name = (String) benefit.get(benefitNameField);
                    Object value = benefit.get(benefitValueField);
                    if (name != null) {
                        String normalized = name.toLowerCase().trim();
                        benefitMap.put(normalized, value);
                    }
                }
            }

            planBenefitMap.put(planName, benefitMap);
        }

        // Step 4: Build the 2D matrix
        List<List<Object>> matrix = new ArrayList<>();

        // Calculate total columns for spanning row spacers
        int totalColumns = 1 + (planNames.size() * (1 + columnSpacingWidth));
        
        // Header row: ["Benefit", "", "Plan1", "", "Plan2", "", ...]
        List<Object> headerRow = new ArrayList<>();
        headerRow.add("Benefit");
        for (int i = 0; i < planNames.size(); i++) {
            // Add spacing column(s)
            for (int s = 0; s < columnSpacingWidth; s++) {
                headerRow.add("");
            }
            // Add plan name
            headerRow.add(planNames.get(i));
        }
        matrix.add(headerRow);

        // Data rows
        for (String displayBenefitName : benefitNames) {
            String normalizedBenefitName = displayBenefitName.toLowerCase().trim();
            List<Object> row = new ArrayList<>();
            row.add(displayBenefitName);

            for (String planName : planNames) {
                // Add spacing column(s)
                for (int s = 0; s < columnSpacingWidth; s++) {
                    row.add("");
                }
                // Add benefit value for this plan
                Map<String, Object> benefitMap = planBenefitMap.get(planName);
                Object value = benefitMap != null ? benefitMap.get(normalizedBenefitName) : "";
                row.add(value != null ? value : "");
            }

            matrix.add(row);
            
            // Add row spacing after benefit (if configured)
            if (rowSpacingHeight > 0) {
                for (int s = 0; s < rowSpacingHeight; s++) {
                    List<Object> spacerRow = new ArrayList<>();
                    for (int c = 0; c < totalColumns; c++) {
                        spacerRow.add("");
                    }
                    matrix.add(spacerRow);
                }
            }
        }

        return matrix;
    }

    /**
     * Convenience method with defaults: benefitNameField="name", benefitValueField="value", columnSpacingWidth=1, rowSpacingHeight=0
     */
    public static List<List<Object>> transformPlansToMatrix(List<Map<String, Object>> plans) {
        return transformPlansToMatrix(plans, "name", "value", 1, 0);
    }
    
    /**
     * Convenience method with custom spacing. rowSpacingHeight defaults to 0.
     */
    public static List<List<Object>> transformPlansToMatrix(
            List<Map<String, Object>> plans,
            String benefitNameField,
            String benefitValueField,
            int columnSpacingWidth) {
        return transformPlansToMatrix(plans, benefitNameField, benefitValueField, columnSpacingWidth, 0);
    }

    /**
     * Transform plans data and inject into a JSON object for template rendering.
     * 
     * @param data Original data object (Map)
     * @param plans List of plan objects
     * @return Updated data with "comparisonMatrix" field added
     */
    public static Map<String, Object> injectComparisonMatrix(
            Map<String, Object> data,
            List<Map<String, Object>> plans) {
        
        Map<String, Object> result = new HashMap<>(data);
        List<List<Object>> matrix = transformPlansToMatrix(plans);
        result.put("comparisonMatrix", matrix);
        return result;
    }

    /**
     * Convenience method to inject with custom field names.
     */
    public static Map<String, Object> injectComparisonMatrix(
            Map<String, Object> data,
            List<Map<String, Object>> plans,
            String benefitNameField,
            String benefitValueField,
            int columnSpacingWidth) {
        
        Map<String, Object> result = new HashMap<>(data);
        List<List<Object>> matrix = transformPlansToMatrix(plans, benefitNameField, benefitValueField, columnSpacingWidth, 0);
        result.put("comparisonMatrix", matrix);
        return result;
    }
    
    /**
     * Inject comparison matrix with custom spacing (both column and row).
     */
    public static Map<String, Object> injectComparisonMatrix(
            Map<String, Object> data,
            List<Map<String, Object>> plans,
            String benefitNameField,
            String benefitValueField,
            int columnSpacingWidth,
            int rowSpacingHeight) {
        
        Map<String, Object> result = new HashMap<>(data);
        List<List<Object>> matrix = transformPlansToMatrix(plans, benefitNameField, benefitValueField, columnSpacingWidth, rowSpacingHeight);
        result.put("comparisonMatrix", matrix);
        return result;
    }

    /**
     * Generate a matrix that excludes the benefit column itself. Useful when the
     * template already contains the benefit names in column A and you only want
     * to fill plan values (columns to the right).
     * 
     * The returned matrix has the same number of rows, but each row begins with
     * spacing columns (if any) followed by plan values; the initial "Benefit"
     * header cell is omitted as is the first column of each data row.
     */
    /**
     * Convenience variant of {@link #transformPlansToMatrixValuesOnly(List,String,String,int)}
     * that uses the default field names ("name"/"value") and spacing of 1.
     */
    public static List<List<Object>> transformPlansToMatrixValuesOnly(
            List<Map<String, Object>> plans) {
        return transformPlansToMatrixValuesOnly(plans, "name", "value", 1);
    }

    /**
     * Convenience variant with row spacing support.
     */
    public static List<List<Object>> transformPlansToMatrixValuesOnly(
            List<Map<String, Object>> plans,
            String benefitNameField,
            String benefitValueField,
            int columnSpacingWidth,
            int rowSpacingHeight) {
        List<List<Object>> full = transformPlansToMatrix(plans, benefitNameField, benefitValueField, columnSpacingWidth, rowSpacingHeight);
        if (full.isEmpty()) {
            return full;
        }
        List<List<Object>> stripped = new ArrayList<>();
        for (List<Object> row : full) {
            if (row.size() <= 1) {
                stripped.add(new ArrayList<>());
            } else {
                // drop the first element (benefit or header label)
                stripped.add(new ArrayList<>(row.subList(1, row.size())));
            }
        }
        return stripped;
    }
    
    /**
     * Convenience variant without row spacing (defaults to 0).
     */
    public static List<List<Object>> transformPlansToMatrixValuesOnly(
            List<Map<String, Object>> plans,
            String benefitNameField,
            String benefitValueField,
            int columnSpacingWidth) {
        return transformPlansToMatrixValuesOnly(plans, benefitNameField, benefitValueField, columnSpacingWidth, 0);
    }

    /**
     * Convenience helper which injects value-only matrix into data under
     * `comparisonMatrixValues` key.  Users can then map a range excluding the
     * benefit column (e.g. "B1:G6").
     */
    /**
     * Convenience variant that uses default field names and spacing.
     */
    public static Map<String, Object> injectComparisonMatrixValuesOnly(
            Map<String, Object> data,
            List<Map<String, Object>> plans) {
        return injectComparisonMatrixValuesOnly(data, plans, "name", "value", 1);
    }

    /**
     * Inject values-only matrix with custom spacing.
     */
    public static Map<String, Object> injectComparisonMatrixValuesOnly(
            Map<String, Object> data,
            List<Map<String, Object>> plans,
            String benefitNameField,
            String benefitValueField,
            int columnSpacingWidth) {
        Map<String, Object> result = new HashMap<>(data);
        List<List<Object>> matrix = transformPlansToMatrixValuesOnly(plans, benefitNameField, benefitValueField, columnSpacingWidth, 0);
        result.put("comparisonMatrixValues", matrix);
        return result;
    }
    
    /**
     * Inject values-only matrix with both column and row spacing.
     */
    public static Map<String, Object> injectComparisonMatrixValuesOnly(
            Map<String, Object> data,
            List<Map<String, Object>> plans,
            String benefitNameField,
            String benefitValueField,
            int columnSpacingWidth,
            int rowSpacingHeight) {
        Map<String, Object> result = new HashMap<>(data);
        List<List<Object>> matrix = transformPlansToMatrixValuesOnly(plans, benefitNameField, benefitValueField, columnSpacingWidth, rowSpacingHeight);
        result.put("comparisonMatrixValues", matrix);
        return result;
    }

    /**
     * Transform plans with age-rating data into a 3-band age rating matrix.
     * 
     * Expected plan structure:
     * {
     *   "planName": "Silver",
     *   "network": "Nat",
     *   "contractCode": "S001",
     *   "ageRatings": [ { "age": 0, "rating": 100 }, { "age": 1, "rating": 110 }, ... ]
     * }
     * 
     * Output matrix has 3 horizontal bands with age/rating columns for each:
     * - Band 1: ages 0-30
     * - Band 2: ages 31-47
     * - Band 3: ages 48-64+
     * 
     * For N plans with columnSpacing=1, each band produces 3 columns (age, rating, space) per plan.
     * 
     * @param plans List of plans with ageRatings field
     * @param ageField Field name for age value (default: "age")
     * @param ratingField Field name for rating value (default: "rating")
     * @param columnSpacingWidth Number of empty columns between bands (default: 1)
     * @return 2D matrix (List<List<Object>>) with 3 bands horizontally arranged
     */
    public static List<List<Object>> transformAgeRatingsToMatrix(
            List<Map<String, Object>> plans,
            String ageField,
            String ratingField,
            int columnSpacingWidth) {

        if (plans == null || plans.isEmpty()) {
            return new ArrayList<>();
        }

        // Age bands: [0-30], [31-47], [48-64+]
        // Find max age to detect band sizes dynamically
        List<Map<String, Object>> allAges = new ArrayList<>();
        for (Map<String, Object> plan : plans) {
            List<Map<String, Object>> ageRatings = (List<Map<String, Object>>) plan.get("ageRatings");
            if (ageRatings != null) {
                allAges.addAll(ageRatings);
            }
        }

        // Group ages into bands
        List<Integer> band1Ages = new ArrayList<>();
        List<Integer> band2Ages = new ArrayList<>();
        List<Integer> band3Ages = new ArrayList<>();

        for (Map<String, Object> item : allAges) {
            Object ageObj = item.get(ageField);
            if (ageObj instanceof Number) {
                int age = ((Number) ageObj).intValue();
                if (age <= 30) {
                    band1Ages.add(age);
                } else if (age <= 47) {
                    band2Ages.add(age);
                } else {
                    band3Ages.add(age);
                }
            }
        }

        // Sort and deduplicate
        band1Ages = new ArrayList<>(new TreeSet<>(band1Ages));
        band2Ages = new ArrayList<>(new TreeSet<>(band2Ages));
        band3Ages = new ArrayList<>(new TreeSet<>(band3Ages));

        // Extract plan names and ageRating maps
        List<String> planNames = plans.stream()
                .map(p -> (String) p.get("planName"))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<String> planCodes = plans.stream()
                .map(p -> {
                    String network = (String) p.get("network");
                    String code = (String) p.get("contractCode");
                    return (network != null ? network : "") + " / " + (code != null ? code : "");
                })
                .collect(Collectors.toList());

        // Build age-to-rating maps for each plan
        Map<String, Map<Integer, Object>> planAgeRatingMap = new HashMap<>();
        for (Map<String, Object> plan : plans) {
            String planName = (String) plan.get("planName");
            Map<Integer, Object> ageRatingMap = new HashMap<>();

            List<Map<String, Object>> ageRatings = (List<Map<String, Object>>) plan.get("ageRatings");
            if (ageRatings != null) {
                for (Map<String, Object> item : ageRatings) {
                    Object ageObj = item.get(ageField);
                    Object ratingObj = item.get(ratingField);
                    if (ageObj instanceof Number) {
                        int age = ((Number) ageObj).intValue();
                        ageRatingMap.put(age, ratingObj);
                    }
                }
            }

            planAgeRatingMap.put(planName, ageRatingMap);
        }

        // Build 3-row header structure
        List<List<Object>> matrix = new ArrayList<>();

        // Row 0: Plan names (repeated for each band)
        List<Object> planNameRow = new ArrayList<>();
        for (String planName : planNames) {
            planNameRow.add(planName);
            for (int b = 0; b < (bandStartColumns().length - 1); b++) {
                planNameRow.add("");
            }
        }
        matrix.add(planNameRow);

        // Row 1: Plan codes (network / contractCode)
        List<Object> planCodeRow = new ArrayList<>();
        for (String planCode : planCodes) {
            planCodeRow.add(planCode);
            for (int b = 0; b < (bandStartColumns().length - 1); b++) {
                planCodeRow.add("");
            }
        }
        matrix.add(planCodeRow);

        // Row 2: Band headers (Age/Rating labels)
        List<Object> bandHeaderRow = new ArrayList<>();
        int[] bandStarts = bandStartColumns();
        for (int bandIdx = 0; bandIdx < 3; bandIdx++) {
            List<Integer> bandAges = (bandIdx == 0) ? band1Ages : (bandIdx == 1) ? band2Ages : band3Ages;
            for (int planIdx = 0; planIdx < planNames.size(); planIdx++) {
                if (planIdx == 0 && bandIdx == 0) {
                    // First band, first plan: use full placement
                    bandHeaderRow.add("Age");
                    bandHeaderRow.add("Rating");
                    if (columnSpacingWidth > 0) {
                        for (int s = 0; s < columnSpacingWidth; s++) {
                            bandHeaderRow.add("");
                        }
                    }
                } else if (planIdx == 0) {
                    // Subsequent bands, first plan: add age header
                    bandHeaderRow.add("Age");
                    bandHeaderRow.add("Rating");
                    if (columnSpacingWidth > 0) {
                        for (int s = 0; s < columnSpacingWidth; s++) {
                            bandHeaderRow.add("");
                        }
                    }
                } else {
                    // Subsequent plans in same band: duplicate pattern
                    bandHeaderRow.add("Age");
                    bandHeaderRow.add("Rating");
                    if (columnSpacingWidth > 0) {
                        for (int s = 0; s < columnSpacingWidth; s++) {
                            bandHeaderRow.add("");
                        }
                    }
                }
            }
        }
        // Adjust header row to actual band structure
        bandHeaderRow.clear();
        for (int bandIdx = 0; bandIdx < 3; bandIdx++) {
            List<Integer> bandAges = (bandIdx == 0) ? band1Ages : (bandIdx == 1) ? band2Ages : band3Ages;
            for (int planIdx = 0; planIdx < planNames.size(); planIdx++) {
                bandHeaderRow.add("Age");
                bandHeaderRow.add("Rating");
                if (columnSpacingWidth > 0) {
                    for (int s = 0; s < columnSpacingWidth; s++) {
                        bandHeaderRow.add("");
                    }
                }
            }
        }
        matrix.add(bandHeaderRow);

        // Data rows: one row = one age across all bands for all plans
        int maxAgeCount = Math.max(band1Ages.size(), Math.max(band2Ages.size(), band3Ages.size()));
        for (int ageIdx = 0; ageIdx < maxAgeCount; ageIdx++) {
            List<Object> dataRow = new ArrayList<>();

            // Band 1 (ages 0-30)
            for (int planIdx = 0; planIdx < planNames.size(); planIdx++) {
                int age = ageIdx < band1Ages.size() ? band1Ages.get(ageIdx) : -1;
                String planName = planNames.get(planIdx);
                Object rating = (age >= 0 && planAgeRatingMap.get(planName) != null) ?
                        planAgeRatingMap.get(planName).getOrDefault(age, "") : "";

                dataRow.add(age >= 0 ? age : "");
                dataRow.add(rating);
                if (columnSpacingWidth > 0) {
                    for (int s = 0; s < columnSpacingWidth; s++) {
                        dataRow.add("");
                    }
                }
            }

            // Band 2 (ages 31-47)
            for (int planIdx = 0; planIdx < planNames.size(); planIdx++) {
                int age = ageIdx < band2Ages.size() ? band2Ages.get(ageIdx) : -1;
                String planName = planNames.get(planIdx);
                Object rating = (age >= 0 && planAgeRatingMap.get(planName) != null) ?
                        planAgeRatingMap.get(planName).getOrDefault(age, "") : "";

                dataRow.add(age >= 0 ? age : "");
                dataRow.add(rating);
                if (columnSpacingWidth > 0) {
                    for (int s = 0; s < columnSpacingWidth; s++) {
                        dataRow.add("");
                    }
                }
            }

            // Band 3 (ages 48-64+)
            for (int planIdx = 0; planIdx < planNames.size(); planIdx++) {
                int age = ageIdx < band3Ages.size() ? band3Ages.get(ageIdx) : -1;
                String planName = planNames.get(planIdx);
                Object rating = (age >= 0 && planAgeRatingMap.get(planName) != null) ?
                        planAgeRatingMap.get(planName).getOrDefault(age, "") : "";

                dataRow.add(age >= 0 ? age : "");
                dataRow.add(rating);
                if (columnSpacingWidth > 0) {
                    for (int s = 0; s < columnSpacingWidth; s++) {
                        dataRow.add("");
                    }
                }
            }

            matrix.add(dataRow);
        }

        return matrix;
    }

    private static int[] bandStartColumns() {
        return new int[]{0, 0, 0}; // Placeholder
    }

    /**
     * Convenience method with defaults for age rating transformation.
     */
    public static List<List<Object>> transformAgeRatingsToMatrix(List<Map<String, Object>> plans) {
        return transformAgeRatingsToMatrix(plans, "age", "rating", 1);
    }

    /**
     * Inject age-rating matrix into data under "comparisonMatrix" key.
     */
    public static Map<String, Object> injectAgeRatings(
            Map<String, Object> data,
            List<Map<String, Object>> plans,
            String ageField,
            String ratingField,
            int columnSpacingWidth) {
        Map<String, Object> result = new HashMap<>(data);
        List<List<Object>> matrix = transformAgeRatingsToMatrix(plans, ageField, ratingField, columnSpacingWidth);
        result.put("comparisonMatrix", matrix);
        return result;
    }

    /**
     * Convenience variant with defaults.
     */
    public static Map<String, Object> injectAgeRatings(
            Map<String, Object> data,
            List<Map<String, Object>> plans) {
        return injectAgeRatings(data, plans, "age", "rating", 1);
    }
}

