package com.insurance.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.excel.service.ExcelReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests exercising all advanced mapping scenarios:
 *   - Column gap (spacer columns between plans)
 *   - Gap rows between benefit items
 *   - Formula skip (leaves template formula cells untouched)
 *   - Merge headers (category headers, plan name headers)
 *   - Composite fields (multi-JSON-field cell assembly)
 *   - Grouped list (benefits grouped by category)
 *   - Multi-row items (plan detail block spanning 3 rows)
 */
@SpringBootTest
class AdvancedMappingIntegrationTest {

    @Autowired ExcelReportService reportService;
    @Autowired ObjectMapper objectMapper;

    // ── Sample data shared across tests ──────────────────────────────────────

    private static final List<Map<String, Object>> SAMPLE_PLANS = List.of(
            Map.of(
                    "planName",            "Gold PPO 500",
                    "carrier",             "BlueCross",
                    "planType",            "PPO",
                    "networkType",         "Large Network",
                    "monthlyPremium",      650.00,
                    "employeeSharePct",    0.20,
                    "deductible",          Map.of("individual", 500,  "family", 1000),
                    "outOfPocketMax",      Map.of("individual", 4000, "family", 8000),
                    "coinsurance",         0.20
            ),
            Map.of(
                    "planName",            "Silver HDHP 1500",
                    "carrier",             "Aetna",
                    "planType",            "HDHP",
                    "networkType",         "Medium Network",
                    "monthlyPremium",      420.00,
                    "employeeSharePct",    0.10,
                    "deductible",          Map.of("individual", 1500, "family", 3000),
                    "outOfPocketMax",      Map.of("individual", 5000, "family", 10000),
                    "coinsurance",         0.30
            ),
            Map.of(
                    "planName",            "Bronze HMO 2500",
                    "carrier",             "Kaiser",
                    "planType",            "HMO",
                    "networkType",         "Closed Panel",
                    "monthlyPremium",      310.00,
                    "employeeSharePct",    0.05,
                    "deductible",          Map.of("individual", 2500, "family", 5000),
                    "outOfPocketMax",      Map.of("individual", 7500, "family", 15000),
                    "coinsurance",         0.40
            )
    );

