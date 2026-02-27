# Age Rating Comparison Matrix - Curl Test Examples

## Prerequisites
- API server running on http://localhost:8080
- curl installed
- jq (optional, for JSON formatting)

## Test 1: Basic Request with JSON File

```bash
curl -X POST http://localhost:8080/api/documents/generate/excel \
  -H "Content-Type: application/json" \
  -d @src/main/resources/requests/age-rating-comparison.json \
  --output age-rating-output.xlsx \
  -v
```

## Test 2: Using Inline JSON Data

```bash
curl -X POST http://localhost:8080/api/documents/generate/excel \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "common-templates",
    "templateId": "age-rating-comparison",
    "data": {
      "comparisonMatrix": [
        ["Silver", "", "", "", "", "", "", "", "Gold", "", "", "", "", "", "", ""],
        ["Nat / S001", "", "", "", "", "", "", "", "Intl / G002", "", "", "", "", "", "", ""],
        ["Age", "Rating", "", "Age", "Rating", "", "Age", "Rating", "", "Age", "Rating", "", "Age", "Rating", "", "Age", "Rating", ""],
        [0, 100, "", 31, 410, "", 48, 600, "", 0, 90, "", 31, 340, "", 48, 500, ""],
        [1, 110, "", 32, 420, "", 49, 615, "", 1, 98, "", 32, 349, "", 49, 512, ""],
        [2, 120, "", 33, 430, "", 50, 630, "", 2, 106, "", 33, 358, "", 50, 524, ""],
        [3, 130, "", 34, 440, "", 51, 645, "", 3, 114, "", 34, 367, "", 51, 536, ""]
      ]
    }
  }' \
  --output age-rating-basic.xlsx
```

## Test 3: Test with Different Template (if you have one)

```bash
curl -X POST http://localhost:8080/api/documents/generate/excel \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "common-templates",
    "templateId": "age-rating-comparison",
    "data": {
      "comparisonMatrix": [
        ["Plan A", "", "Plan B"],
        ["Age", "Rating", "Age", "Rating"]
      ]
    }
  }' \
  --output age-rating-minimal.xlsx
```

## Test 4: Using Variables from Shell Script

```bash
#!/bin/bash

TEMPLATE_ID="age-rating-comparison"
NAMESPACE="common-templates"
REQUEST_FILE="src/main/resources/requests/age-rating-comparison.json"
OUTPUT_FILE="output-$(date +%Y%m%d-%H%M%S).xlsx"

curl -X POST http://localhost:8080/api/documents/generate/excel \
  -H "Content-Type: application/json" \
  -d @"$REQUEST_FILE" \
  --output "$OUTPUT_FILE" \
  -w "\nHTTP Status: %{http_code}\n"

echo "Generated: $OUTPUT_FILE"
```

## Test 5: Verbose Output with Response Headers

```bash
curl -X POST http://localhost:8080/api/documents/generate/excel \
  -H "Content-Type: application/json" \
  -d @src/main/resources/requests/age-rating-comparison.json \
  --output age-rating-verbose.xlsx \
  -v \
  -w "\nTotal time: %{time_total}s\nDownload speed: %{speed_download} bytes/sec\n"
```

## Test 6: Running the Shell Script

```bash
# Make the script executable
chmod +x test-age-rating-comparison.sh

# Run the test
./test-age-rating-comparison.sh
```

## Verification Steps

1. **Check HTTP Status Code** - Should be 200 OK
2. **Verify File Created** - Check if Excel file was generated
3. **Inspect File Size** - Should be > 10KB (typical for populated Excel)
4. **Open in Excel/LibreOffice**:
   - Verify headers are correct (Silver, Gold plan names)
   - Check Band 1 (ages 0-30): ratings 100-400 for Silver, 90-330 for Gold
   - Check Band 2 (ages 31-47): ratings 410-570 for Silver, 340-484 for Gold
   - Check Band 3 (ages 48-64): ratings 600-840 for Silver, 500-692 for Gold

## Troubleshooting

### Server Not Responding
```bash
# Check if server is running
curl -X GET http://localhost:8080/actuator/health
```

### Invalid JSON
```bash
# Validate JSON before sending
jq . src/main/resources/requests/age-rating-comparison.json
```

### Save Response Headers for Debugging
```bash
curl -X POST http://localhost:8080/api/documents/generate/excel \
  -H "Content-Type: application/json" \
  -d @src/main/resources/requests/age-rating-comparison.json \
  -D age-rating-headers.txt \
  --output age-rating-output.xlsx
```

## Other Useful Curl Options

| Option | Description |
|--------|-------------|
| `-v` | Verbose output (show headers) |
| `-s` | Silent mode (no progress meter) |
| `-w '%{http_code}'` | Show only HTTP status code |
| `-X POST` | Use POST method |
| `-H "Content-Type: application/json"` | Set Content-Type header |
| `-d @file.json` | Send file as request body |
| `--output file.xlsx` | Save response to file |
| `-L` | Follow redirects |
| `--max-time 30` | Set timeout to 30 seconds |
