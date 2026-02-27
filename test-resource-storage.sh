#!/bin/bash

##############################################################################
# Test Resource Storage Endpoint
# Tests fetching binary (PDF) and text (FTL) resources from ResourceStorageController
##############################################################################

set -e

BASE_URL="http://localhost:8080/api/resources"

echo "=========================================="
echo "Testing Resource Storage Service"
echo "=========================================="
echo ""

# Test 1: Fetch FTL template (text file)
echo "TEST 1: Fetching FTL template from common-templates"
echo "URL: $BASE_URL/common-templates/templates/signature-page.ftl"
curl -v -H "Accept: text/plain" "$BASE_URL/common-templates/templates/signature-page.ftl" 2>&1 | grep -E "HTTP|Content-Type|<"
echo ""
echo ""

# Test 2: Fetch PDF (binary file)
echo "TEST 2: Fetching PDF from common-templates"
echo "URL: $BASE_URL/common-templates/templates/forms/applicant-form.pdf"
curl -v -H "Accept: application/pdf" "$BASE_URL/common-templates/templates/forms/applicant-form.pdf" 2>&1 | grep -E "HTTP|Content-Type|Content-Length|%PDF"
echo ""
echo ""

# Test 3: Fetch FTL template from tenant-a
echo "TEST 3: Fetching FTL template from tenant-a"
echo "URL: $BASE_URL/tenant-a/templates/signature-page.ftl"
curl -v -H "Accept: text/plain" "$BASE_URL/tenant-a/templates/signature-page.ftl" 2>&1 | grep -E "HTTP|Content-Type|<"
echo ""
echo ""

# Test 4: Test non-existent resource (should fail gracefully)
echo "TEST 4: Fetching non-existent resource (error test)"
echo "URL: $BASE_URL/common-templates/templates/non-existent.pdf"
curl -v "$BASE_URL/common-templates/templates/non-existent.pdf" 2>&1 | grep -E "HTTP|404|error" || true
echo ""
echo ""

# Test 5: Download PDF to verify it's valid
echo "TEST 5: Downloading PDF and verifying file integrity"
PDF_OUTPUT="/tmp/test-applicant-form.pdf"
curl -s "$BASE_URL/common-templates/templates/forms/applicant-form.pdf" -o "$PDF_OUTPUT"
if file "$PDF_OUTPUT" | grep -q "PDF"; then
    echo "✓ PDF is valid"
    ls -lh "$PDF_OUTPUT"
else
    echo "✗ Downloaded file is not a valid PDF"
fi
echo ""
echo ""

# Test 6: Fetch FTL and verify content
echo "TEST 6: Fetching FTL and verifying it's a text file"
FTL_OUTPUT="/tmp/test-signature-page.ftl"
curl -s "$BASE_URL/common-templates/templates/signature-page.ftl" -o "$FTL_OUTPUT"
if file "$FTL_OUTPUT" | grep -q "text"; then
    echo "✓ FTL is a text file"
    ls -lh "$FTL_OUTPUT"
    echo ""
    echo "Content preview (first 10 lines):"
    head -10 "$FTL_OUTPUT"
else
    echo "✗ Downloaded file is not a text file"
fi
echo ""
echo ""

echo "=========================================="
echo "Resource Storage Tests Complete"
echo "=========================================="
