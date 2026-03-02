I want to use excel templates for a healthcare insurance domain which would include sheets for proposed plan comparison against list of benefits and also rating schedule for different products and plans. How could I fill those templates with value from json data using as spring boot app .


Here's a comprehensive guide on how to build a Spring Boot application that fills Excel templates for healthcare insurance use cases.

---

## Architecture Overview

The approach uses **Apache POI** (the standard Java library for Excel) with **named cell ranges** or **placeholder-based** template filling. Your Spring Boot app reads JSON data, maps it to a model, then writes values into pre-defined template cells.

---

## 1. Dependencies (pom.xml)

```xml
<dependencies>
    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Apache POI for Excel -->
    <dependency>
        <groupId>org.apache.poi</groupId>
        <artifactId>poi-ooxml</artifactId>
        <version>5.2.5</version>
    </dependency>

    <!-- Jackson for JSON -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>

    <!-- Lombok (optional, reduces boilerplate) -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

---

## 2. Domain Models

```java
// Plan comparison model
@Data
public class InsurancePlan {
    private String planName;
    private String carrier;
    private String planType;         // HMO, PPO, HDHP
    private BigDecimal premium;
    private BigDecimal deductible;
    private BigDecimal outOfPocketMax;
    private List<BenefitRow> benefits;
}

@Data
public class BenefitRow {
    private String benefitName;      // e.g., "Primary Care Visit"
    private String inNetworkCoverage;
    private String outOfNetworkCoverage;
    private String priorAuthRequired;
}

// Rating schedule model
@Data
public class RatingSchedule {
    private String productName;
    private String effectiveDate;
    private List<RateRow> rates;
}

@Data
public class RateRow {
    private String tier;             // e.g., "Employee Only"
    private int ageFrom;
    private int ageTo;
    private BigDecimal monthlyRate;
    private BigDecimal annualRate;
}
```

---

## 3. Template Strategy: Named Ranges vs. Placeholder Cells

There are two solid approaches. **Named ranges** is cleaner for enterprise use.

### Option A — Named Ranges (Recommended)
Define named cells in your Excel template (e.g., `PLAN_NAME_1`, `PREMIUM_1`) via Excel's Name Manager. Then in code:

```java
@Service
public class ExcelTemplateService {

    private static final String PLAN_COMPARISON_TEMPLATE = "templates/plan_comparison.xlsx";
    private static final String RATING_SCHEDULE_TEMPLATE = "templates/rating_schedule.xlsx";

