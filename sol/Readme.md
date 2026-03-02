# Excel Template Engine — Config-Driven Approach
## Healthcare Insurance Domain (Spring Boot + Apache POI)

---

## Architecture Overview

```
HTTP Request (JSON)
       │
       ▼
ExcelReportController
       │  templateId + JsonNode
       ▼
ExcelReportService
       │
       ├──► MappingConfigLoader ──► *-mapping.yml (YAML from classpath)
       │
       ├──► ClassPathResource    ──► *.xlsx template file
       │
       ▼
TemplateFillEngine
       │
       ├──► JsonValueResolver    ──► JsonPath / dot-notation field access
       │
       └──► CellValueWriter      ──► Typed cell writes (currency, %, date...)
```

**Key principle**: Zero code changes required to add or modify templates.
Only YAML mapping files and Excel template files need to be updated.

---

## Project Structure

```
src/main/
├── java/com/insurance/excel/
│   ├── config/
│   │   └── MappingConfigLoader.java      ← Auto-loads all *-mapping.yml at startup
│   ├── controller/
│   │   └── ExcelReportController.java    ← REST endpoints
│   ├── model/
│   │   └── TemplateMappingConfig.java    ← POJO representation of YAML structure
│   ├── resolver/
│   │   ├── JsonValueResolver.java        ← JsonPath + dot-notation field access
│   │   └── CellValueWriter.java          ← Typed Apache POI cell writer
│   └── service/
│       ├── ExcelReportService.java       ← High-level orchestrator
│       └── TemplateFillEngine.java       ← Core fill logic (4 mapping strategies)
│
└── resources/
    ├── application.yml
    ├── mappings/
    │   ├── plan-comparison-mapping.yml   ← Mapping config for plan comparison
    │   └── rating-schedule-mapping.yml   ← Mapping config for rating schedule
    └── templates/
        ├── plan_comparison.xlsx          ← Excel template (you provide this)
        └── rating_schedule.xlsx          ← Excel template (you provide this)
```

---

## YAML Mapping Config Reference

Every YAML file in `resources/mappings/` is auto-discovered. The structure:

```yaml
template:
  id: my-template           # Must be unique — used as the API key
  file: templates/my.xlsx   # Classpath location of the .xlsx template
  description: "..."

sheets:
  - name: "Sheet1"          # Must match the Excel tab name exactly
    mappings:
      - id: field_id        # Unique within the sheet (for logging/debugging)
        type: CELL | CELL_EXPR | LIST | MULTI_COL
        # ... type-specific fields below
```

### Mapping Types

#### `CELL` — Scalar value into a single cell
```yaml
- id: effective_date
  type: CELL
  cell: B3                         # Excel cell reference
  jsonPath: "$.effectiveDate"      # JsonPath expression from root
  dataType: DATE                   # STRING | NUMBER | CURRENCY | PERCENTAGE | DATE | BOOLEAN
  format: "MM/dd/yyyy"             # Optional format pattern
```

#### `CELL_EXPR` — Expression result into a single cell
```yaml
- id: plan_count
  type: CELL_EXPR
  cell: C4
  expression: "$.plans[*].size()"  # Currently supports *.size() and direct JsonPath
  dataType: NUMBER
```

#### `LIST` — JSON array → repeating rows
Each item in the array maps to one row. Columns within the row are defined per item field.
```yaml
- id: benefit_rows
  type: LIST
  jsonPath: "$.benefits[*]"
  startRow: 26                     # 1-based row where data starts
  rowStepSize: 1                   # Rows between each item (use 2 for alternating rows)
  preserveRowStyle: true           # Copy cell style from template's startRow
  insertRowsIfNeeded: true         # Shift rows down if data > template rows
  columns:
    - col: B
      field: "benefitName"         # Dot-notation path within each array item
      dataType: STRING
    - col: C
      field: "plan1Coverage"
      dataType: STRING
    - col: E
      formula: "=D{ROW}*12"        # {ROW} replaced with actual Excel row number
```

**Dot notation** supports nested objects:
```yaml
field: "deductible.individual"     # accesses item.deductible.individual
field: "address.city"
field: "tiers[0].rate"             # array index access
```

#### `MULTI_COL` — JSON array → parallel columns (plan comparison style)
Each item in the array fills a different column. Field rows stack vertically starting at `startRow`.
```yaml
- id: plan_headers
  type: MULTI_COL
  jsonPath: "$.plans[*]"
  startRow: 7
  columns:                         # Defines which fields to write and in what row order
    - field: "planName"
      dataType: STRING
    - field: "carrier"
      dataType: STRING
    - field: "monthlyPremium"
      dataType: CURRENCY
  columnMapping:                   # Maps array item index → Excel column letter
    0: C                           # plans[0] → column C
    1: D                           # plans[1] → column D
    2: E
    3: F
```

