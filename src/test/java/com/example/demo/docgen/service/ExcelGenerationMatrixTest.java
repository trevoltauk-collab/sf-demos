package com.example.demo.docgen.service;

import com.example.demo.docgen.model.DocumentGenerationRequest;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("local")
@SpringBootTest
public class ExcelGenerationMatrixTest {

    @Autowired
    private DocumentComposer composer;

    @Test
    public void generatedExcel_containsComparisonMatrix_A1_G6() throws Exception {
        Map<String, Object> data = new HashMap<>();

        List<Map<String, Object>> plans = new ArrayList<>();

        Map<String, Object> basic = new HashMap<>();
        basic.put("planName", "Basic");
        List<Map<String, Object>> basicBenefits = new ArrayList<>();
        basicBenefits.add(Map.of("name", "Doctor Visits", "value", "$20 copay"));
        basicBenefits.add(Map.of("name", "Prescriptions", "value", "$10 copay"));
        basic.put("benefits", basicBenefits);

        Map<String, Object> premium = new HashMap<>();
        premium.put("planName", "Premium");
        List<Map<String, Object>> premiumBenefits = new ArrayList<>();
        premiumBenefits.add(Map.of("name", "Doctor Visits", "value", "Covered 100%"));
        premiumBenefits.add(Map.of("name", "Prescriptions", "value", "$5 copay"));
        premium.put("benefits", premiumBenefits);

        plans.add(basic);
        plans.add(premium);

        data.put("comparisonTitle", "Insurance Plan Comparison");
        data.put("plans", plans);

        DocumentGenerationRequest req = DocumentGenerationRequest.builder()
            .namespace("common-templates")
            .templateId("plan-comparison")
            .data(data)
            .build();

        byte[] bytes = composer.generateExcel(req);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0, "Excel bytes should not be empty");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertNotNull(sheet, "Sheet must exist");

            // A1 -> row 0 col 0
            assertEquals("Benefit", sheet.getRow(0).getCell(0).getStringCellValue());

            // the plan-comparison template uses a columnSpacing of 2, so the first
            // plan header appears two empty columns to the right of the Benefit
            // column.  Compute dynamically so the test doesn’t hard-code the index.
            int spacing = 2;
            int firstPlanCol = 1 + spacing;
            int secondPlanCol = firstPlanCol + 1 + spacing;

            // first plan name ("Basic") should appear at the computed offset
            assertEquals("Basic", sheet.getRow(0).getCell(firstPlanCol).getStringCellValue());
            // underneath it, the first benefit value ought to be at the same column
            assertEquals("$20 copay", sheet.getRow(1).getCell(firstPlanCol).getStringCellValue());

            // Ensure header has 7 columns (A..G)
            assertEquals(7, sheet.getRow(0).getLastCellNum());
        }
    }
}
