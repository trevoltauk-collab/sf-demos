#!/bin/bash

# Age Rating Comparison Matrix Test
# This script tests the age-rating-comparison template with curl

BASE_URL="http://localhost:8080/api/documents/generate/excel"
REQUEST_FILE="src/main/resources/requests/age-rating-comparison.json"
OUTPUT_FILE="age-rating-comparison-output.xlsx"

echo "Testing Age Rating Comparison Matrix Generation..."
echo "=================================================="
echo ""
echo "Request URL: $BASE_URL"
echo "Request file: $REQUEST_FILE"
echo "Output file: $OUTPUT_FILE"
echo ""

# Send the request and save the Excel file
curl -X POST "$BASE_URL" \
  -H "Content-Type: application/json" \
  -d @"$REQUEST_FILE" \
  --output "$OUTPUT_FILE" \
  -v

echo ""
echo "=================================================="
echo "Excel file saved to: $OUTPUT_FILE"
echo ""
echo "To verify the output:"
echo "  1. Open $OUTPUT_FILE in Excel or LibreOffice"
echo "  2. Verify the three age bands (0-30, 31-47, 48-64) are displayed"
echo "  3. Check that Silver and Gold plan ratings are correct"
echo ""