    // Benefits grouped by category — drives GROUPED_LIST with category headers
    private static final List<Map<String, Object>> SAMPLE_BENEFITS = List.of(
            // Medical group
            Map.of("category", "Medical",
                   "benefitName", "Primary Care Visit",
                   "plan1Coverage", "$20 copay", "plan1PriorAuth", "No",
                   "plan2Coverage", "20% after ded.", "plan2PriorAuth", "No",
                   "plan3Coverage", "$30 copay", "plan3PriorAuth", "No",
                   "notes", ""),
            Map.of("category", "Medical",
                   "benefitName", "Specialist Visit",
                   "plan1Coverage", "$40 copay", "plan1PriorAuth", "Yes",
                   "plan2Coverage", "20% after ded.", "plan2PriorAuth", "Yes",
                   "plan3Coverage", "$50 copay", "plan3PriorAuth", "Yes",
                   "notes", "Referral required for HMO"),
            Map.of("category", "Medical",
                   "benefitName", "Inpatient Hospital",
                   "plan1Coverage", "$300/day (3 day max)", "plan1PriorAuth", "Yes",
                   "plan2Coverage", "20% after ded.", "plan2PriorAuth", "Yes",
                   "plan3Coverage", "20% after ded.", "plan3PriorAuth", "Yes",
                   "notes", ""),
            Map.of("category", "Medical",
                   "benefitName", "Emergency Room",
                   "plan1Coverage", "$250 copay (waived if admitted)", "plan1PriorAuth", "No",
                   "plan2Coverage", "20% after ded.", "plan2PriorAuth", "No",
                   "plan3Coverage", "20% after ded.", "plan3PriorAuth", "No",
                   "notes", ""),
            // Pharmacy group
            Map.of("category", "Pharmacy",
                   "benefitName", "Tier 1 — Generic",
                   "plan1Coverage", "$10 copay", "plan1PriorAuth", "No",
                   "plan2Coverage", "$10 after ded.", "plan2PriorAuth", "No",
                   "plan3Coverage", "$10 copay", "plan3PriorAuth", "No",
                   "notes", "30-day supply"),
            Map.of("category", "Pharmacy",
                   "benefitName", "Tier 2 — Preferred Brand",
                   "plan1Coverage", "$40 copay", "plan1PriorAuth", "No",
                   "plan2Coverage", "$40 after ded.", "plan2PriorAuth", "Yes",
                   "plan3Coverage", "$45 copay", "plan3PriorAuth", "No",
                   "notes", ""),
            Map.of("category", "Pharmacy",
                   "benefitName", "Tier 3 — Non-Preferred Brand",
                   "plan1Coverage", "$75 copay", "plan1PriorAuth", "Yes",
                   "plan2Coverage", "$75 after ded.", "plan2PriorAuth", "Yes",
                   "plan3Coverage", "$80 copay", "plan3PriorAuth", "Yes",
                   "notes", "Step therapy may apply"),
            Map.of("category", "Pharmacy",
                   "benefitName", "Specialty Drugs",
                   "plan1Coverage", "30% up to $250/fill", "plan1PriorAuth", "Yes",
                   "plan2Coverage", "30% after ded.", "plan2PriorAuth", "Yes",
                   "plan3Coverage", "30% up to $300/fill", "plan3PriorAuth", "Yes",
                   "notes", "Limited to specialty pharmacy network"),
            // Behavioral group
            Map.of("category", "Behavioral Health",
                   "benefitName", "Outpatient Mental Health",
                   "plan1Coverage", "$20 copay", "plan1PriorAuth", "No",
                   "plan2Coverage", "20% after ded.", "plan2PriorAuth", "No",
                   "plan3Coverage", "$30 copay", "plan3PriorAuth", "No",
                   "notes", "Parity with Medical"),
            Map.of("category", "Behavioral Health",
                   "benefitName", "Inpatient Substance Use",
                   "plan1Coverage", "$300/day (3 day max)", "plan1PriorAuth", "Yes",
                   "plan2Coverage", "20% after ded.", "plan2PriorAuth", "Yes",
                   "plan3Coverage", "20% after ded.", "plan3PriorAuth", "Yes",
                   "notes", ""),
            // Preventive group
            Map.of("category", "Preventive Care",
                   "benefitName", "Annual Physical",
                   "plan1Coverage", "100% covered", "plan1PriorAuth", "No",
                   "plan2Coverage", "100% covered", "plan2PriorAuth", "No",
                   "plan3Coverage", "100% covered", "plan3PriorAuth", "No",
                   "notes", "ACA-mandated preventive"),
            Map.of("category", "Preventive Care",
                   "benefitName", "Immunizations",
                   "plan1Coverage", "100% covered", "plan1PriorAuth", "No",
                   "plan2Coverage", "100% covered", "plan2PriorAuth", "No",
                   "plan3Coverage", "100% covered", "plan3PriorAuth", "No",
                   "notes", "")
    );

