# Excel Template Filling Developer Guide

## Overview

The document generation system provides comprehensive support for filling Excel templates with data from various sources. This guide covers all supported scenarios, configuration options, and best practices for using Excel template filling in your applications.

## Table of Contents

1. [Core Architecture](#core-architecture)
2. [Mapping Strategies](#mapping-strategies)
3. [Filling Scenarios](#filling-scenarios)
4. [Configuration Options](#configuration-options)
5. [Best Practices](#best-practices)
6. [Examples](#examples)
7. [Testing](#testing)

---

## Core Architecture

### Key Components

| Component | Role |
|-----------|------|
| **ExcelSectionRenderer** | Main renderer responsible for applying mappings to Excel workbooks |
| **DocumentComposer** | High-level API that orchestrates template loading and rendering |
| **TemplateLoader** | Loads Excel templates from classpath, filesystem, or config server |
| **FieldMappingStrategy** | Resolves data values using specified expression language (JSONPATH, etc.) |
| **PageSection** | Template definition that specifies mappings and options |
| **RenderContext** | Runtime state containing template, data, and output artifacts |

### Processing Flow

```
DocumentGenerationRequest
    ↓
TemplateLoader.loadTemplate()
    ↓
ExcelSectionRenderer.render()
    ├─ Load workbook from template
    ├─ Apply mappings (cell by cell or range-based)
    ├─ Handle repeating groups
    └─ Store result in RenderContext
    ↓
ExcelOutputService.toBytes()
    ↓
byte[] (XLSX binary data)
```

---

## Mapping Strategies

### Supported Mapping Types

#### JSONPATH (Default)

Uses JSONPath expressions to navigate and extract values from JSON/Map data structures.

**Supported Syntax:**
- `$.fieldName` - Access a field
- `$.users[0].name` - Access array element
- `$.address.street` - Nested object access
- `$.items[*].price` - Array projection (returns list)

**Example:**
```yaml
mappingType: JSONPATH
fieldMappings:
  "A1": "$.customerName"
  "B1": "$.email"
  "C2:C10": "$.benefitNames"  # Range mapping
```

---

## Filling Scenarios

### Scenario 1: Simple Single-Cell Mappings

**Use Case:** Fill individual cells with scalar values from data.

**Configuration:**
```yaml
sections:
  - sectionId: basic_info
    type: EXCEL
    templatePath: employee-form.xlsx
    mappingType: JSONPATH
    fieldMappings:
      "A1": "$.firstName"
      "B1": "$.lastName"
      "C1": "$.email"
      "D1": "$.phoneNumber"
```

**Data:**
```json
{
  "firstName": "John",
  "lastName": "Doe",
  "email": "john@example.com",
  "phoneNumber": "555-1234"
}
```

**Result:** Each cell receives the mapped value from data.

---

### Scenario 2: Range Mapping with 2D Matrices

**Use Case:** Fill a rectangular range with a 2D list/matrix (e.g., plan comparison table).

**Configuration:**
```yaml
sections:
  - sectionId: plan_comparison
    type: EXCEL
    templatePath: comparison-template.xlsx
    mappingType: JSONPATH
    fieldMappings:
      "Sheet1!A1:D5": "$.comparisonMatrix"
```

**Data Structure:**
```json
{
  "comparisonMatrix": [
    ["Plan Type", "Basic", "Premium", "Gold"],
    ["Doctor Visit", "$20 copay", "$10 copay", "Covered 100%"],
    ["Prescriptions", "$10 copay", "$5 copay", "$0 copay"],
    ["Dental", "50% after deductible", "80%", "100%"]
  ]
}
```

**Behavior:**
- Rows are filled top-to-bottom
- Columns are filled left-to-right within each row
- If data exceeds range bounds, extra data is ignored
- Cells are overwritten (unless `overwrite: false` is specified)

**Merged Cell Handling:**
- If a target cell is part of a merged region and is NOT the leading cell, it is **skipped**
- Only the top-left cell of a merged region receives values
- This prevents corrupting merged cell data

### Scenario 2b: Name Matching with Prefilled Benefit Names

**Use Case:** The Excel template already contains a column of benefit names and you want
to populate only the corresponding values. The plan data may have rows in a different
order or might be missing/extra compared to the template. Instead of blindly copying
rows by index, the renderer looks up each benefit name in the template and only writes
the value to the row with the matching name.

**Key Points:**
- A boolean flag `matchBenefitNamesInTemplate` enables this mode. It can be placed on
the `PageSection` or on an individual `FieldMappingGroup`.
- When the flag is true, the `DocumentComposer` automatically injects a values-only
  matrix (benefit column excluded) behind the scenes, so formulas in the template
  remain intact and the first column of names is not overwritten.
- The range you specify for the matrix should **exclude** the name column. For example,
  if column A holds the benefit names in the template, use `B2:D5` instead of
  `A2:D5`.
- The injected data still contains the benefit names as its first column; the renderer
  discards them when copying values. This allows the transformer to build the matrix
  normally while the renderer performs the name lookup.
- Template names are preserved (not overwritten) unless they are blank, which lets you
  prefill headings, formatting, or missing rows.

**Configuration Example:**
```yaml
sections:
  - sectionId: plan_comparison
    type: EXCEL
    templatePath: comparison-template.xlsx
    mappingType: JSONPATH
    matchBenefitNamesInTemplate: true    # enable name‑matching mode
    fieldMappings:
      "Sheet1!B2:D5": "$.comparisonMatrix"  # notice B start column
```

**Data Preparation:**
The transformer call does not need to change; you continue to use
`injectComparisonMatrix()` or `injectComparisonMatrixValuesOnly()` as before. The
renderer will ignore the first column of the resulting matrix.

**Behavior:**
- Column spacing values are still honoured, but the final matrix passed to the
  renderer has one fewer column because the name column is dropped.
- Row order of the output workbook matches the template, not the input data.
- Rows with names not found in the template are skipped; data rows with matching
  names but extra columns are truncated to the range width.

**Why use this mode?**
- Templates created by business users often contain pre‑populated benefit labels
  with styling or formulas that should not be replaced by the engine.
- It is safer when plans have optional benefits or when the two lists may diverge.

*Note:* scenario 2b is orthogonal to the values-only mode; you can enable both by
setting `valuesOnly: true` (on the template config) and `matchBenefitNamesInTemplate: true`.
The `DocumentComposer` handles both cases transparently.


### Scenario 2a: Multi-band Plan Comparison Matrix (Age/Rating Bands)

**Use Case:** Some data models require rendering multiple independent blocks (bands) of related values for each plan. For example, a set of insurance plans may provide age‑based ratings that are grouped into three ranges (0‑30, 31‑47, 48+). Each band should start at the same row so that the top of every block aligns horizontally, and the plan headers need to span all three bands.

**Strategy:**
1. **Build a rectangular matrix** where the first three rows contain the plan header, a secondary label (network/contract), and column labels (`Age`, `Rating`).
2. **Insert three vertical bands per plan** by calculating `band1Size`, `band2Size`, `band3Size` and iterating by row index rather than absolute age. This keeps the top of each band in the same worksheet row.
3. **Add spacing columns** between plans if desired (`columnSpacing`).
4. **Merge header cells** in the template or programmatically after rendering so that the primary and secondary headers span all bands, then apply a centered cell style to the leading cell of each merged region.

**Matrix construction (Java example):**
```java
private List<List<Object>> buildAgeRatingMatrix(List<Map<String,Object>> plans,
                                                int columnSpacing) {
    List<List<Object>> matrix = new ArrayList<>();

    // three header rows
    List<Object> hdr1 = new ArrayList<>();
    List<Object> hdr2 = new ArrayList<>();
    List<Object> hdr3 = new ArrayList<>();

    for (Map<String,Object> plan : plans) {
        hdr1.add(plan.get("planName"));
        hdr2.add(plan.get("network") + " / " + plan.get("contractCode"));
        for (int i = 0; i < ((2 * 3) + columnSpacing); i++) {
            hdr1.add(""); hdr2.add("");
        }
        for (int g = 0; g < 3; g++) {
            hdr3.add("Age"); hdr3.add("Rating");
            for (int s = 0; s < columnSpacing; s++) hdr3.add("");
        }
    }
    matrix.add(hdr1);
    matrix.add(hdr2);
    matrix.add(hdr3);

    int band1Size = 31;
    int band2Size = 17;
    int band3Size = 17;
    int maxRows = Math.max(band1Size, Math.max(band2Size, band3Size));

    List<Map<Integer,Object>> planMaps = plans.stream()
            .map(p -> ((List<Map<String,Object>>)p.get("ageRatings")).stream()
                    .collect(Collectors.toMap(m -> (Integer)m.get("age"), m -> m.get("rating"))))
            .collect(Collectors.toList());

    for (int rowIndex = 0; rowIndex < maxRows; rowIndex++) {
        List<Object> row = new ArrayList<>();
        for (Map<Integer,Object> map : planMaps) {
            if (rowIndex < band1Size) {
                int age = rowIndex;
                row.add(age);
                row.add(map.getOrDefault(age, ""));
            } else {
                row.add(""); row.add("");
            }
            for (int s = 0; s < columnSpacing; s++) row.add("");

            if (rowIndex < band2Size) {
                int age = 31 + rowIndex;
                row.add(age);
                row.add(map.getOrDefault(age, ""));
            } else {
                row.add(""); row.add("");
            }
            for (int s = 0; s < columnSpacing; s++) row.add("");

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
```

**Template considerations:**
- A blank worksheet is acceptable; the renderer will overwrite the range starting at the top-left cell.
- Alternatively, pre‑merge the first two header rows for each plan (e.g. A1:H1, A2:H2 for an 8‑column plan). The test sample shows how to apply merges programmatically after rendering if the template is not pre‑merged.

**YAML example mapping (range large enough to hold matrix):**
```yaml
sections:
  - sectionId: age_rating
    type: EXCEL
    templatePath: age-rating-template.xlsx
    mappingType: JSONPATH
    fieldMappings:
      "A1:Z100": "$.comparisonMatrix"
```

**Testing:**
- See `PlanAgeRatingMatrixTest` in the test suite for a full Spring Boot integration example, including merge and centre styling logic.

---

---

### Scenario 3: Values-Only Mapping (Preserves Formulas)

**Use Case:** Fill worksheet with data values while preserving formula cells.

> **Note:** the values-only transformer is also automatically applied when
> `matchBenefitNamesInTemplate` (name-matching) mode is enabled. In that case the
> first column (benefit names) is removed from the range before values are written.

**Challenge:** Some templates contain formula cells (e.g., totals, calculations) in the target range that should not be overwritten.

**Solution:** Use the values-only transformer to skip formula cells.

**Configuration:**
```yaml
sections:
  - sectionId: plan_matrix
    type: EXCEL
    templatePath: comparison-template.xlsx
    mappingType: JSONPATH
    fieldMappings:
      "Sheet1!B2:D4": "$.comparisonMatrixValues"
```

**Key Implementation Details:**

1. **Detection:** Before filling a cell, the renderer checks `cell.getCellType() == CellType.FORMULA`
2. **Behavior:** Formula cells are **skipped** (not overwritten)
3. **Data Preparation:** Use `PlanComparisonTransformer.injectComparisonMatrixValuesOnly()` to prepare data:

```java
Map<String, Object> enriched = PlanComparisonTransformer.injectComparisonMatrixValuesOnly(
    originalData,
    plansList
);
// Result: enriched.comparisonMatrixValues contains only values, not formulas
```

**Template Setup Example:**
```
Row 1: [Benefit]     [Basic]         [Premium]       [Gold]
Row 2: [Doctor Visit] =SUM(1,1)      [formula cell]  [formula cell]
Row 3: [Prescriptions] [$10 copay]   [$5 copay]      [$0 copay]
Row 4: [Dental]      [50% after DD]  [80%]           [100%]
```

**Processing:**
- Formula cells (Row 2, columns B, C, D) are detected and skipped
- Data cells are filled normally
- Original formulas remain intact in the output

---

### Scenario 4: Repeating Groups - Table Mode

**Use Case:** Populate a table with a dynamic list of rows (employees, items, benefits, etc.).

**Configuration:**
```yaml
fieldMappingGroups:
  - mappingType: JSONPATH
    basePath: "$.employees"
    repeatingGroup:
      startCell: "Sheet1!A5"           # First data row (after headers)
      insertRows: false                # Use existing rows, don't insert new ones
      overwrite: true                  # Overwrite existing values
      maxItems: 100                    # Limit rows to 100
      fields:
        "A": "name"                    # Column A <- name field
        "B": "salary"                  # Column B <- salary field
        "C": "department"              # Column C <- department field
```

**Data:**
```json
{
  "employees": [
    { "name": "Alice Johnson", "salary": 95000, "department": "Engineering" },
    { "name": "Bob Smith", "salary": 85000, "department": "Engineering" },
    { "name": "Carol Davis", "salary": 75000, "department": "Sales" }
  ]
}
```

**Result:**
```
Row 4: Name               | Salary  | Department
Row 5: Alice Johnson      | 95000   | Engineering
Row 6: Bob Smith          | 85000   | Engineering
Row 7: Carol Davis        | 75000   | Sales
```

**Key Options:**

- **insertRows: false** (Recommended for Excel tables)
  - Uses existing worksheet rows
  - Does not insert new rows
  - Useful when template has pre-allocated space

- **insertRows: true**
  - Inserts new rows as needed
  - Useful for dynamic lists without pre-allocated rows
  - More complex to manage merged regions

- **overwrite: true** (Default)
  - All cells in the target range are written
  - Replaces any pre-existing template content

- **overwrite: false**
  - Only writes to empty cells
  - Preserves pre-populated template values
  - Useful for partial data updates

**startCell Format:**
```
Unbounded: "A5"              # Row 5, column A (any sheet)
Bounded:   "Sheet1!A5"       # Explicit sheet + cell reference
```

---

### Scenario 5: Repeating Groups - Numbered Field Mode

**Use Case:** Generate indexed field names for PDF AcroForm repeating fields.

**Configuration:**
```yaml
fieldMappingGroups:
  - mappingType: JSONPATH
    basePath: "$.children"
    repeatingGroup:
      # Note: NO startCell specified (triggers numbered field mode)
      prefix: "child"                        # Field name prefix
      indexPosition: BEFORE_FIELD            # child1_firstName or childFirstName1
      indexSeparator: "_"                    # Separator between prefix and index
      startIndex: 1                          # Start numbering from 1
      maxItems: 5
      fields:
        "firstName": "$.firstName"           # Generates: child1_firstName, child2_firstName, ...
        "age": "$.age"                       # Generates: child1_age, child2_age, ...
```

**Data:**
```json
{
  "children": [
    { "firstName": "Emma", "age": 8 },
    { "firstName": "Liam", "age": 6 }
  ]
}
```

**Generated Mappings:**
```
child1_firstName ← Emma
child1_age       ← 8
child2_firstName ← Liam
child2_age       ← 6
```

**Use Cases:**
- PDF form repeating sections (not direct Excel cell writes)
- Legacy form field naming conventions
- Integration with PDF form readers

**Note:** In Excel mode, numbered fields don't directly map to cells; they're used for context and can be processed by custom renderers or PDF handlers.

---

### Scenario 6: Numeric Value Handling

**Behavior:**
The renderer automatically parses and converts numeric strings to Excel numeric cell types.

**Example:**
```json
{
  "age": "30",              // String in JSON
  "salary": "50000.50"      // String in JSON
}
```

**Result in Excel:**
- `age` cell: Numeric type, value = 30.0
- `salary` cell: Numeric type, value = 50000.5
- Both can be used in formulas

**Detection Logic:**
1. Attempt to parse value as Double
2. If successful, set cell as numeric type
3. If parsing fails, set as string type

---

### Scenario 7: Sheet-Qualified References

**Use Case:** Target specific sheets in multi-sheet workbooks.

**Configuration:**
```yaml
fieldMappings:
  "Sheet1!A1": "$.summary"
  "DetailPage!B5:D10": "$.detailMatrix"
  "Summary!C2": "$.totalAmount"
```

**Format:**
```
SheetName!CellRange

Examples:
- Sheet1!A1
- MySheet!B2:C5
- "Employee Data"!A10
- (quotes needed if sheet name contains spaces)
```

**Behavior:**
- If sheet doesn't exist, an error is raised
- If unqualified (e.g., just "A1"), defaults to the first sheet
- Useful for distributing data across multiple sheets

---

## Configuration Options

### PageSection Configuration

All mapping options are defined in the `PageSection` model:

```java
@Data
@Builder
public class PageSection {
    private String sectionId;                           // Unique identifier
    private SectionType type;                           // EXCEL, ACROFORM, FREEMARKER
    private String templatePath;                        // Path to Excel file
    private MappingType mappingType;                    // JSONPATH, etc.
    private Map<String, String> fieldMappings;          // Single-cell/range mappings
    private List<FieldMappingGroup> fieldMappingGroups; // Repeating group configs
    private boolean overwrite;                          // Overwrite existing values
    private Boolean matchBenefitNamesInTemplate;        // If true, name-based row matching mode
    private int order;                                  // Rendering order
    private String condition;                           // Conditional rendering
}
```

### FieldMappingGroup Configuration

The `FieldMappingGroup` model is used when you need repeating groups. It mirrors
many of the same settings as `PageSection`, including the ability to toggle
name‑matching on a per‑group basis:

```java
@Data
@Builder
public class FieldMappingGroup {
    private MappingType mappingType;
    private String basePath;
    private RepeatingGroupConfig repeatingGroup;
    private Boolean matchBenefitNamesInTemplate; // can be set here when groups are used
    // ... other fields omitted for brevity
}
```

### RepeatingGroupConfig

```java
@Data
@Builder
public class RepeatingGroupConfig {
    private String startCell;                           // "Sheet1!A5" or "A5"
    private boolean insertRows;                         // Insert vs. use existing rows
    private boolean overwrite;                          // Overwrite existing cell values
    private int maxItems;                               // Maximum rows to process
    private String prefix;                              // Field name prefix
    private String indexSeparator;                      // Separator for indexed names
    private IndexPosition indexPosition;                // BEFORE_FIELD or AFTER_FIELD
    private int startIndex;                             // Starting index (1 or 0)
    private Map<String, String> fields;                 // Field -> JSONPath mappings
}

enum IndexPosition {
    BEFORE_FIELD,   // child1_firstName
    AFTER_FIELD     // childFirstName1
}
```

### Overwrite Flag Details

| Setting | Behavior |
|---------|----------|
| `overwrite: true` (default) | All target cells are written; existing content is replaced |
| `overwrite: false` | Only empty cells are written; preserves non-blank cells |

**Use Cases:**
- `overwrite: true`: Bulk data replacement, fresh template fill
- `overwrite: false`: Partial updates, preserving user-entered data

---

## Best Practices

### 1. Template Design

✅ **DO:**
- Pre-allocate rows for repeating groups (makes `insertRows: false` safe)
- Use clear, descriptive section IDs
- Place headers in separate rows from data
- Document template structure in template file or YAML comments
- If you pre-fill benefit or item names, enable `matchBenefitNamesInTemplate`
  and leave the name column outside of your range

❌ **DON'T:**
- Rely on dynamic row insertion unless necessary
- Mix data and formula cells without planning for `overwrite: false`
- Use complex merged regions in data areas

### 2. Data Preparation

✅ **DO:**
- Use `PlanComparisonTransformer` for matrix transformations
- Validate data structure matches expected JSONPath expressions
- Use type-safe builders (e.g., `PageSection.builder()`)
- Separate template configuration from runtime data

❌ **DON'T:**
- Pass unstructured data expecting automatic conversion
- Mix scalar and list values in the same mapping
- Assume cell types will be preserved across overwrites

### 3. Range Mapping

✅ **DO:**
- Ensure 2D data matches range dimensions
- Use `Sheet1!A1:D5` syntax for explicit sheet targeting
- Document range boundaries in comments

❌ **DON'T:**
- Assume data larger than range will be silently truncated (verify bounds)
- Mix merged regions and range mapping without testing
- Use unbounded ranges with dynamic-sized data

### 4. Repeating Groups

✅ **DO:**
- Use table mode (`startCell`) for Excel data tables
- Use numbered field mode for PDF AcroForm integration
- Set `maxItems` to prevent accidental overflow
- Test with boundary conditions (0 items, 1 item, max items)

❌ **DON'T:**
- Exceed pre-allocated rows without `insertRows: true`
- Mix table and numbered field modes in same section
- Forget to adjust `startCell` if adding/removing header rows

### 5. Formula Preservation

✅ **DO:**
- Use values-only transformer when template contains calculation cells
- Test rendered output to ensure formulas remain intact
- Document which cells contain formulas in template comments

❌ **DON'T:**
- Manually edit formula cells after rendering (use template instead)
- Assume `overwrite: false` will preserve formulas (use values-only transformer)
- Mix formula and data cells in same column without planning

### 6. Testing

✅ **DO:**
- Write unit tests for each mapping scenario
- Use `@SpringBootTest` for integration tests with real templates
- Generate sample output files to docs/ for visual inspection
- Test edge cases: empty data, max items, merged cells

❌ **DON'T:**
- Test only happy path scenarios
- Assume unit tests cover integration behavior
- Skip testing with actual Excel templates

---

## Examples

### Example 1: Employee Directory

**Template:** `employee-directory.xlsx`
```
Column A: Name
Column B: Email
Column C: Department
Column D: Salary
```

**YAML Configuration:**
```yaml
templateId: employee-directory
sections:
  - sectionId: employee_list
    type: EXCEL
    templatePath: employee-directory.xlsx
    mappingType: JSONPATH
    fieldMappingGroups:
      - basePath: "$.employees"
        repeatingGroup:
          startCell: "A3"
          insertRows: false
          overwrite: true
          maxItems: 1000
          fields:
            "A": "name"
            "B": "email"
            "C": "department"
            "D": "salary"
```

**Java Code:**
```java
DocumentGenerationRequest req = DocumentGenerationRequest.builder()
    .namespace("common-templates")
    .templateId("employee-directory")
    .data(Map.of(
        "employees", List.of(
            Map.of("name", "Alice", "email", "alice@co.com", "department", "Engineering", "salary", 95000),
            Map.of("name", "Bob", "email", "bob@co.com", "department", "Sales", "salary", 75000)
        )
    ))
    .build();

byte[] xlsx = documentComposer.generateExcel(req);
```

---

### Example 2: Insurance Plan Comparison

**Template:** `plan-comparison.xlsx`
```
Row 1: Headers (Plan names in columns B, C, D)
Row 2: Subheaders (Group names)
Rows 3+: Benefit rows (Benefit name in column A, values in B, C, D)
```

**YAML Configuration:**
```yaml
templateId: plan-comparison
config:
  columnSpacing: 1
  valuesOnly: true           # preserve any formulas in the template
# matchBenefitNamesInTemplate may also be set here or on a section/group:
# launch name‑matching mode if template already has a first column of names
sections:
  - sectionId: comparison_matrix
    type: EXCEL
    templatePath: comparison-template.xlsx
    mappingType: JSONPATH
    matchBenefitNamesInTemplate: true      # optional, see discussion above
    fieldMappings:
      "B2:D5": "$.comparisonMatrixValues"  # note range excludes A (names)
```

**Data Preparation:**
```java
// Load original plan data
List<Plan> plans = loadPlans();

// Transform to values-only matrix
Map<String, Object> data = new HashMap<>();
data.put("plans", plans);
Map<String, Object> enriched = PlanComparisonTransformer
    .injectComparisonMatrixValuesOnly(data, plans);

DocumentGenerationRequest req = DocumentGenerationRequest.builder()
    .namespace("common-templates")
    .templateId("plan-comparison")
    .data(enriched)
    .build();

byte[] xlsx = documentComposer.generateExcel(req);
```

**Key Features:**
- Formula cells in template are preserved
- Only data values are written to the matrix
- Formulas can reference data cells for calculations

---

### Example 3: Multi-Sheet Workbook

**YAML Configuration:**
```yaml
templateId: annual-report
sections:
  - sectionId: summary_page
    type: EXCEL
    templatePath: annual-report.xlsx
    mappingType: JSONPATH
    fieldMappings:
      "Summary!A1": "$.companyName"
      "Summary!A2": "$.year"
      "Summary!B5:D10": "$.quarterlyResults"
  
  - sectionId: detail_page
    type: EXCEL
    templatePath: annual-report.xlsx
    mappingType: JSONPATH
    fieldMappings:
      "DetailedResults!A1": "$.reportDate"
      "DetailedResults!A3:E100": "$.monthlyData"
```

**Behavior:**
- First section loads template and fills Summary sheet
- Second section uses same workbook and fills DetailedResults sheet
- Both operations update the same in-memory workbook
- Final output includes both sheets

---

### Example 4: Conditional Rendering

**YAML Configuration:**
```yaml
templateId: conditional-report
sections:
  - sectionId: executive_summary
    type: EXCEL
    templatePath: report.xlsx
    condition: "$.includeExecutiveSummary"
    mappingType: JSONPATH
    fieldMappings:
      "A1": "$.summaryText"
  
  - sectionId: detailed_data
    type: EXCEL
    templatePath: report.xlsx
    condition: "$.detailedData != null && $.detailedData.length > 0"
    mappingType: JSONPATH
    fieldMappings:
      "DataTable!A1:D50": "$.detailedData"
```

**Behavior:**
- Executive summary section is rendered only if `includeExecutiveSummary` is true
- Detailed data section is rendered only if data is not null/empty
- Conditions are evaluated at render time
- Sections are processed in order

---

## Testing

### Unit Testing

**Test File:** `ExcelSectionRendererTest.java`

```java
@Test
public void testTablePopulationFromRepeatingGroup() throws Exception {
    // Setup template
    Path template = createTestTemplate("table.xlsx");
    when(templateLoader.getResourceBytes(anyString())).thenReturn(readBytes(template));

    // Setup data
    List<Map<String, Object>> employees = List.of(
        Map.of("name", "Alice", "age", 30),
        Map.of("name", "Bob", "age", 40)
    );

    // Configure repeating group
    RepeatingGroupConfig repeating = RepeatingGroupConfig.builder()
        .startCell("A2")
        .insertRows(false)
        .overwrite(true)
        .fields(Map.of("A", "name", "B", "age"))
        .build();

    FieldMappingGroup group = FieldMappingGroup.builder()
        .mappingType(MappingType.JSONPATH)
        .basePath("$.employees")
        .repeatingGroup(repeating)
        .build();

    PageSection section = PageSection.builder()
        .sectionId("test")
        .type(SectionType.EXCEL)
        .templatePath("table.xlsx")
        .fieldMappingGroups(List.of(group))
        .build();

    RenderContext context = new RenderContext(null, Map.of("employees", employees));
    renderer.render(section, context);

    // Verify
    Workbook result = context.getMetadata("excelWorkbook");
    assertEquals("Alice", result.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
}
```

### Integration Testing

**Test File:** `ExcelRendererIntegrationTest.java`

```java
@SpringBootTest
@ActiveProfiles("local")
public class ExcelRendererIntegrationTest {
    @Autowired
    private DocumentComposer composer;
    
    @MockBean
    private TemplateLoader templateLoader;

    @Test
    public void generateValuesOnlyWorkbook() throws Exception {
        // Prepare data
        Map<String, Object> data = loadSampleData();
        data = PlanComparisonTransformer.injectComparisonMatrixValuesOnly(data, plans);

        // Build template
        DocumentTemplate template = DocumentTemplate.builder()
            .templateId("values-only-test")
            .sections(List.of(
                PageSection.builder()
                    .sectionId("test")
                    .type(SectionType.EXCEL)
                    .templatePath("comparison.xlsx")
                    .fieldMappings(Map.of("B2:D4", "$.comparisonMatrixValues"))
                    .build()
            ))
            .build();

        when(templateLoader.loadTemplate(anyString(), anyString(), anyMap()))
            .thenReturn(template);
        when(templateLoader.getResourceBytes(anyString()))
            .thenReturn(createTestTemplate());

        // Execute
        DocumentGenerationRequest req = DocumentGenerationRequest.builder()
            .namespace("common-templates")
            .templateId("values-only-test")
            .data(data)
            .build();

        byte[] result = composer.generateExcel(req);
        assertTrue(result.length > 0);
        
        // Optional: write to file for manual inspection
        Files.write(Paths.get("docs/sample-output.xlsx"), result);
    }
}
```

### Manual Testing Checklist

- [ ] Generated Excel file opens without errors
- [ ] All expected cells contain correct values
- [ ] Formulas are preserved (use `=` prefix to identify)
- [ ] Cell types match expectation (numeric cells are numbers, not strings)
- [ ] Merged regions are intact
- [ ] No missing rows or columns
- [ ] Multiple sheets (if applicable) are all populated
- [ ] Conditional sections render correctly based on data

---

## Troubleshooting

### Issue: "Cannot get a STRING value from a NUMERIC cell"

**Cause:** Cell contains numeric data but code expects string.

**Solution:**
```java
Cell cell = row.getCell(colIndex);
Object value;
if (cell.getCellType() == CellType.NUMERIC) {
    value = cell.getNumericCellValue();
} else {
    value = cell.getStringCellValue();
}
```

### Issue: Formula cells are being overwritten

**Cause:** `overwrite: true` is set or values-only transformer is not used.

**Solution:**
1. Add values-only transformer to data preparation
2. OR set `overwrite: false` in repeating group config
3. Verify formula cells are in target range

### Issue: Merged cells are corrupted

**Cause:** Non-leading cells in merged region are being written.

**Solution:**
- The renderer now automatically skips non-leading merged cells
- Verify template has correct merge setup
- Test with sample data

### Issue: Data exceeds range bounds

**Cause:** 2D data has more rows/columns than range allows.

**Solution:**
- Increase range size: `A1:Z1000` instead of `A1:D10`
- Use repeating groups with `insertRows: true` for dynamic sizing
- Split large datasets across multiple sheets

### Issue: Sheet-qualified reference not found

**Cause:** Sheet name is misspelled or doesn't exist.

**Solution:**
- Verify sheet name matches exactly (case-sensitive)
- Use quotes if sheet name contains spaces: `"Sheet Name"!A1`
- List sheets in template using Excel

---

## API Reference

### DocumentComposer

```java
/**
 * Generate an Excel workbook from a template and data.
 * Auto-transforms plan data if template is plan-comparison.
 */
public byte[] generateExcel(DocumentGenerationRequest request) throws Exception;
```

**Parameters:**
- `request.namespace`: Template namespace (e.g., "common-templates")
- `request.templateId`: Template identifier
- `request.data`: Runtime data (Map or POJO)

**Returns:** XLSX binary data (can be written to file or HTTP response)

### ExcelSectionRenderer

```java
/**
 * Render Excel section with mapped data.
 * Stores resulting workbook in RenderContext metadata under "excelWorkbook".
 */
public void render(PageSection section, RenderContext context) throws Exception;
```

### PlanComparisonTransformer

```java
/**
 * Inject plan data as 2D comparison matrix into data.
 * Auto-extracts benefit names and builds header/data rows.
 */
public static Map<String, Object> injectComparisonMatrix(
    Map<String, Object> data,
    List<Map<String, Object>> plans,
    int columnSpacing
) throws Exception;

/**
 * Inject values-only matrix (skips formula cells).
 * Use when template contains formulas in benefit value cells.
 */
public static Map<String, Object> injectComparisonMatrixValuesOnly(
    Map<String, Object> data,
    List<Map<String, Object>> plans
) throws Exception;
```

---

## Related Documentation

- [YAML Template Configuration](./CONFIGURATION_GUIDE.md)
- [Plan Comparison Guide](./PLAN_COMPARISON_GUIDE.md)
- [Excel Generation Test Suite](./EXCEL_GENERATION_TEST_SUITE.md)
- [Namespace Architecture](./NAMESPACE_GUIDE.md)

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-02-26 | Initial guide: single-cell, range, table, numbered field, values-only scenarios |

---

## Contributing

To add new examples or improve documentation:
1. Update relevant section in this guide
2. Add/update tests in `ExcelSectionRendererTest.java` or `ExcelRendererIntegrationTest.java`
3. Generate sample output files to `docs/`
4. Include code examples and expected results

---

## Support

For issues or questions:
1. Check the [Troubleshooting](#troubleshooting) section
2. Review related code in `ExcelSectionRenderer.java`
3. Examine test cases in `ExcelSectionRendererTest.java`
4. Refer to YAML template examples in `src/main/resources/common-templates/templates/`
