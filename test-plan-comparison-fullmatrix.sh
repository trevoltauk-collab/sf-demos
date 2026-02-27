 curl -X POST http://localhost:8080/api/documents/generate/excel \
  -H "Content-Type: application/json" \
  -d '{
  "namespace": "common-templates",
  "templateId": "plan-comparison",
  "data": {
    "comparisonTitle": "Insurance Plan Comparison",
    "plans": [
      {
        "planName": "Basic",
        "group": "Group A",
        "benefits": [
          { "name": "Doctor Visits", "value": "$20 copay" },
          { "name": "Prescriptions", "value": "$10 copay" }
        ]
      },
      {
        "planName": "Premium",
        "group": "Group B",
        "benefits": [
          { "name": "Doctor Visits", "value": "Covered 100%" },
          { "name": "Prescriptions", "value": "$5 copay" }
        ]
      }
    ]
  }
}
      }' \
  --output plan-comparison-full-matrix-output.xlsx