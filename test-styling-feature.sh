#!/bin/bash

# Test Styling Feature - Quick Test Script
# Usage: ./test-styling-feature.sh

set -e

BASE_URL="${BASE_URL:-http://localhost:8080}"
OUTPUT_DIR="${OUTPUT_DIR:-.}"
TEMPLATE_ID="${TEMPLATE_ID:-styling-demo.yaml}"
NAMESPACE="${NAMESPACE:-common-templates}"

echo "======================================"
echo "AcroForm Field Styling - Test Script"
echo "======================================"
echo "Base URL: $BASE_URL"
echo "Template: $TEMPLATE_ID"
echo "Namespace: $NAMESPACE"
echo "Output: $OUTPUT_DIR"
echo ""

# Check if server is running
echo "Checking server connectivity..."
if ! curl -s "$BASE_URL/actuator/health" > /dev/null 2>&1; then
    echo "❌ Error: Server not responding at $BASE_URL"
    echo "   Start the server with: mvn spring-boot:run"
    exit 1
fi
echo "✅ Server is running"
echo ""

# Test 1: Required Fields Styling
echo "Test 1: Required Fields (Red, Bold)"
echo "-----------------------------------"
curl -s -X POST "$BASE_URL/api/documents/generate" \
  -H "Content-Type: application/json" \
  -d '{
    "templateId": "'"$TEMPLATE_ID"'",
    "namespace": "'"$NAMESPACE"'",
    "data": {
      "firstName": "John",
      "lastName": "Doe",
      "applicantType": "PRIMARY",
      "total": "1234.56",
      "id": "APP-2026-001"
    }
  }' \
  -o "$OUTPUT_DIR/test-required-fields.pdf"

if [ -f "$OUTPUT_DIR/test-required-fields.pdf" ]; then
    echo "✅ Generated: $OUTPUT_DIR/test-required-fields.pdf"
    echo "   Expected: RED bold text for firstName, lastName"
else
    echo "❌ Failed to generate test-required-fields.pdf"
    exit 1
fi
echo ""

# Test 2: Read-Only Fields Styling
echo "Test 2: Read-Only Fields (Gray Background)"
echo "---------------------------------------"
curl -s -X POST "$BASE_URL/api/documents/generate" \
  -H "Content-Type: application/json" \
  -d '{
    "templateId": "'"$TEMPLATE_ID"'",
    "namespace": "'"$NAMESPACE"'",
    "data": {
      "firstName": "Jane",
      "lastName": "Smith",
      "applicantType": "SPOUSE",
      "total": "5000.00",
      "id": "APP-2026-002"
    }
  }' \
  -o "$OUTPUT_DIR/test-readonly-fields.pdf"

if [ -f "$OUTPUT_DIR/test-readonly-fields.pdf" ]; then
    echo "✅ Generated: $OUTPUT_DIR/test-readonly-fields.pdf"
    echo "   Expected: Light gray background for total, id fields"
else
    echo "❌ Failed to generate test-readonly-fields.pdf"
    exit 1
fi
echo ""

# Test 3: Mixed Styling
echo "Test 3: Mixed Styling (Multiple Groups)"
echo "--------------------------------------"
curl -s -X POST "$BASE_URL/api/documents/generate" \
  -H "Content-Type: application/json" \
  -d '{
    "templateId": "'"$TEMPLATE_ID"'",
    "namespace": "'"$NAMESPACE"'",
    "data": {
      "firstName": "Robert",
      "lastName": "Johnson",
      "applicantType": "DEPENDENT",
      "total": "999.99",
      "id": "APP-2026-003"
    }
  }' \
  -o "$OUTPUT_DIR/test-mixed-styling.pdf"

if [ -f "$OUTPUT_DIR/test-mixed-styling.pdf" ]; then
    echo "✅ Generated: $OUTPUT_DIR/test-mixed-styling.pdf"
    echo "   Expected: Mixed styling (red required, gray readonly, etc)"
else
    echo "❌ Failed to generate test-mixed-styling.pdf"
    exit 1
fi
echo ""

# Summary
echo "======================================"
echo "✅ All tests completed!"
echo "======================================"
echo "Generated PDFs in: $OUTPUT_DIR"
echo ""
echo "Next Steps:"
echo "1. Open the generated PDFs in Adobe Reader or Preview"
echo "2. Verify styles are applied correctly:"
echo "   - firstName, lastName: RED, BOLD text"
echo "   - total, id: Light GRAY background, READ-ONLY"
echo "3. Try to edit fields:"
echo "   - Red fields: should be editable"
echo "   - Gray fields: should be read-only"
echo ""
echo "View all generated PDFs:"
echo "  • open $OUTPUT_DIR/test-*.pdf          (macOS)"
echo "  • xdg-open $OUTPUT_DIR/test-*.pdf      (Linux)"
echo "  • start $OUTPUT_DIR/test-*.pdf         (Windows)"
echo ""
