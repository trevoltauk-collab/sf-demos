#!/bin/bash
PORT=${1:-8080}
echo "Testing ViewModel PDF generation on port $PORT..."
curl -X POST http://localhost:$PORT/api/documents/generate \
  -H "Content-Type: application/json" \
  -d @src/main/resources/test-data/invoice-viewmodel-request.json \
  --output invoice-viewmodel-output.pdf

if [ $? -eq 0 ]; then
    echo "Success! PDF generated: invoice-viewmodel-output.pdf"
    ls -l invoice-viewmodel-output.pdf
else
    echo "Failed to generate PDF."
fi
