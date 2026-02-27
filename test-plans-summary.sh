#!/bin/bash
PORT=${1:-8080}
echo "Testing Plans Summary PDF generation on port $PORT..."
curl -X POST http://localhost:$PORT/api/documents/generate \
  -H "Content-Type: application/json" \
  -d @src/main/resources/test-data/plans-summary-request.json \
  --output new-plans-summary-output.pdf

if [ $? -eq 0 ]; then
    echo "Success! PDF generated: plans-summary-output.pdf"
    ls -l plans-summary-output.pdf
else
    echo "Failed to generate PDF."
fi
