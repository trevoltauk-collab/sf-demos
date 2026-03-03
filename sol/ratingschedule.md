# ============================================================
# Rating Schedule Template Mapping
# Template: rating_schedule.xlsx
# ============================================================

template:
  id: rating-schedule
  file: templates/rating_schedule.xlsx
  description: "Age-banded premium rating schedule by product and tier"

sheets:
  - name: "Rating Schedule"
    mappings:

      # ── Document Header ──────────────────────────────────
      - id: product_name
        type: CELL
        cell: C3
        jsonPath: "$.productName"
        dataType: STRING

      - id: product_code
        type: CELL
        cell: C4
        jsonPath: "$.productCode"
        dataType: STRING

      - id: effective_date
        type: CELL
        cell: C5
        jsonPath: "$.effectiveDate"
        dataType: DATE
        format: "MM/dd/yyyy"

      - id: expiration_date
        type: CELL
        cell: C6
        jsonPath: "$.expirationDate"
        dataType: DATE
        format: "MM/dd/yyyy"

      - id: state
        type: CELL
        cell: F3
        jsonPath: "$.state"
        dataType: STRING

      - id: market_segment
        type: CELL
        cell: F4
        jsonPath: "$.marketSegment"   # e.g. Small Group, Large Group, Individual
        dataType: STRING

      - id: plan_design
        type: CELL
        cell: F5
        jsonPath: "$.planDesign"
        dataType: STRING

      # ── Rate Factors / Assumptions ───────────────────────
      - id: area_factor
        type: CELL
        cell: C10
        jsonPath: "$.rateFactors.areaFactor"
        dataType: NUMBER
        format: "0.0000"

      - id: industry_factor
        type: CELL
        cell: C11
        jsonPath: "$.rateFactors.industryFactor"
        dataType: NUMBER
        format: "0.0000"

      - id: group_size_factor
        type: CELL
        cell: C12
        jsonPath: "$.rateFactors.groupSizeFactor"
        dataType: NUMBER
        format: "0.0000"

      - id: wellness_discount
        type: CELL
        cell: C13
        jsonPath: "$.rateFactors.wellnessDiscount"
        dataType: PERCENTAGE

      # ── Age-Banded Rate Table (repeating rows) ───────────
      - id: rate_rows
        type: LIST
        jsonPath: "$.rates[*]"
        startRow: 18          # first data row in template
        rowStepSize: 1
        preserveRowStyle: true
        insertRowsIfNeeded: true  # dynamically insert rows for large datasets
        columns:
          - col: A
            field: "tier"
            dataType: STRING
          - col: B
            field: "ageFrom"
            dataType: NUMBER
            format: "0"
          - col: C
            field: "ageTo"
            dataType: NUMBER
            format: "0"
          - col: D
            field: "monthlyRate"
            dataType: CURRENCY
          - col: E
            field: "annualRate"
            formula: "=D{ROW}*12"    # {ROW} replaced with actual row number
          - col: F
            field: "tobaccoSurcharge"
            dataType: CURRENCY
          - col: G
            field: "totalWithSurcharge"
            formula: "=D{ROW}+F{ROW}"

  - name: "By Tier"
    mappings:
      # ── Tier Summary — grouped by tier name ─────────────
      - id: tier_summary
        type: LIST
        jsonPath: "$.tierSummary[*]"
        startRow: 5
        rowStepSize: 1
        preserveRowStyle: true
        columns:
          - col: A
            field: "tierName"
            dataType: STRING
          - col: B
            field: "compositeRate"
            dataType: CURRENCY
          - col: C
            field: "employerShare"
            dataType: CURRENCY
          - col: D
            field: "employeeShare"
            dataType: CURRENCY
          - col: E
            field: "enrolledCount"
            dataType: NUMBER
          - col: F
            field: "totalMonthlyPremium"
            formula: "=B{ROW}*E{ROW}"

  - name: "Products"
    mappings:
      # ── Multi-product comparison ─────────────────────────
      - id: product_comparison
        type: LIST
        jsonPath: "$.products[*]"
        startRow: 4
        rowStepSize: 1
        preserveRowStyle: true
        columns:
          - col: A
            field: "productName"
            dataType: STRING
          - col: B
            field: "planType"
            dataType: STRING
          - col: C
            field: "networkType"
            dataType: STRING
          - col: D
            field: "baseRate"
            dataType: CURRENCY
          - col: E
            field: "effectiveDate"
            dataType: DATE
            format: "MM/dd/yyyy"