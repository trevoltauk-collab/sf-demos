#!/bin/bash
curl -X POST http://localhost:8080/api/documents/generate \
  -H "Content-Type: application/json" \
  -d @src/main/resources/test-data/enrollment-multi-overflow-request.json \
  --output multi-overflow-output.pdf

echo "PDF generated: multi-overflow-output.pdf"
