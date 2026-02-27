#!/bin/bash

echo "========================================="
echo "Overflow Handling Strategy Testing"
echo "========================================="
echo ""

BASE_URL="http://localhost:8080/api/overflow"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}1. Getting test data...${NC}"
curl -s "$BASE_URL/test-data" > /tmp/overflow-test-data.json
CHILD_COUNT=$(cat /tmp/overflow-test-data.json | grep -o '"applicantType": "CHILD"' | wc -l)
echo -e "${GREEN}✓ Test data loaded: $CHILD_COUNT children${NC}"
echo ""

echo -e "${BLUE}2. Testing Strategy 3: Manual YAML Slicing${NC}"
echo "   Generating main form (children 1-3) + addendum (children 4-6)..."
curl -s -X POST "$BASE_URL/strategy3-manual" \
  -H "Content-Type: application/json" \
  -d @/tmp/overflow-test-data.json \
  -o /tmp/overflow-strategy3.pdf \
  -w "\n   Status: %{http_code}\n   Time: %{time_total}s\n"

if [ -f /tmp/overflow-strategy3.pdf ]; then
    SIZE=$(ls -lh /tmp/overflow-strategy3.pdf | awk '{print $5}')
    PAGES=$(pdfinfo /tmp/overflow-strategy3.pdf 2>/dev/null | grep Pages | awk '{print $2}')
    echo -e "${GREEN}✓ PDF generated: $SIZE, $PAGES pages${NC}"
    echo "   Saved to: /tmp/overflow-strategy3.pdf"
else
    echo -e "${YELLOW}✗ Failed to generate PDF${NC}"
fi
echo ""

echo -e "${BLUE}3. Testing Strategy 2: Automatic Detection${NC}"
curl -s -X POST "$BASE_URL/strategy2-automatic" \
  -H "Content-Type: application/json" \
  -d @/tmp/overflow-test-data.json | jq '.'
echo ""

echo -e "${GREEN}========================================="
echo "Testing Complete!"
echo "=========================================${NC}"
echo ""
echo "Generated files:"
echo "  - /tmp/overflow-strategy3.pdf (Manual slicing with addendum)"
echo ""
echo "You can open the PDF with:"
echo "  xdg-open /tmp/overflow-strategy3.pdf"
