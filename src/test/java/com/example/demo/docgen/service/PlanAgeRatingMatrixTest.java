package com.example.demo.docgen.service;

import com.example.demo.docgen.mapper.MappingType;
import com.example.demo.docgen.model.DocumentGenerationRequest;
import com.example.demo.docgen.model.DocumentTemplate;
import com.example.demo.docgen.model.PageSection;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.example.demo.docgen.service.DocumentComposer;
import com.example.demo.docgen.service.TemplateLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Demonstrates how to render a set of plans that each contain three
 * independent age\/rating column groups.  The matrix transformation
 * shown here can be reused by production code; the test simply exercises
 * it and then verifies that the resulting workbook contains the expected
 * values.
 */
@SpringBootTest
@ActiveProfiles("local")
public class PlanAgeRatingMatrixTest {

    @Autowired
    private DocumentComposer composer;

    @MockBean
    private TemplateLoader templateLoader;

    /**
     * Build a simple template that has two header rows and a large body
     * range.  The renderer will dump our matrix into A1 so we don't care
     * what the cells contain initially.
     */
    private byte[] createAgeRatingTemplate() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("Sheet1");
            // allocate 100 rows and 30 columns so test lookups never NPE
            for (int r = 0; r < 100; r++) {
                Row row = wb.getSheetAt(0).createRow(r);
                for (int c = 0; c < 30; c++) {
                    row.createCell(c);
                }
            }
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                wb.write(baos);
                return baos.toByteArray();
            }
        }
    }

    /**
     * Helper that constructs the "comparisonMatrix" described in the
     * previous conversation.  Each plan must supply a flat list of
     * (age,rating) pairs; this method splits the ages into the three
     * required bands and returns a rectangular matrix suitable for
     * mapping into a contiguous range.
     */
    private List<List<Object>> buildAgeRatingMatrix(List<Map<String,Object>> plans,
                                                    int columnSpacing) {
        List<List<Object>> matrix = new ArrayList<>();

        // header row 1: plan names
        List<Object> hdr1 = new ArrayList<>();
        // header row 2: network / contract code
        List<Object> hdr2 = new ArrayList<>();
        // header row 3: age/rating labels
        List<Object> hdr3 = new ArrayList<>();

        for (Map<String,Object> plan : plans) {
            hdr1.add(plan.get("planName"));
            hdr2.add(plan.get("network") + " / " + plan.get("contractCode"));
            for (int i = 0; i < ((2 * 3) + columnSpacing); i++) {
                hdr1.add("");
                hdr2.add("");
            }
            for (int g = 0; g < 3; g++) {
                hdr3.add("Age");
                hdr3.add("Rating");
                for (int s = 0; s < columnSpacing; s++) hdr3.add("");
            }
        }
        matrix.add(hdr1);
        matrix.add(hdr2);
        matrix.add(hdr3);

        // body rows: build three independent vertical blocks.  Each block begins
        // at the same row so the top of every band aligns horizontally.
        
        int band1Size = 31;             // ages 0..30
        int band2Size = 17;             // ages 31..47
        int band3Size = 17;             // 48..64 (we'll render 17 rows representing 48..64+)

        int maxRows = Math.max(band1Size, Math.max(band2Size, band3Size));
        // compute lookup maps for each plan keyed by actual age
        List<Map<Integer,Object>> planMaps = plans.stream()
                .map(p -> ((List<Map<String,Object>>)p.get("ageRatings")).stream()
                        .collect(Collectors.toMap(m -> (Integer)m.get("age"), m -> m.get("rating"))))
                .collect(Collectors.toList());

        for (int rowIndex = 0; rowIndex < maxRows; rowIndex++) {
            List<Object> row = new ArrayList<>();
            for (Map<Integer,Object> map : planMaps) {
                // band1 age = rowIndex
                if (rowIndex < band1Size) {
                    int age = rowIndex;
                    row.add(age);
                    row.add(map.getOrDefault(age, ""));
                } else {
                    row.add(""); row.add("");
                }
                for (int s = 0; s < columnSpacing; s++) row.add("");

                // band2 age = 31 + rowIndex
                if (rowIndex < band2Size) {
                    int age = 31 + rowIndex;
                    row.add(age);
                    row.add(map.getOrDefault(age, ""));
                } else {
                    row.add(""); row.add("");
                }
                for (int s = 0; s < columnSpacing; s++) row.add("");

                // band3 age = 48 + rowIndex
                if (rowIndex < band3Size) {
                    int age = 48 + rowIndex;
                    row.add(age);
                    row.add(map.getOrDefault(age, ""));
                } else {
                    row.add(""); row.add("");
                }
                for (int s = 0; s < columnSpacing; s++) row.add("");
            }
            matrix.add(row);
        }
        return matrix;
    }

    /**
     * Generate age ratings for all ages in a range (e.g., 0-30, 31-47, 48-64+)
     * with ratings that increase by baseIncrementPerAge.
     */
    private List<Map<String,Object>> generateAgeRatingsForRange(int startAge, int endAge, int baseRating, int incrementPerAge) {
        List<Map<String,Object>> ratings = new ArrayList<>();
        for (int age = startAge; age <= endAge; age++) {
            int rating = baseRating + (age - startAge) * incrementPerAge;
            ratings.add(Map.of("age", age, "rating", rating));
        }
        return ratings;
    }

    @Test
    public void testAgeRatingMatrixGeneration() throws Exception {
        byte[] templateBytes = createAgeRatingTemplate();

        // Generate age ratings for all ages in each band
        // Band 1: ages 0-30 (31 ages), Band 2: ages 31-47 (17 ages), Band 3: ages 48-64 (17 ages)
        List<Map<String,Object>> plan1Ratings = new ArrayList<>();
        plan1Ratings.addAll(generateAgeRatingsForRange(0, 30, 100, 10));      // ages 0-30: 100 to 400
        plan1Ratings.addAll(generateAgeRatingsForRange(31, 47, 410, 10));     // ages 31-47: 410 to 570
        plan1Ratings.addAll(generateAgeRatingsForRange(48, 64, 600, 15));     // ages 48-64: 600 to 840

        List<Map<String,Object>> plan2Ratings = new ArrayList<>();
        plan2Ratings.addAll(generateAgeRatingsForRange(0, 30, 90, 8));        // ages 0-30: 90 to 330
        plan2Ratings.addAll(generateAgeRatingsForRange(31, 47, 340, 9));      // ages 31-47: 340 to 484
        plan2Ratings.addAll(generateAgeRatingsForRange(48, 64, 500, 12));     // ages 48-64: 500 to 692

        Map<String,Object> plan1 = Map.of(
                "planName", "Silver",
                "contractCode", "S001",
                "network", "Nat",
                "ageRatings", plan1Ratings
        );
        Map<String,Object> plan2 = Map.of(
                "planName", "Gold",
                "contractCode", "G002",
                "network", "Intl",
                "ageRatings", plan2Ratings
        );
        List<Map<String,Object>> plans = List.of(plan1, plan2);

        List<List<Object>> matrix = buildAgeRatingMatrix(plans, 1);
        assertFalse(matrix.isEmpty(), "matrix should not be empty");
        
        // Verify headers
        assertEquals("Silver", matrix.get(0).get(0));
        assertEquals("Nat / S001", matrix.get(1).get(0));
        
        // Verify band headers (Age/Rating labels in row 2)
        assertEquals("Age", matrix.get(2).get(0));
        assertEquals("Rating", matrix.get(2).get(1));
        
        // Band layout per plan (columnSpacing=1):
        // Band 1: cols 0-2 (age, rating, spacing)
        // Band 2: cols 3-5 (age, rating, spacing)
        // Band 3: cols 6-8 (age, rating, spacing)
        
        // Verify Band 1 data (ages 0-30): body starts at row 3, rowIndex 0 = age 0
        assertEquals(0, matrix.get(3).get(0), "Age 0 should be at row 3, col 0");
        assertEquals(100, matrix.get(3).get(1), "Silver age 0 rating should be 100");
        
        // Age 30: rowIndex=30 -> row 33 (3 headers + 30)
        assertEquals(30, matrix.get(33).get(0), "Age 30 should be at row 33, col 0");
        assertEquals(400, matrix.get(33).get(1), "Silver age 30 rating should be 400");
        
        // Verify Band 2 data (ages 31-47): cols 3-4
        assertEquals(31, matrix.get(3).get(3), "Age 31 (band 2) should be at row 3, col 3");
        assertEquals(410, matrix.get(3).get(4), "Silver age 31 rating should be 410");
        
        // Verify Band 3 data (ages 48-64): cols 6-7
        assertEquals(48, matrix.get(3).get(6), "Age 48 (band 3) should be at row 3, col 6");
        assertEquals(600, matrix.get(3).get(7), "Silver age 48 rating should be 600");

        // turn the matrix into request data
        Map<String,Object> data = Map.of("comparisonMatrix", matrix);

        // build a template definition (not YAML) for test
        PageSection sec = PageSection.builder()
                .sectionId("age_rating").type(com.example.demo.docgen.model.SectionType.EXCEL)
                .templatePath("age-rating-template.xlsx")
                .mappingType(MappingType.JSONPATH)
                .fieldMappings(Map.of("A1:Z100", "$.comparisonMatrix"))
                .build();
        DocumentTemplate tmpl = DocumentTemplate.builder()
                .templateId("age-rating-test")
                .sections(List.of(sec)).build();

        when(templateLoader.loadTemplate(anyString(), anyString(), anyMap())).thenReturn(tmpl);
        when(templateLoader.getResourceBytes(anyString())).thenReturn(templateBytes);

        DocumentGenerationRequest req = DocumentGenerationRequest.builder()
                .namespace("common-templates")
                .templateId("age-rating-test")
                .data(data)
                .build();

        byte[] result = composer.generateExcel(req);
        assertNotNull(result);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            // Verify headers
            assertEquals("Silver", wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("Nat / S001", wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            
            // Column layout: Band 1 (cols 0-2), Band 2 (cols 3-5), Band 3 (cols 6-8)
            // Verify Band 1: Age 0 at row 3, cols 0-1
            assertEquals(0.0, wb.getSheetAt(0).getRow(3).getCell(0).getNumericCellValue(), 
                    "Age 0 should be at row 3, col 0");
            assertEquals(100.0, wb.getSheetAt(0).getRow(3).getCell(1).getNumericCellValue(),
                    "Silver rating for age 0 should be 100");
            
            // Verify Band 1: Age 30 at row 33, cols 0-1
            assertEquals(30.0, wb.getSheetAt(0).getRow(33).getCell(0).getNumericCellValue(),
                    "Age 30 should be at row 33, col 0");
            assertEquals(400.0, wb.getSheetAt(0).getRow(33).getCell(1).getNumericCellValue(),
                    "Silver rating for age 30 should be 400");
            
            // Verify Band 2: Age 31 at row 3, cols 3-4
            assertEquals(31.0, wb.getSheetAt(0).getRow(3).getCell(3).getNumericCellValue(),
                    "Age 31 (band 2) should be at row 3, col 3");
            assertEquals(410.0, wb.getSheetAt(0).getRow(3).getCell(4).getNumericCellValue(),
                    "Silver rating for age 31 should be 410");
            
            // Verify Band 3: Age 48 at row 3, cols 6-7
            assertEquals(48.0, wb.getSheetAt(0).getRow(3).getCell(6).getNumericCellValue(),
                    "Age 48 (band 3) should be at row 3, col 6");
            assertEquals(600.0, wb.getSheetAt(0).getRow(3).getCell(7).getNumericCellValue(),
                    "Silver rating for age 48 should be 600");


            // merge header cells horizontally so network/contract spans all three bands per plan
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            int columnSpacing = 1; // must match buildAgeRatingMatrix call above
            int planWidth = 1 + (2 * 3) + columnSpacing;
            int col = 0;
            for (int i = 0; i < 2; i++) { // number of plans in test
                org.apache.poi.ss.util.CellRangeAddress r1 = new org.apache.poi.ss.util.CellRangeAddress(0, 0, col, col + planWidth - 1);
                org.apache.poi.ss.util.CellRangeAddress r2 = new org.apache.poi.ss.util.CellRangeAddress(1, 1, col, col + planWidth - 1);
                sheet.addMergedRegion(r1);
                sheet.addMergedRegion(r2);
                // center align the contents of the first cell in each merged region
                org.apache.poi.ss.usermodel.Cell cell1 = sheet.getRow(0).getCell(col);
                org.apache.poi.ss.usermodel.Cell cell2 = sheet.getRow(1).getCell(col);
                org.apache.poi.ss.usermodel.CellStyle style = sheet.getWorkbook().createCellStyle();
                style.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
                style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
                if (cell1 != null) cell1.setCellStyle(style);
                if (cell2 != null) cell2.setCellStyle(style);
                col += planWidth;
            }
            // write file for manual inspection
            java.nio.file.Path out = java.nio.file.Paths.get("docs/sample-age-rating.xlsx");
            java.nio.file.Files.createDirectories(out.getParent());
            try (java.io.OutputStream os = java.nio.file.Files.newOutputStream(out)) {
                wb.write(os);
            }
            System.out.println(">> wrote sample workbook to " + out.toAbsolutePath());
        }
    }
}
