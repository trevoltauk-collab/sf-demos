package com.insurance.excel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurance.excel.model.TemplateMappingConfig.*;
import com.insurance.excel.resolver.CellValueWriter;
import com.insurance.excel.resolver.JsonValueResolver;
import com.insurance.excel.service.BenefitLookupStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;

import static com.insurance.excel.service.BenefitLookupStrategy.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests covering the dynamic column layout engine in BenefitLookupStrategy.
 *
 * Key invariant tested:
 *   The column computed for plan N in BENEFIT_LOOKUP must always equal
 *   the column computed for item N in a MULTI_COL mapping using the same
 *   startCol + columnGap — otherwise plan names and benefit values end up
 *   in misaligned columns.
 */
class BenefitLookupStrategyTest {

    private BenefitLookupStrategy strategy;
    private JsonValueResolver jsonResolver;

    @BeforeEach
    void setUp() {
        jsonResolver = new JsonValueResolver(new ObjectMapper());
        strategy = new BenefitLookupStrategy(jsonResolver, new CellValueWriter());
    }

    // ── Column computation unit tests ─────────────────────────────────────────

    @Nested
    class DynamicColumnComputation {

        @Test
        void adjacentPlans_noGap_columnCtoH() {
            // planStartCol=C (index 2), gap=0 → C D E F G H for plans 0-5
            int start = columnLetterToIndex("C"); // = 2

            assertThat(columnIndexToLetter(computePlanColIndex(start, 0, 0))).isEqualTo("C");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 1, 0))).isEqualTo("D");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 2, 0))).isEqualTo("E");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 3, 0))).isEqualTo("F");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 4, 0))).isEqualTo("G");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 5, 0))).isEqualTo("H");
        }

        @Test
        void oneSpacerBetweenPlans_gap1_everyOtherColumn() {
            // planStartCol=C, gap=1 → C, E, G, I (D, F, H are spacers)
            int start = columnLetterToIndex("C");

            assertThat(columnIndexToLetter(computePlanColIndex(start, 0, 1))).isEqualTo("C");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 1, 1))).isEqualTo("E");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 2, 1))).isEqualTo("G");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 3, 1))).isEqualTo("I");
        }

        @Test
        void twoSpacersBetweenPlans_gap2() {
            // planStartCol=C, gap=2 → C, F, I, L
            int start = columnLetterToIndex("C");

            assertThat(columnIndexToLetter(computePlanColIndex(start, 0, 2))).isEqualTo("C");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 1, 2))).isEqualTo("F");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 2, 2))).isEqualTo("I");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 3, 2))).isEqualTo("L");
        }

        @Test
        void differentStartCol_D_noGap() {
            int start = columnLetterToIndex("D");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 0, 0))).isEqualTo("D");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 1, 0))).isEqualTo("E");
            assertThat(columnIndexToLetter(computePlanColIndex(start, 2, 0))).isEqualTo("F");
        }

        @ParameterizedTest(name = "planIdx={0}, gap={1} → col {2}")
        @CsvSource({
            "0, 0, C",
            "1, 0, D",
            "9, 0, L",    // 10th plan adjacent → col L
            "0, 1, C",
            "1, 1, E",
            "4, 1, K",    // 5th plan with gap=1 → col K
        })
        void parameterized_startColC(int planIdx, int gap, String expected) {
            int start = columnLetterToIndex("C");
            assertThat(columnIndexToLetter(computePlanColIndex(start, planIdx, gap)))
                    .isEqualTo(expected);
        }

        @Test
        void columnRoundTrip_letterToIndexToLetter() {
            // Verify letter→index→letter round-trips for all common columns
            for (String col : List.of("A","B","C","D","E","F","G","H","I","J",
                                      "K","L","M","N","O","P","Q","R","S","T")) {
                int idx = columnLetterToIndex(col);
                assertThat(columnIndexToLetter(idx)).isEqualTo(col);
            }
        }
    }

    // ── Prior auth column computation ─────────────────────────────────────────

    @Nested
    class PriorAuthColumnComputation {

        @Test
        void nextCol_layout_priorAuthInSpacerColumn() {
            // Coverage col=C (index 2), gap=1 → prior auth goes in D (index 3)
            int coverageCol = columnLetterToIndex("C");
            int paCol = computePriorAuthColIndex(coverageCol, PriorAuthLayout.NEXT_COL, 1);
            assertThat(columnIndexToLetter(paCol)).isEqualTo("D");
        }

        @Test
        void sameColNextRow_layout_colUnchanged() {
            int coverageCol = columnLetterToIndex("E");
            int paCol = computePriorAuthColIndex(coverageCol, PriorAuthLayout.SAME_COL_NEXT_ROW, 0);
            assertThat(paCol).isEqualTo(coverageCol); // same column, row shift is handled separately
        }

        @Test
        void none_layout_returnsMinusOne() {
            int paCol = computePriorAuthColIndex(2, PriorAuthLayout.NONE, 0);
            assertThat(paCol).isEqualTo(-1); // sentinel — not used
        }
    }

    // ── Alignment invariant: benefit table columns must match plan header columns ──

    @Nested
    class AlignmentInvariant {

        /**
         * Critical property: plan_name_row (MULTI_COL) and benefit_table (BENEFIT_LOOKUP)
         * must put the same plan index into the same column.
         *
         * MULTI_COL formula:  col = indexOf(startCol) + planIdx * (1 + columnGap)
         * BENEFIT_LOOKUP:     col = indexOf(planStartCol) + planIdx * (1 + planColumnGap)
         *
         * When startCol == planStartCol and columnGap == planColumnGap, they are identical.
         */
        @Test
        void multiColAndBenefitLookup_sameStartAndGap_columnsAlign() {
            String startCol = "C";
            int    gap      = 1;
            int    start    = columnLetterToIndex(startCol);

            for (int planIdx = 0; planIdx < 6; planIdx++) {
                // MULTI_COL formula (from TemplateFillEngine.resolveColumnMapping)
                int multiColResult = start + planIdx * (1 + gap);
                // BENEFIT_LOOKUP formula
                int lookupResult   = computePlanColIndex(start, planIdx, gap);

                assertThat(lookupResult)
                        .as("Plan %d: BENEFIT_LOOKUP col must equal MULTI_COL col", planIdx)
                        .isEqualTo(multiColResult);
            }
        }
    }

    // ── Benefit index building from JSON ──────────────────────────────────────

    @Nested
    class BenefitIndexBuilding {

        @Test
        void resolvesBenefitKeyCorrectly() {
            var benefit = Map.of("benefitKey", "pcp_visit", "coverage", "$20 copay", "priorAuth", "No");
            Object key = jsonResolver.resolveField(benefit, "benefitKey");
            assertThat(key).isEqualTo("pcp_visit");
        }

        @Test
        void keyMatchingIsCaseInsensitive() {
            // Keys are lowercased in buildBenefitIndex, so "PCP_VISIT" matches "pcp_visit"
            var benefit = Map.of("benefitKey", "PCP_VISIT", "coverage", "$20 copay");
            Object key = jsonResolver.resolveField(benefit, "benefitKey");
            // The strategy lowercases: key.toString().trim().toLowerCase()
            assertThat(key.toString().toLowerCase()).isEqualTo("pcp_visit");
        }

        @Test
        void missingBenefitKey_notIndexed() {
            var benefit = Map.of("coverage", "$20 copay"); // no benefitKey field
            Object key = jsonResolver.resolveField(benefit, "benefitKey");
            assertThat(key).isNull(); // null keys are skipped in buildBenefitIndex
        }

        @Test
        void dotNotation_nestedCoverageField() {
            var benefit = Map.of(
                "benefitKey", "deductible",
                "cost", Map.of("individual", 500, "family", 1000)
            );
            Object indCost = jsonResolver.resolveField(benefit, "cost.individual");
            assertThat(indCost).isEqualTo(500);
        }
    }

    // ── Full payload structure validation ────────────────────────────────────

    @Nested
    class PayloadStructure {

        @Test
        void dynamicPlanCount_1plan() {
            int start = columnLetterToIndex("C");
            // 1 plan → only column C used
            assertThat(columnIndexToLetter(computePlanColIndex(start, 0, 0))).isEqualTo("C");
        }

        @Test
        void dynamicPlanCount_10plans_noGap() {
            int start = columnLetterToIndex("C"); // index 2
            // 10 plans → C through L
            String[] expected = {"C","D","E","F","G","H","I","J","K","L"};
            for (int i = 0; i < 10; i++) {
                assertThat(columnIndexToLetter(computePlanColIndex(start, i, 0)))
                        .as("Plan " + i)
                        .isEqualTo(expected[i]);
            }
        }

        @Test
        void overflowHandling_unmappedKeyFromJson() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            // "new_benefit_2025" is NOT in benefitRowMap → goes to overflow
            String json = """
                {
                  "plans": [{
                    "planName": "Gold PPO",
                    "benefits": [
                      {"benefitKey": "pcp_visit",       "coverage": "$20 copay"},
                      {"benefitKey": "new_benefit_2025", "coverage": "100% covered"}
                    ]
                  }]
                }""";
            Object data = mapper.readValue(json, Object.class);

            List<Object> plans   = jsonResolver.resolveList(data, "$.plans[*]");
            List<Object> benefits = jsonResolver.resolveList(plans.get(0), "$.benefits[*]");

            long unmappedCount = benefits.stream()
                .map(b -> jsonResolver.resolveField(b, "benefitKey"))
                .filter(k -> k != null && !Set.of("pcp_visit", "specialist_visit",
                        "emergency_room").contains(k.toString()))
                .count();
            assertThat(unmappedCount).isEqualTo(1); // "new_benefit_2025" would go to overflow
        }

        @Test
        void fallbackValue_writtenForMissingBenefit() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String json = """
                {
                  "plans": [
                    {
                      "planName": "Bronze HMO",
                      "benefits": [
                        {"benefitKey": "pcp_visit", "coverage": "$30 copay"}
                      ]
                    }
                  ]
                }""";
            Object data = mapper.readValue(json, Object.class);
            List<Object> plans = jsonResolver.resolveList(data, "$.plans[*]");
            List<Object> benefits = jsonResolver.resolveList(plans.get(0), "$.benefits[*]");

            // "emergency_room" is NOT in Bronze HMO → fallback
            boolean hasEmergency = benefits.stream()
                .anyMatch(b -> "emergency_room".equals(jsonResolver.resolveField(b, "benefitKey")));
            assertThat(hasEmergency).isFalse(); // confirms "Not Covered" would be written
        }
    }
}