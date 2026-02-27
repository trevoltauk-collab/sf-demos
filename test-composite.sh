#!/bin/bash
echo "Testing Medical in CA..."
curl -X POST http://localhost:8080/api/documents/generate \
  -H "Content-Type: application/json" \
  -d @src/main/resources/test-data/composite-medical-ca.json \
  --output composite-medical-ca.pdf

echo "Testing Dental in NY..."
curl -X POST http://localhost:8080/api/documents/generate \
  -H "Content-Type: application/json" \
  -d @src/main/resources/test-data/composite-dental-ny.json \
  --output composite-dental-ny.pdf

echo "PDFs generated: composite-medical-ca.pdf, composite-dental-ny.pdf"
