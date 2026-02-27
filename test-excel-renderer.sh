#!/bin/bash
# Test script for Excel Section Renderer
# Tests end-to-end document generation with Excel templates

set -e

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== Excel Section Renderer Test Suite ===${NC}\n"

# Test Configuration
API_URL="http://localhost:8080/api/documents/generate"
TEMPLATE_ID="excel-enrollment"
NAMESPACE="common-templates"

# Test 1: Basic single-sheet Excel rendering
echo -e "${BLUE}Test 1: Basic Excel Rendering (Single Sheet)${NC}"
TEST_DATA='{
  "firstName": "Alice",
  "lastName": "Smith",
  "email": "alice.smith@example.com",
  "phoneNumber": "555-987-6543",
  "address": {
    "street": "456 Oak Ave",
    "city": "Metropolis",
    "state": "NY",
    "zipCode": "10001"
  },
  "policyNumber": "POL-2026-005",
  "coverageType": "Individual",
  "effectiveDate": "2026-04-15",
  "planName": "Silver"
}'

echo "Sending request to: $API_URL"
echo "Template: $TEMPLATE_ID"
echo "Data: $TEST_DATA"
echo ""

RESPONSE=$(curl -s -X POST "$API_URL" \
  -H "Content-Type: application/json" \
  -d "{
    \"namespace\": \"$NAMESPACE\",
    \"templateId\": \"$TEMPLATE_ID\",
    \"data\": $TEST_DATA
  }")

echo "Response: $RESPONSE"
echo -e "${GREEN}✓ Test 1 passed${NC}\n"

# Test 2: Nested data structures
echo -e "${BLUE}Test 2: Complex Nested Data${NC}"
COMPLEX_DATA='{
  "firstName": "Robert",
  "lastName": "Johnson",
  "email": "robert.johnson@example.com",
  "phoneNumber": "555-222-3333",
  "address": {
    "street": "789 Pine Road",
    "city": "Gotham",
    "state": "CA",
    "zipCode": "90001"
  },
  "policyNumber": "POL-2026-010",
  "coverageType": "Family Plus",
  "effectiveDate": "2026-05-20",
  "planName": "Platinum"
}'

RESPONSE=$(curl -s -X POST "$API_URL" \
  -H "Content-Type: application/json" \
  -d "{
    \"namespace\": \"$NAMESPACE\",
    \"templateId\": \"$TEMPLATE_ID\",
    \"data\": $COMPLEX_DATA
  }")

echo "Response: $RESPONSE"
echo -e "${GREEN}✓ Test 2 passed${NC}\n"

# Test 3: Minimal required data
echo -e "${BLUE}Test 3: Minimal Required Fields${NC}"
MINIMAL_DATA='{
  "firstName": "Jane",
  "lastName": "Doe",
  "email": "jane@example.com",
  "phoneNumber": "555-999-8888",
  "address": {
    "street": "321 Elm Street",
    "city": "Serenity",
    "state": "TX",
    "zipCode": "75001"
  },
  "policyNumber": "POL-2026-015",
  "coverageType": "Basic",
  "effectiveDate": "2026-06-01",
  "planName": "Bronze"
}'

RESPONSE=$(curl -s -X POST "$API_URL" \
  -H "Content-Type: application/json" \
  -d "{
    \"namespace\": \"$NAMESPACE\",
    \"templateId\": \"$TEMPLATE_ID\",
    \"data\": $MINIMAL_DATA
  }")

echo "Response: $RESPONSE"
echo -e "${GREEN}✓ Test 3 passed${NC}\n"

echo -e "${GREEN}=== All tests completed ===${NC}"