    public byte[] fillPlanComparisonTemplate(List<InsurancePlan> plans) throws IOException {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream(PLAN_COMPARISON_TEMPLATE);
             Workbook wb = WorkbookFactory.create(is)) {

            Sheet sheet = wb.getSheet("Plan Comparison");

            // Fill plan headers using named ranges
            for (int i = 0; i < plans.size(); i++) {
                InsurancePlan plan = plans.get(i);
                int col = 2 + i; // columns C, D, E for plans 1, 2, 3

                setNamedCell(wb, "PLAN_NAME_" + (i + 1), plan.getPlanName());
                setNamedCell(wb, "CARRIER_" + (i + 1), plan.getCarrier());
                setNamedCell(wb, "PREMIUM_" + (i + 1), plan.getPremium());
                setNamedCell(wb, "DEDUCTIBLE_" + (i + 1), plan.getDeductible());
                setNamedCell(wb, "OOP_MAX_" + (i + 1), plan.getOutOfPocketMax());

                // Fill benefit rows
                fillBenefitRows(sheet, plan.getBenefits(), col);
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private void setNamedCell(Workbook wb, String rangeName, Object value) {
        Name name = wb.getName(rangeName);
        if (name == null) return;

        AreaReference ref = new AreaReference(name.getRefersToFormula(),
                SpreadsheetVersion.EXCEL2007);
        CellReference cellRef = ref.getFirstCell();

        Sheet sheet = wb.getSheet(cellRef.getSheetName());
        Row row = sheet.getRow(cellRef.getRow());
        if (row == null) row = sheet.createRow(cellRef.getRow());
        Cell cell = row.getCell(cellRef.getCol());
        if (cell == null) cell = row.createCell(cellRef.getCol());

        if (value instanceof BigDecimal || value instanceof Double) {
            cell.setCellValue(((Number) value).doubleValue());
        } else if (value instanceof Integer) {
            cell.setCellValue((Integer) value);
        } else {
            cell.setCellValue(value != null ? value.toString() : "");
        }
    }

    private void fillBenefitRows(Sheet sheet, List<BenefitRow> benefits, int planCol) {
        // Assumes row 8 is the first benefit row in the template
        int startRow = 7; // 0-indexed
        for (int i = 0; i < benefits.size(); i++) {
            BenefitRow benefit = benefits.get(i);
            Row row = sheet.getRow(startRow + i);
            if (row == null) row = sheet.createRow(startRow + i);

            // Column A = benefit name (only write once for first plan)
            if (planCol == 2) {
                setCellValue(row, 0, benefit.getBenefitName());
            }
            setCellValue(row, planCol, benefit.getInNetworkCoverage());
            setCellValue(row, planCol + 1, benefit.getOutOfNetworkCoverage());
            setCellValue(row, planCol + 2, benefit.getPriorAuthRequired());
        }
    }
}
```

### Option B — Placeholder Substitution
Use `{{PLAN_NAME}}` style tokens in cells and replace them via iteration:

```java
public void replacePlaceholders(Sheet sheet, Map<String, String> data) {
    for (Row row : sheet) {
        for (Cell cell : row) {
            if (cell.getCellType() == CellType.STRING) {
                String val = cell.getStringCellValue();
                for (Map.Entry<String, String> entry : data.entrySet()) {
                    if (val.contains("{{" + entry.getKey() + "}}")) {
                        val = val.replace("{{" + entry.getKey() + "}}", entry.getValue());
                    }
                }
                cell.setCellValue(val);
            }
        }
    }
}
```

---

## 4. Rating Schedule Filler

```java
public byte[] fillRatingScheduleTemplate(RatingSchedule schedule) throws IOException {
    try (InputStream is = getClass().getClassLoader()
            .getResourceAsStream(RATING_SCHEDULE_TEMPLATE);
         Workbook wb = WorkbookFactory.create(is)) {

        Sheet sheet = wb.getSheet("Rating Schedule");

        // Fill header metadata
        setNamedCell(wb, "PRODUCT_NAME", schedule.getProductName());
        setNamedCell(wb, "EFFECTIVE_DATE", schedule.getEffectiveDate());

        // Fill rate rows starting at a defined anchor row
        int dataStartRow = findAnchorRow(sheet, "##DATA_START##"); // marker cell
        List<RateRow> rates = schedule.getRates();

        // If template has fewer rows than data, insert rows
        if (rates.size() > 1) {
            sheet.shiftRows(dataStartRow + 1, sheet.getLastRowNum(), rates.size() - 1);
        }

        for (int i = 0; i < rates.size(); i++) {
            RateRow rate = rates.get(i);
            Row row = sheet.getRow(dataStartRow + i);
            if (row == null) row = sheet.createRow(dataStartRow + i);

            setCellValue(row, 0, rate.getTier());
            setCellValue(row, 1, rate.getAgeFrom());
            setCellValue(row, 2, rate.getAgeTo());
            setCellValue(row, 3, rate.getMonthlyRate().doubleValue());
            // Annual rate as a formula referencing monthly
            row.createCell(4).setCellFormula("D" + (dataStartRow + i + 1) + "*12");
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        return bos.toByteArray();
    }
}

private int findAnchorRow(Sheet sheet, String marker) {
    for (Row row : sheet) {
        for (Cell cell : row) {
            if (cell.getCellType() == CellType.STRING 
                    && marker.equals(cell.getStringCellValue())) {
                return row.getRowNum();
            }
        }
    }
    return 5; // fallback default
}
```

---

## 5. REST Controller

```java
@RestController
@RequestMapping("/api/insurance/excel")
public class ExcelReportController {

    @Autowired private ExcelTemplateService excelService;
    @Autowired private ObjectMapper objectMapper;

    @PostMapping("/plan-comparison")
    public ResponseEntity<byte[]> generatePlanComparison(
            @RequestBody List<InsurancePlan> plans) throws IOException {

        byte[] excelBytes = excelService.fillPlanComparisonTemplate(plans);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=plan_comparison.xlsx")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }

    @PostMapping("/rating-schedule")
    public ResponseEntity<byte[]> generateRatingSchedule(
            @RequestBody RatingSchedule schedule) throws IOException {

        byte[] excelBytes = excelService.fillRatingScheduleTemplate(schedule);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=rating_schedule.xlsx")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }
}
```

---

## 6. Sample JSON Input

**Plan Comparison:**
```json
[
  {
    "planName": "Gold PPO 500",
    "carrier": "BlueCross",
    "planType": "PPO",
    "premium": 650.00,
    "deductible": 500.00,
    "outOfPocketMax": 4000.00,
    "benefits": [
      {
        "benefitName": "Primary Care Visit",
        "inNetworkCoverage": "$20 copay",
        "outOfNetworkCoverage": "40% after deductible",
        "priorAuthRequired": "No"
      },
      {
        "benefitName": "Specialist Visit",
        "inNetworkCoverage": "$40 copay",
        "outOfNetworkCoverage": "40% after deductible",
        "priorAuthRequired": "Yes"
      }
    ]
  }
]
```

**Rating Schedule:**
```json
{
  "productName": "Group Medical Plan A",
  "effectiveDate": "2025-01-01",
  "rates": [
    { "tier": "Employee Only", "ageFrom": 18, "ageTo": 29, "monthlyRate": 420.00 },
    { "tier": "Employee Only", "ageFrom": 30, "ageTo": 39, "monthlyRate": 510.00 },
    { "tier": "Employee + Spouse", "ageFrom": 18, "ageTo": 29, "monthlyRate": 840.00 }
  ]
}
```

---

## 7. Key Design Recommendations

**Template design:** Store `.xlsx` templates in `src/main/resources/templates/`. Use Excel's Name Manager to define named ranges for all dynamic cells — this decouples your code from specific row/column positions, making templates maintainable without code changes.

**Cell style preservation:** When writing to template cells, avoid creating new cells unnecessarily. Copy the existing cell's `CellStyle` to maintain formatting:
```java
CellStyle existingStyle = cell.getCellStyle();
cell.setCellValue(newValue);
cell.setCellStyle(existingStyle); // preserve formatting
```

**Row insertion for variable-length data:** Use `sheet.shiftRows()` for the rating schedule where the number of age bands varies. Copy row styles from a template row to maintain formatting on inserted rows.

**Formula preservation:** Don't open templates with `data_only=true` — this destroys formulas. Any summary rows (totals, averages) in the template will recalculate automatically when opened in Excel.

**Multiple products/plans:** For scenarios with many plans, consider generating separate sheets per product within the same workbook using `wb.cloneSheet()` from a hidden master template sheet.