### Data Types and Formats

| dataType     | Accepted input                       | Default format       |
|------------- |--------------------------------------|----------------------|
| `STRING`     | Any                                  | —                    |
| `NUMBER`     | int, double, String-parseable        | `#,##0.##`           |
| `CURRENCY`   | Numeric                              | `$#,##0.00`          |
| `PERCENTAGE` | `0.20`, `20`, `"20%"` (all accepted) | `0.00%`              |
| `DATE`       | String in matching `format` pattern  | `MM/dd/yyyy`         |
| `BOOLEAN`    | true/false, yes/no, 1/0              | —                    |

Override the default format with `format: "your-pattern"`.

---

## API Endpoints

### Generate a Report
```
POST /api/insurance/reports/generate/{templateId}
Content-Type: application/json

{ ... your JSON data ... }
```
Returns: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`

### Convenience Endpoints
```
POST /api/insurance/reports/plan-comparison
POST /api/insurance/reports/rating-schedule
```

### List Available Templates
```
GET /api/insurance/reports/templates
```
Returns:
```json
{
  "plan-comparison": "Side-by-side comparison of insurance plans with benefits",
  "rating-schedule": "Age-banded premium rating schedule by product and tier"
}
```

### Hot-Reload Mapping (dev/config refresh)
```
POST /api/insurance/reports/reload/{templateId}
```
Reloads the YAML mapping file without restarting the app.

---

## Sample JSON Payloads

### Plan Comparison
```json
{
  "reportTitle":   "2025 Open Enrollment — Plan Options",
  "effectiveDate": "01/01/2025",
  "employerName":  "Acme Corp",
  "preparedBy":    "Benefits Team",
  "plans": [
    {
      "planName":    "Gold PPO 500",
      "carrier":     "BlueCross",
      "planType":    "PPO",
      "monthlyPremium": 650.00,
      "deductible": { "individual": 500.0, "family": 1000.0 },
      "outOfPocketMax": { "individual": 4000.0, "family": 8000.0 },
      "coinsurance": 0.20
    }
  ],
  "benefits": [
    {
      "benefitName":    "Primary Care Visit",
      "plan1Coverage":  "$20 copay",
      "plan2Coverage":  "20% after deductible"
    }
  ]
}
```

### Rating Schedule
```json
{
  "productName":    "Group Medical Plan A",
  "effectiveDate":  "01/01/2025",
  "state":          "California",
  "marketSegment":  "Small Group",
  "rateFactors": {
    "areaFactor":       1.05,
    "industryFactor":   0.98,
    "wellnessDiscount": 0.03
  },
  "rates": [
    { "tier": "Employee Only", "ageFrom": 18, "ageTo": 29,
      "monthlyRate": 380.00, "tobaccoSurcharge": 0.0 },
    { "tier": "Employee Only", "ageFrom": 30, "ageTo": 39,
      "monthlyRate": 440.00, "tobaccoSurcharge": 0.0 }
  ]
}
```

---

## Adding a New Template (Zero Code Changes)

1. **Create the Excel template** (`src/main/resources/templates/my_report.xlsx`)
   - Use Name Manager for named ranges (optional but recommended)
   - Leave list regions with 1 styled template row — the engine will insert more if needed

2. **Create the YAML mapping** (`src/main/resources/mappings/my-report-mapping.yml`)
   ```yaml
   template:
     id: my-report
     file: templates/my_report.xlsx
     description: "My new report"
   sheets:
     - name: "Sheet1"
       mappings:
         - id: some_field
           type: CELL
           cell: B2
           jsonPath: "$.someField"
           dataType: STRING
   ```

3. **Restart the app** (or call `POST /api/insurance/reports/reload/my-report`)

4. **Call the API**:
   ```
   POST /api/insurance/reports/generate/my-report
   ```

---

## Design Decisions

**Why YAML over @ConfigurationProperties?**
YAML files in `resources/mappings/` are loaded by `PathMatchingResourcePatternResolver`, not
Spring's property binder. This allows hot-reload and multiple files without polluting
`application.yml`. You can also version-control mappings independently of app config.

**Why JsonPath for root + dot-notation for item fields?**
JsonPath (`$.plans[*]`) is expressive for navigating the root document. Once inside an array
item (a Map), dot-notation is simpler and avoids double-parsing overhead for every cell.

**Why preserve template row styles?**
Excel templates often have conditional formatting, borders, number formats, and font styling
baked in. By copying the template row's style to dynamically inserted rows, the output matches
the designer's intent without any code involvement.

**Formula support (`formula: "=D{ROW}*12"`)**
Annual rate cells use `=D{ROW}*12` so the Excel file remains dynamic — changing a monthly
rate recalculates the annual automatically. `{ROW}` is substituted with the actual row number
at write time.