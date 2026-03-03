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
                List<Object> sub = new ArrayList<>(row.subList(1, row.size()));
                // If the first column after dropping the benefit is an empty
                // spacing column (common when columnSpacingWidth > 0), drop
                // that as well so values-only matrices align to the first
                // mapped column (e.g. B1).
                if (!sub.isEmpty()) {
                    Object first = sub.get(0);
                    if (first == null || "".equals(first.toString())) {
                        if (sub.size() > 1) {
                            sub = new ArrayList<>(sub.subList(1, sub.size()));
                        } else {
                            sub = new ArrayList<>();
                        }
                    }
                }
                stripped.add(sub);
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
        // Debug: print matrix for tests to help diagnose alignment issues
        System.out.println("[DEBUG transformer values-only matrix] " + matrix);
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
        // Handle both string and numeric ages
        List<String> band1Ages = new ArrayList<>();  // 0-14, 15, 16, ..., 30
        List<String> band2Ages = new ArrayList<>();  // 31, 32, ..., 47
        List<String> band3Ages = new ArrayList<>();  // 48, 49, ..., 64+

        // Collect all age entries and categorize into bands
        for (Map<String, Object> plan : plans) {
            List<Map<String, Object>> ageRatings = (List<Map<String, Object>>) plan.get("ageRatings");
            if (ageRatings != null) {
                for (Map<String, Object> item : ageRatings) {
                    Object ageObj = item.get(ageField);
                    if (ageObj != null) {
                        String ageStr = ageObj.toString();
                        int ageBand = getAgeBand(ageStr);
                        if (ageBand == 1) {
                            if (!band1Ages.contains(ageStr)) band1Ages.add(ageStr);
                        } else if (ageBand == 2) {
                            if (!band2Ages.contains(ageStr)) band2Ages.add(ageStr);
                        } else if (ageBand == 3) {
                            if (!band3Ages.contains(ageStr)) band3Ages.add(ageStr);
                        }
                    }
                }
            }
        }

        // Sort each band's ages in order
        sortAgeBand(band1Ages);
        sortAgeBand(band2Ages);
        sortAgeBand(band3Ages);

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

        // Build age-to-rating maps for each plan (keyed by string age)
        Map<String, Map<String, Object>> planAgeRatingMap = new HashMap<>();
        for (Map<String, Object> plan : plans) {
            String planName = (String) plan.get("planName");
            Map<String, Object> ageRatingMap = new HashMap<>();

            List<Map<String, Object>> ageRatings = (List<Map<String, Object>>) plan.get("ageRatings");
            if (ageRatings != null) {
                for (Map<String, Object> item : ageRatings) {
                    Object ageObj = item.get(ageField);
                    Object ratingObj = item.get(ratingField);
                    if (ageObj != null) {
                        String ageStr = ageObj.toString();
                        ageRatingMap.put(ageStr, ratingObj);
                    }
                }
            }

            planAgeRatingMap.put(planName, ageRatingMap);
        }

        // Build matrix with structure:
        // Row 1: Plan names (one per band set, with spacing)
        // Row 2: Plan codes (one per band set, with spacing)
        // Row 3: Band headers (Age/Rating labels)
        // Rows 4+: Data rows with ages and ratings
        
        List<List<Object>> matrix = new ArrayList<>();

        // Row 0: Plan names - repeated for each band
        List<Object> planNameRow = new ArrayList<>();
        for (String planName : planNames) {
            // Band 1, 2, 3 - show plan name in first column of each band
            planNameRow.add(planName);
            planNameRow.add("");  // Rating column header
            if (columnSpacingWidth > 0) {
                for (int s = 0; s < columnSpacingWidth; s++) {
                    planNameRow.add("");
                }
            }
            planNameRow.add("");   // Band 2 Age
            planNameRow.add("");   // Band 2 Rating  
            if (columnSpacingWidth > 0) {
                for (int s = 0; s < columnSpacingWidth; s++) {
                    planNameRow.add("");
                }
            }
            planNameRow.add("");   // Band 3 Age
            planNameRow.add("");   // Band 3 Rating
            if (columnSpacingWidth > 0) {
                for (int s = 0; s < columnSpacingWidth; s++) {
                    planNameRow.add("");
                }
            }
        }
        matrix.add(planNameRow);

        // Row 1: Plan codes (network / contractCode)
        List<Object> planCodeRow = new ArrayList<>();
        for (String planCode : planCodes) {
            planCodeRow.add(planCode);
            planCodeRow.add("");
            if (columnSpacingWidth > 0) {
                for (int s = 0; s < columnSpacingWidth; s++) {
                    planCodeRow.add("");
                }
            }
            planCodeRow.add("");
            planCodeRow.add("");
            if (columnSpacingWidth > 0) {
                for (int s = 0; s < columnSpacingWidth; s++) {
                    planCodeRow.add("");
                }
            }
            planCodeRow.add("");
            planCodeRow.add("");
            if (columnSpacingWidth > 0) {
                for (int s = 0; s < columnSpacingWidth; s++) {
                    planCodeRow.add("");
                }
            }
        }
        matrix.add(planCodeRow);

        // Row 2: Column headers (Age/Rating pairs for each band)
        List<Object> bandHeaderRow = new ArrayList<>();
        for (String planName : planNames) {
            // Band 1 headers
            bandHeaderRow.add("Age");
            bandHeaderRow.add("Rating");
            if (columnSpacingWidth > 0) {
                for (int s = 0; s < columnSpacingWidth; s++) {
                    bandHeaderRow.add("");
                }
            }
            // Band 2 headers
            bandHeaderRow.add("Age");
            bandHeaderRow.add("Rating");
            if (columnSpacingWidth > 0) {
                for (int s = 0; s < columnSpacingWidth; s++) {
                    bandHeaderRow.add("");
                }
            }
            // Band 3 headers
            bandHeaderRow.add("Age");
            bandHeaderRow.add("Rating");
            if (columnSpacingWidth > 0) {
                for (int s = 0; s < columnSpacingWidth; s++) {
                    bandHeaderRow.add("");
                }
            }
        }
        matrix.add(bandHeaderRow);

        // Data rows: each row contains age/rating pairs for each band
        int maxAgeCount = Math.max(band1Ages.size(), Math.max(band2Ages.size(), band3Ages.size()));
        for (int ageIdx = 0; ageIdx < maxAgeCount; ageIdx++) {
            List<Object> dataRow = new ArrayList<>();

            for (int planIdx = 0; planIdx < planNames.size(); planIdx++) {
                String planName = planNames.get(planIdx);
                Map<String, Object> ageRatingMap = planAgeRatingMap.get(planName);

                // Band 1 (ages 0-30)
                String band1Age = ageIdx < band1Ages.size() ? band1Ages.get(ageIdx) : "";
                Object band1Rating = !band1Age.isEmpty() && ageRatingMap != null ? 
                        ageRatingMap.getOrDefault(band1Age, "") : "";
                dataRow.add(band1Age);
                dataRow.add(band1Rating);
                if (columnSpacingWidth > 0) {
                    for (int s = 0; s < columnSpacingWidth; s++) {
                        dataRow.add("");
                    }
                }

                // Band 2 (ages 31-47)
                String band2Age = ageIdx < band2Ages.size() ? band2Ages.get(ageIdx) : "";
                Object band2Rating = !band2Age.isEmpty() && ageRatingMap != null ? 
                        ageRatingMap.getOrDefault(band2Age, "") : "";
                dataRow.add(band2Age);
                dataRow.add(band2Rating);
                if (columnSpacingWidth > 0) {
                    for (int s = 0; s < columnSpacingWidth; s++) {
                        dataRow.add("");
                    }
                }

                // Band 3 (ages 48-64+)
                String band3Age = ageIdx < band3Ages.size() ? band3Ages.get(ageIdx) : "";
                Object band3Rating = !band3Age.isEmpty() && ageRatingMap != null ? 
                        ageRatingMap.getOrDefault(band3Age, "") : "";
                dataRow.add(band3Age);
                dataRow.add(band3Rating);
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

    /**
     * Determine which age band an age value belongs to.
     * Band 1: 0-30 (includes "0-14", "15", ..., "30")
     * Band 2: 31-47 (includes "31", "32", ..., "47")
     * Band 3: 48-64+ (includes "48", "49", ..., "64+")
     */
    private static int getAgeBand(String ageStr) {
        if (ageStr == null || ageStr.isEmpty()) return -1;
        
        // Handle special cases
        if ("0-14".equals(ageStr)) return 1;
        if ("64+".equals(ageStr)) return 3;
        
        try {
            int age = Integer.parseInt(ageStr);
            if (age <= 30) return 1;
            if (age <= 47) return 2;
            return 3;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Sort ages within a band, handling both "0-14" string and numeric strings.
     */
    private static void sortAgeBand(List<String> ageList) {
        ageList.sort((a, b) -> {
            // Handle special "0-14" band marker
            if ("0-14".equals(a)) return -1;
            if ("0-14".equals(b)) return 1;
            if ("64+".equals(a)) return 1;
            if ("64+".equals(b)) return -1;
            
            try {
                int aNum = Integer.parseInt(a);
                int bNum = Integer.parseInt(b);
                return Integer.compare(aNum, bNum);
            } catch (NumberFormatException e) {
                return a.compareTo(b);
            }
        });
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