    private static final List<Map<String, Object>> SAMPLE_RATING_TIERS = List.of(
            Map.of("tier", "Employee Only",    "ageFrom", 18, "ageTo", 29, "monthlyRate", 380.00, "tobaccoSurcharge", 0.00),
            Map.of("tier", "Employee Only",    "ageFrom", 30, "ageTo", 39, "monthlyRate", 440.00, "tobaccoSurcharge", 44.00),
            Map.of("tier", "Employee Only",    "ageFrom", 40, "ageTo", 49, "monthlyRate", 530.00, "tobaccoSurcharge", 53.00),
            Map.of("tier", "Employee Only",    "ageFrom", 50, "ageTo", 64, "monthlyRate", 680.00, "tobaccoSurcharge", 68.00),
            Map.of("tier", "Emp + Spouse",     "ageFrom", 18, "ageTo", 29, "monthlyRate", 760.00, "tobaccoSurcharge", 0.00),
            Map.of("tier", "Emp + Spouse",     "ageFrom", 30, "ageTo", 39, "monthlyRate", 880.00, "tobaccoSurcharge", 88.00),
            Map.of("tier", "Emp + Spouse",     "ageFrom", 40, "ageTo", 64, "monthlyRate", 1060.00,"tobaccoSurcharge", 106.00),
            Map.of("tier", "Emp + Children",   "ageFrom", 18, "ageTo", 64, "monthlyRate", 920.00, "tobaccoSurcharge", 0.00),
            Map.of("tier", "Emp + Family",     "ageFrom", 18, "ageTo", 29, "monthlyRate", 1100.00,"tobaccoSurcharge", 0.00),
            Map.of("tier", "Emp + Family",     "ageFrom", 30, "ageTo", 64, "monthlyRate", 1250.00,"tobaccoSurcharge", 125.00)
    );

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void advancedPlanComparison_allScenariosSucceed() throws Exception {
        Map<String, Object> payload = Map.of(
                "reportTitle",   "2025 Open Enrollment — Proposed Plans",
                "effectiveDate", "01/01/2025",
                "employerName",  "Acme Corporation",
                "disclaimer",    "Premium rates are illustrative. Final rates subject to carrier underwriting. " +
                                  "Benefits subject to plan document terms and conditions.",
                "plans",        SAMPLE_PLANS,
                "benefits",     SAMPLE_BENEFITS,
                "ratingTiers",  SAMPLE_RATING_TIERS,
                "productName",  "Group Medical Plan — 2025",
                "rates",        SAMPLE_RATING_TIERS   // reuse for rating sheet
        );

        byte[] result = reportService.generate("plan-comparison-advanced", payload);
        assertThat(result).isNotEmpty();
        System.out.println("✓ Advanced plan comparison generated: " + result.length + " bytes");
    }

    @Test
    void compositeField_ageRange() throws Exception {
        // Verify composite "Age 18 - 29" logic in isolation is correct
        var builder = new com.insurance.excel.resolver.CompositeValueBuilder(
                new com.insurance.excel.resolver.JsonValueResolver(new ObjectMapper()));

        var spec = new com.insurance.excel.model.TemplateMappingConfig.CompositeSpec();
        spec.setParts(List.of("Age ", "$ageFrom", " - ", "$ageTo"));
        spec.setSeparator("");

        String result = builder.build(spec, Map.of("ageFrom", 18, "ageTo", 29));
        assertThat(result).isEqualTo("Age 18 - 29");
    }

    @Test
    void compositeField_deductibleSummary() throws Exception {
        var builder = new com.insurance.excel.resolver.CompositeValueBuilder(
                new com.insurance.excel.resolver.JsonValueResolver(new ObjectMapper()));

        var spec = new com.insurance.excel.model.TemplateMappingConfig.CompositeSpec();
        spec.setParts(List.of("Ind: $", "$deductible.individual", "  |  Fam: $", "$deductible.family"));
        spec.setSeparator("");
        spec.setNumericFormat("#,##0");

        String result = builder.build(spec,
                Map.of("deductible", Map.of("individual", 500, "family", 1000)));
        assertThat(result).isEqualTo("Ind: $500  |  Fam: $1,000");
    }

    @Test
    void formulaSkipEvaluator_parsesCorrectly() {
        var evaluator = new com.insurance.excel.resolver.FormulaSkipEvaluator();

        var skipMap = evaluator.parse(List.of("E", "G:0", "G:2", "H:1"));

        // E = skip ALL row offsets
        assertThat(evaluator.shouldSkip(skipMap, "E", 0)).isTrue();
        assertThat(evaluator.shouldSkip(skipMap, "E", 5)).isTrue();

        // G = skip only rowOffset 0 and 2
        assertThat(evaluator.shouldSkip(skipMap, "G", 0)).isTrue();
        assertThat(evaluator.shouldSkip(skipMap, "G", 1)).isFalse();
        assertThat(evaluator.shouldSkip(skipMap, "G", 2)).isTrue();

        // H = skip only rowOffset 1
        assertThat(evaluator.shouldSkip(skipMap, "H", 0)).isFalse();
        assertThat(evaluator.shouldSkip(skipMap, "H", 1)).isTrue();

        // F = not in skip list
        assertThat(evaluator.shouldSkip(skipMap, "F", 0)).isFalse();
    }
}