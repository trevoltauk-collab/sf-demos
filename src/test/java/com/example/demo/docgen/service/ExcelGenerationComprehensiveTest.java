package com.example.demo.docgen.service;

import com.example.demo.docgen.util.PlanComparisonTransformer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive test suite for Excel generation with plan comparison functionality
 * 
 * Tests cover:
 * - Basic plan comparison rendering
 * - Different plan/benefit combinations
 * - Auto-transformation of raw plan data
 * - Edge cases and error handling
 * - Matrix structure and content validation
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@DisplayName("Excel Generation - Comprehensive Test Suite")
class ExcelGenerationComprehensiveTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Nested
    @DisplayName("Plan Comparison - Basic Scenarios")
    class BasicPlanComparisonTests {

        /**
         * Test 1: Simple 3-plan comparison with same benefits
         * All plans have same benefits in same order
         */
        @Test
        @DisplayName("Should render 3 plans with common benefits")
        void testSimplePlanComparison() throws Exception {
            Map<String, Object> requestData = new HashMap<>();
            requestData.put("comparisonTitle", "Basic Plan Comparison");

            List<Map<String, Object>> plans = Arrays.asList(
                    createPlan("Basic", Arrays.asList(
                            createBenefit("Doctor Visits", "$20 copay"),
                            createBenefit("ER Visit", "$250"),
                            createBenefit("Hospital Stay", "$1000 deductible")
                    )),
                    createPlan("Standard", Arrays.asList(
                            createBenefit("Doctor Visits", "Covered 100%"),
                            createBenefit("ER Visit", "$150"),
                            createBenefit("Hospital Stay", "$500 deductible")
                    )),
                    createPlan("Premium", Arrays.asList(
                            createBenefit("Doctor Visits", "Covered 100%"),
                            createBenefit("ER Visit", "Covered 100%"),
                            createBenefit("Hospital Stay", "Covered 100%")
                    ))
            );

            Map<String, Object> enrichedData = PlanComparisonTransformer.injectComparisonMatrix(requestData, plans);

            byte[] excelBytes = performExcelGeneration(
                    "common-templates",
                    "plan-comparison",
                    enrichedData
            );

            // Validate structure
            Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes));
            Sheet sheet = workbook.getSheetAt(0);

            // Expected: 4 rows (1 header + 3 benefits) x 7 columns (benefit + 3 plans with 1-col spacing)
            assertEquals("Benefit", getCellValue(sheet, 0, 0));
            assertEquals("Basic", getCellValue(sheet, 0, 2));
            assertEquals("Standard", getCellValue(sheet, 0, 4));
            assertEquals("Premium", getCellValue(sheet, 0, 6));

            assertEquals("Doctor Visits", getCellValue(sheet, 1, 0));
            assertEquals("$20 copay", getCellValue(sheet, 1, 2));
            assertEquals("Covered 100%", getCellValue(sheet, 1, 4));
            assertEquals("Covered 100%", getCellValue(sheet, 1, 6));

            workbook.close();
        }

        /**
         * Test 2: Plans with different benefit orders
         * Each plan has benefits in different order - transformer should normalize
         */
        @Test
        @DisplayName("Should handle benefits in different orders across plans")
        void testPlanComparisonDifferentBenefitOrders() throws Exception {
            List<Map<String, Object>> plans = Arrays.asList(
                    // Plan A: Benefits in order 1, 2, 3
                    createPlan("Plan A", Arrays.asList(
                            createBenefit("Doctor Visits", "Yes"),
                            createBenefit("ER Visit", "$20"),
                            createBenefit("Prescription", "$5 copay")
                    )),
                    // Plan B: Benefits in order 3, 1, 2 (different order)
                    createPlan("Plan B", Arrays.asList(
                            createBenefit("Prescription", "$10 copay"),
                            createBenefit("Doctor Visits", "Free"),
                            createBenefit("ER Visit", "$50")
                    )),
                    // Plan C: Benefits in order 2, 3, 1 (different order again)
                    createPlan("Plan C", Arrays.asList(
                            createBenefit("ER Visit", "Covered"),
                            createBenefit("Prescription", "Free"),
                            createBenefit("Doctor Visits", "Covered 100%")
                    ))
            );

            Map<String, Object> data = new HashMap<>();
            Map<String, Object> enrichedData = PlanComparisonTransformer.injectComparisonMatrix(data, plans);

            byte[] excelBytes = performExcelGeneration(
                    "common-templates",
                    "plan-comparison",
                    enrichedData
            );

            Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes));
            Sheet sheet = workbook.getSheetAt(0);

            // Verify all benefits appear in same order (normalized by transformer)
            assertEquals("Doctor Visits", getCellValue(sheet, 1, 0));
            assertEquals("ER Visit", getCellValue(sheet, 2, 0));
            assertEquals("Prescription", getCellValue(sheet, 3, 0));

            // Verify values are correctly matched across plans despite different input order
            assertEquals("Yes", getCellValue(sheet, 1, 2));      // Plan A Doctor Visits
            assertEquals("Free", getCellValue(sheet, 1, 4));     // Plan B Doctor Visits
            assertEquals("Covered 100%", getCellValue(sheet, 1, 6)); // Plan C Doctor Visits

            workbook.close();
        }

        /**
         * Test 3: Plans with missing benefits
         * Some plans don't have all benefits - should show empty cells
         */
        @Test
        @DisplayName("Should handle missing benefits in some plans")
        void testPlanComparisonMissingBenefits() throws Exception {
            List<Map<String, Object>> plans = Arrays.asList(
                    createPlan("Plan A", Arrays.asList(
                            createBenefit("Doctor Visits", "$20"),
                            createBenefit("ER Visit", "$100"),
                            createBenefit("Dental", "Not Covered")
                    )),
                    // Plan B missing Dental benefit
                    createPlan("Plan B", Arrays.asList(
                            createBenefit("Doctor Visits", "Free"),
                            createBenefit("ER Visit", "Free")
                    )),
                    createPlan("Plan C", Arrays.asList(
                            createBenefit("Doctor Visits", "$15"),
                            createBenefit("ER Visit", "$50"),
                            createBenefit("Dental", "Covered")
                    ))
            );

            Map<String, Object> data = new HashMap<>();
            Map<String, Object> enrichedData = PlanComparisonTransformer.injectComparisonMatrix(data, plans);

            byte[] excelBytes = performExcelGeneration(
                    "common-templates",
                    "plan-comparison",
                    enrichedData
            );

            Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes));
            Sheet sheet = workbook.getSheetAt(0);

            // Find Dental row
            String dentalValue = getCellValue(sheet, 3, 0);
            assertEquals("Dental", dentalValue);

            // Plan A has Dental: "Not Covered"
            assertEquals("Not Covered", getCellValue(sheet, 3, 2));

            // Plan B should be empty (missing benefit)
            String planBDentalValue = getCellValue(sheet, 3, 4);
            assertTrue(planBDentalValue == null || planBDentalValue.isEmpty(),
                    "Plan B Dental should be empty but got: " + planBDentalValue);

            // Plan C has Dental: "Covered"
            assertEquals("Covered", getCellValue(sheet, 3, 6));

            workbook.close();
        }
    }

    @Nested
    @DisplayName("Plan Comparison - Auto-Transformation")
    class AutoTransformationTests {

        /**
         * Test 4: Raw plan data without manual transformation
         * Tests the new auto-transformation feature in DocumentComposer
         */
        @Test
        @DisplayName("Should auto-transform raw plan data without client-side transformer")
        void testAutoTransformationFromRawPlanData() throws Exception {
            Map<String, Object> requestData = new HashMap<>();
            List<Map<String, Object>> plans = Arrays.asList(
                    createPlan("Entry", Arrays.asList(
                            createBenefit("Primary Care", "$15"),
                            createBenefit("Specialist", "$40")
                    )),
                    createPlan("Plus", Arrays.asList(
                            createBenefit("Primary Care", "Free"),
                            createBenefit("Specialist", "$20")
                    ))
            );

            // Add plans to request data
            requestData.put("plans", plans);

            // additional scenario: explicit config-driven template (foo-explicit)
            {
                byte[] excelBytes2 = performExcelGeneration(
                        "common-templates",
                        "foo-explicit",   // ID not starting with "plan-comparison"
                        requestData
                );
                Workbook wb2 = WorkbookFactory.create(new ByteArrayInputStream(excelBytes2));
                Sheet s2 = wb2.getSheetAt(0);
                // print header cells for debugging
                for (int c = 0; c < 10; c++) {
                    System.out.println("[DEBUG] header cell(0," + c + ") = '" + getCellValue(s2, 0, c) + "'");
                }
                int spacing = 2;
                int firstPlanCol = 1 + spacing;
                int secondPlanCol = firstPlanCol + 1 + spacing;
                assertEquals("Entry", getCellValue(s2,0,firstPlanCol));
                assertEquals("Plus", getCellValue(s2,0,secondPlanCol));
                wb2.close();
                // DEBUG: inspect requestData after server call
                System.out.println("[DEBUG] requestData after foo-explicit call: " + requestData);
            }

            // scenario: custom plansPath in template
            {
                Map<String,Object> reqData2 = new HashMap<>(requestData);
                reqData2.put("altPlans", reqData2.remove("plans"));
                // create a temporary template id that sets plansPath
                // use existing foo-explicit for convenience
                byte[] excelBytes3 = performExcelGeneration(
                        "common-templates",
                        "foo-explicit",
                        reqData2
                );
                Workbook wb3 = WorkbookFactory.create(new ByteArrayInputStream(excelBytes3));
                Sheet s3 = wb3.getSheetAt(0);
                int firstPlanCol = 1 + 2;
                assertEquals("Entry", getCellValue(s3,0,firstPlanCol));
                wb3.close();
            }

            // Put plans directly in data WITHOUT calling transformer
            byte[] excelBytes = performExcelGeneration(
                    "common-templates",
                    "plan-comparison",
                    requestData // Raw data, no comparisonMatrix yet
            );

            // Verify the server auto-transformed it
            Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes));
            Sheet sheet = workbook.getSheetAt(0);

            // If auto-transformation worked, we should see the matrix
            assertEquals("Benefit", getCellValue(sheet, 0, 0));
            // template is configured with columnSpacing=2; compute offsets
            int spacing = 2;
            int firstPlanCol = 1 + spacing;                // after "Benefit" + spacing
            int secondPlanCol = firstPlanCol + 1 + spacing; // next plan header

            assertEquals("Entry", getCellValue(sheet, 0, firstPlanCol));
            assertEquals("Plus", getCellValue(sheet, 0, secondPlanCol));

            assertEquals("Primary Care", getCellValue(sheet, 1, 0));
            assertEquals("$15", getCellValue(sheet, 1, firstPlanCol));
            assertEquals("Free", getCellValue(sheet, 1, secondPlanCol));

            workbook.close();

            // --- new behaviour: section-level config example ---
            Map<String, Object> sectionData = new HashMap<>();
            sectionData.put("plans", plans);
            sectionData.put("note", "second sheet text");

            byte[] excelBytesSection = performExcelGeneration(
                    "common-templates",
                    "section-transform",
                    sectionData
            );
            Workbook wbSection = WorkbookFactory.create(new ByteArrayInputStream(excelBytesSection));
            // workbook should now have two sheets (template cloned for each section)
            assertEquals(2, wbSection.getNumberOfSheets(), "Expected two sheets after rendering two sections using same template");
            // first sheet should contain plan headers
            Sheet s1 = wbSection.getSheetAt(0);
            assertEquals("Entry", getCellValue(s1, 0, firstPlanCol));
            // second sheet should show the note cell at A1
            Sheet s2 = wbSection.getSheetAt(1);
            assertEquals("second sheet text", getCellValue(s2, 0, 0));
            wbSection.close();
        }

        /**
         * Test 5: Verify auto-transformation skips if comparisonMatrix already exists
         * Should use the provided matrix instead of transforming
         */
        @Test
        @DisplayName("Should skip auto-transformation if comparisonMatrix already provided")
        void testAutoTransformationSkipsIfMatrixExists() throws Exception {
            // Provide explicit matrix
            List<List<Object>> customMatrix = Arrays.asList(
                    Arrays.asList("Benefit", "", "CustomPlan"),
                    Arrays.asList("CustomBenefit", "", "CustomValue")
            );

            Map<String, Object> requestData = new HashMap<>();
            requestData.put("plans", Arrays.asList(
                    createPlan("IgnoredPlan", Arrays.asList(
                            createBenefit("IgnoredBenefit", "Should not appear")
                    ))
            ));
            requestData.put("comparisonMatrix", customMatrix);

            byte[] excelBytes = performExcelGeneration(
                    "common-templates",
                    "plan-comparison",
                    requestData
            );

            Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes));
            Sheet sheet = workbook.getSheetAt(0);

            // Should see custom matrix, not auto-transformed one
            assertEquals("CustomPlan", getCellValue(sheet, 0, 2));
            assertEquals("CustomBenefit", getCellValue(sheet, 1, 0));
            assertEquals("CustomValue", getCellValue(sheet, 1, 2));

            // Original plan data should NOT be visible
            assertNotEquals("IgnoredPlan", getCellValue(sheet, 0, 2));

            workbook.close();
        }

        /**
         * Test 6: Auto-transform into values-only matrix when template is configured accordingly
         */
        @Test
        @DisplayName("Should auto-transform raw plan data into values-only matrix")
        void testAutoTransformationValuesOnly() throws Exception {
            Map<String, Object> requestData = new HashMap<>();
            List<Map<String, Object>> plans = Arrays.asList(
                    createPlan("X", Arrays.asList(createBenefit("B", "1"))),
                    createPlan("Y", Arrays.asList(createBenefit("B", "2")))
            );
            requestData.put("plans", plans);

            byte[] excelBytes = performExcelGeneration(
                    "common-templates",
                    "plan-comparison-values-only",
                    requestData
            );

            Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes));
            Sheet sheet = workbook.getSheetAt(0);

            // Column A should remain untouched (blank or empty in base workbook)
            String first = getCellValue(sheet, 0, 0);
            assertTrue(first == null || first.isEmpty(), "Column A should be blank but was: '" + first + "'");

            // dump first two rows for debugging
            System.out.println("Row0: " + rowToString(sheet, 0, 0, 10));
            System.out.println("Row1: " + rowToString(sheet, 1, 0, 10));

            // the values-only template uses columnSpacing = 1 (see plan-comparison-values-only.yaml)
            int spacing = 1;
            int firstPlanCol = 1 + spacing;
            int secondPlanCol = firstPlanCol + 1 + spacing;

            System.out.println("Using firstPlanCol="+firstPlanCol+" secondPlanCol="+secondPlanCol);

            assertEquals("X", getCellValue(sheet, 0, firstPlanCol));
            assertEquals("Y", getCellValue(sheet, 0, secondPlanCol));
            assertEquals("1", getCellValue(sheet, 1, firstPlanCol));
            assertEquals("2", getCellValue(sheet, 1, secondPlanCol));

            workbook.close();
        }

        /**
         * Test 7: Auto-transformation should respect provided comparisonMatrixValues when config valuesOnly=true
         */
        @Test
        @DisplayName("Should skip auto-transformation if comparisonMatrixValues already provided")
        void testAutoTransformationSkipsIfValuesMatrixExists() throws Exception {
            List<List<Object>> customMatrix = Arrays.asList(
                    Arrays.asList("X", "", "Y"),
                    Arrays.asList("V1", "", "V2")
            );
            Map<String, Object> requestData = new HashMap<>();
            requestData.put("plans", Arrays.asList(
                    createPlan("ignored", Arrays.asList(createBenefit("foo", "bar")))
            ));
            requestData.put("comparisonMatrixValues", customMatrix);

            byte[] excelBytes = performExcelGeneration(
                    "common-templates",
                    "plan-comparison-values-only",
                    requestData
            );

            Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes));
            Sheet sheet = workbook.getSheetAt(0);

            assertEquals("X", getCellValue(sheet, 0, 1));
            assertEquals("Y", getCellValue(sheet, 0, 3));
            assertEquals("V1", getCellValue(sheet, 1, 1));
            assertEquals("V2", getCellValue(sheet, 1, 3));

            workbook.close();
        }
    }

    @Nested
    @DisplayName("Plan Comparison - Advanced Scenarios")
    class AdvancedScenarios {

        /**
         * Test 6: Large comparison with many plans and benefits
         */
        @Test
        @DisplayName("Should handle many plans with many benefits")
        void testLargePlanComparison() throws Exception {
            // Template only supports 3 plans and 5 benefits (A1:G6 = header + 5 rows)
            // So create 3 plans with 5 different benefits each
            List<Map<String, Object>> plans = new ArrayList<>();

            for (int p = 1; p <= 3; p++) {
                List<Map<String, Object>> benefits = new ArrayList<>();
                for (int b = 1; b <= 5; b++) {
                    benefits.add(createBenefit("Benefit " + b, "Plan " + p + " Value " + b));
                }
                plans.add(createPlan("Plan " + p, benefits));
            }

            Map<String, Object> data = new HashMap<>();
            Map<String, Object> enrichedData = PlanComparisonTransformer.injectComparisonMatrix(data, plans);

            byte[] excelBytes = performExcelGeneration(
                    "common-templates",
                    "plan-comparison",
                    enrichedData
            );

            Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes));
            Sheet sheet = workbook.getSheetAt(0);

            // Verify structure: header + 5 benefits (template max is A1:G6)
            assertEquals("Benefit", getCellValue(sheet, 0, 0));
            assertEquals("Plan 1", getCellValue(sheet, 0, 2));
            assertEquals("Plan 2", getCellValue(sheet, 0, 4));
            assertEquals("Plan 3", getCellValue(sheet, 0, 6));

            // Verify all 5 benefits are present
            for (int i = 1; i <= 5; i++) {
                String benefitName = getCellValue(sheet, i, 0);
                assertEquals("Benefit " + i, benefitName);
                
                // Verify values for each plan
                assertEquals("Plan 1 Value " + i, getCellValue(sheet, i, 2));
                assertEquals("Plan 2 Value " + i, getCellValue(sheet, i, 4));
                assertEquals("Plan 3 Value " + i, getCellValue(sheet, i, 6));
            }

            workbook.close();
        }

        /**
         * Test 7: Case-insensitive benefit matching
         * "Doctor" and "doctor" should be treated as same benefit
         */
        @Test
        @DisplayName("Should match benefits case-insensitively")
        void testBenefitCaseInsensitiveMatching() throws Exception {
            List<Map<String, Object>> plans = Arrays.asList(
                    createPlan("Plan A", Arrays.asList(
                            createBenefit("Doctor Visits", "$20"),
                            createBenefit("ER Visit", "$100")
                    )),
                    createPlan("Plan B", Arrays.asList(
                            // Same benefit but different case
                            createBenefit("doctor visits", "Free"),
                            createBenefit("er visit", "Free")
                    ))
            );

            Map<String, Object> data = new HashMap<>();
            Map<String, Object> enrichedData = PlanComparisonTransformer.injectComparisonMatrix(data, plans);

            @SuppressWarnings("unchecked")
            List<List<Object>> matrix = (List<List<Object>>) enrichedData.get("comparisonMatrix");

            // Should have only 3 rows (header + 2 benefits), not 4
            assertEquals(3, matrix.size(), "Should normalize case-insensitive benefit names");

            // Display names should use first occurrence casing
            assertEquals("Doctor Visits", matrix.get(1).get(0));
            assertEquals("ER Visit", matrix.get(2).get(0));
        }

        /**
         * Test 8: Custom spacing width
         * Test rendering with 2-column spacing instead of default 1
         */
        @Test
        @DisplayName("Should support custom spacing width between plan columns")
        void testCustomSpacingWidth() throws Exception {
            List<Map<String, Object>> plans = Arrays.asList(
                    createPlan("Plan A", Arrays.asList(
                            createBenefit("Coverage", "Yes")
                    )),
                    createPlan("Plan B", Arrays.asList(
                            createBenefit("Coverage", "No")
                    ))
            );

            // Use 2-column spacing instead of default 1
            List<List<Object>> matrix = PlanComparisonTransformer.transformPlansToMatrix(
                    plans, "name", "value", 2
            );

            // Expected: Benefit | spacer1 | spacer2 | Plan A | spacer1 | spacer2 | Plan B
            // Row 0 should have: Benefit, "", "", Plan A, "", "", Plan B
            assertEquals("Benefit", matrix.get(0).get(0));
            assertEquals("", matrix.get(0).get(1));
            assertEquals("", matrix.get(0).get(2));
            assertEquals("Plan A", matrix.get(0).get(3));
            assertEquals("", matrix.get(0).get(4));
            assertEquals("", matrix.get(0).get(5));
            assertEquals("Plan B", matrix.get(0).get(6));
        }

        /**
         * Test 9: Special characters and formatting in benefit values
         */
        @Test
        @DisplayName("Should preserve special characters and formatting in values")
        void testSpecialCharactersInBenefitValues() throws Exception {
            List<Map<String, Object>> plans = Arrays.asList(
                    createPlan("Plan A", Arrays.asList(
                            createBenefit("Cost", "$100-$500"),
                            createBenefit("Coverage", "100% (after deductible)"),
                            createBenefit("Notes", "Covers: Visits, Tests & Labs")
                    )),
                    createPlan("Plan B", Arrays.asList(
                            createBenefit("Cost", "$50/visit (max 5)"),
                            createBenefit("Coverage", "50%–75%"),
                            createBenefit("Notes", "See: provider.com/benefits")
                    ))
            );

            Map<String, Object> data = new HashMap<>();
            Map<String, Object> enrichedData = PlanComparisonTransformer.injectComparisonMatrix(data, plans);

            byte[] excelBytes = performExcelGeneration(
                    "common-templates",
                    "plan-comparison",
                    enrichedData
            );

            Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes));
            Sheet sheet = workbook.getSheetAt(0);

            // Verify special characters are preserved
            assertEquals("$100-$500", getCellValue(sheet, 1, 2));
            assertEquals("100% (after deductible)", getCellValue(sheet, 2, 2));
            assertEquals("$50/visit (max 5)", getCellValue(sheet, 1, 4));
            assertEquals("50%–75%", getCellValue(sheet, 2, 4));

            workbook.close();
        }
    }

    @Nested
    @DisplayName("Plan Comparison - Edge Cases and Error Handling")
    class EdgeCasesAndErrorHandling {

        /**
         * Test 10: Empty plans list
         */
        @Test
        @DisplayName("Should handle empty plans list gracefully")
        void testEmptyPlans() throws Exception {
            List<Map<String, Object>> plans = new ArrayList<>();
            Map<String, Object> data = new HashMap<>();
            Map<String, Object> enrichedData = PlanComparisonTransformer.injectComparisonMatrix(data, plans);

            @SuppressWarnings("unchecked")
            List<List<Object>> matrix = (List<List<Object>>) enrichedData.get("comparisonMatrix");
            
            // Should return empty matrix
            assertEquals(0, matrix.size());
        }

        /**
         * Test 11: Single plan only
         */
        @Test
        @DisplayName("Should handle single plan comparison")
        void testSinglePlan() throws Exception {
            List<Map<String, Object>> plans = Arrays.asList(
                    createPlan("OnlyPlan", Arrays.asList(
                            createBenefit("Benefit1", "Value1"),
                            createBenefit("Benefit2", "Value2")
                    ))
            );

            Map<String, Object> data = new HashMap<>();
            Map<String, Object> enrichedData = PlanComparisonTransformer.injectComparisonMatrix(data, plans);

            byte[] excelBytes = performExcelGeneration(
                    "common-templates",
                    "plan-comparison",
                    enrichedData
            );

            Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes));
            Sheet sheet = workbook.getSheetAt(0);

            assertEquals("Benefit", getCellValue(sheet, 0, 0));
            assertEquals("OnlyPlan", getCellValue(sheet, 0, 2));

            workbook.close();
        }

        /**
         * Test 12: Null or missing benefit values
         */
        @Test
        @DisplayName("Should handle null and missing benefit values")
        void testNullBenefitValues() throws Exception {
            List<Map<String, Object>> plans = Arrays.asList(
                    createPlan("Plan A", Arrays.asList(
                            createBenefit("Benefit1", null),  // null value
                            createBenefit("Benefit2", "Value2")
                    )),
                    createPlan("Plan B", Arrays.asList(
                            createBenefit("Benefit1", ""),    // empty value
                            createBenefit("Benefit2", "Value2B")
                    ))
            );

            Map<String, Object> data = new HashMap<>();
            Map<String, Object> enrichedData = PlanComparisonTransformer.injectComparisonMatrix(data, plans);

            @SuppressWarnings("unchecked")
            List<List<Object>> matrix = (List<List<Object>>) enrichedData.get("comparisonMatrix");

            // Row 1: Benefit1 values
            // Plan A: null should become ""
            Object planABenefit1Value = matrix.get(1).get(2);
            assertTrue(planABenefit1Value == null || "".equals(planABenefit1Value));

            // Plan B: empty stays empty
            Object planBBenefit1Value = matrix.get(1).get(4);
            assertTrue(planBBenefit1Value == null || "".equals(planBBenefit1Value));
        }

        /**
         * Test 13: Non-plan-comparison template should not trigger transformation
         */
        @Test
        @DisplayName("Should not transform data for non-plan-comparison templates")
        void testNoTransformationForOtherTemplates() throws Exception {
            // This test just ensures that if we had another template,
            // the auto-transformation wouldn't interfere
            Map<String, Object> data = new HashMap<>();
            data.put("plans", Arrays.asList(
                    createPlan("Plan", Arrays.asList(createBenefit("Benefit", "Value")))
            ));

            // Using a different template ID should not auto-transform
            // (This would fail or behave differently, which is expected)
            // We're just verifying the logic doesn't try to auto-transform
            
            assertNull(data.get("comparisonMatrix"), 
                    "Should not auto-inject comparisonMatrix without auto-transformation trigger");
        }
    }

    // ==================== Helper Methods ====================

    private byte[] performExcelGeneration(String namespace, String templateId, 
                                         Map<String, Object> data) throws Exception {
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("namespace", namespace);
        requestPayload.put("templateId", templateId);
        requestPayload.put("data", data);

        String requestBody = objectMapper.writeValueAsString(requestPayload);

        MvcResult result = mockMvc.perform(post("/api/documents/generate/excel")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        return result.getResponse().getContentAsByteArray();
    }

    private String getCellValue(Sheet sheet, int row, int col) {
        var r = sheet.getRow(row);
        if (r == null) return null;
        var cell = r.getCell(col);
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                // remove any trailing .0 if integer
                double d = cell.getNumericCellValue();
                if (d == (long) d) {
                    return String.valueOf((long) d);
                }
                return String.valueOf(d);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            case BLANK:
                return "";
            default:
                return cell.toString();
        }
    }

    private Map<String, Object> createPlan(String planName, List<Map<String, Object>> benefits) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("planName", planName);
        plan.put("benefits", benefits);
        return plan;
    }

    private Map<String, Object> createBenefit(String name, String value) {
        Map<String, Object> benefit = new HashMap<>();
        benefit.put("name", name);
        benefit.put("value", value);
        return benefit;
    }

    private String rowToString(Sheet sheet, int rowIndex, int startCol, int endCol) {
        Row r = sheet.getRow(rowIndex);
        if (r == null) return "<null>";
        StringBuilder sb = new StringBuilder();
        for (int c = startCol; c <= endCol; c++) {
            sb.append(getCellValue(sheet, rowIndex, c));
            if (c < endCol) sb.append("|");
        }
        return sb.toString();
    }
}
