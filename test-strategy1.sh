#!/bin/bash

echo "========================================="
echo "Testing Strategy 1: Pre-Processing"
echo "========================================="

BASE_URL="http://localhost:8080/api/overflow"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}1. Getting test data...${NC}"
curl -s "$BASE_URL/test-data" > /tmp/overflow-test-data.json

echo -e "${BLUE}2. Calling Strategy 1+4 Endpoint...${NC}"
curl -s -X POST "$BASE_URL/strategy1-plus-4" \
  -H "Content-Type: application/json" \
  -d @/tmp/overflow-test-data.json \
  -o /tmp/overflow-strategy1.pdf \
  -w "\n   Status: %{http_code}\n   Time: %{time_total}s\n"

if [ -f /tmp/overflow-strategy1.pdf ]; then
    SIZE=$(ls -lh /tmp/overflow-strategy1.pdf | awk '{print $5}')
    echo -e "${GREEN}✓ PDF generated: $SIZE${NC}"
    echo "   Saved to: /tmp/overflow-strategy1.pdf"
else
    echo -e "${YELLOW}✗ Failed to generate PDF${NC}"
fi
