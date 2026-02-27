#!/bin/bash

echo "Starting Config Server..."
cd /workspaces/demo/config-server
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8888" > config-server.log 2>&1 &
CONFIG_SERVER_PID=$!

echo "Waiting for Config Server to start (30s)..."
sleep 30

# Verify Config Server is serving the template
echo "Verifying template from Config Server..."
curl -s http://localhost:8888/doc-gen-service/default/main/base-enrollment.yaml | head -n 10

echo "Starting Main Application..."
cd /workspaces/demo
./dev.sh 8080 > app.log 2>&1 &
APP_PID=$!

echo "Waiting for Main Application to start (20s)..."
sleep 20

echo "Testing document generation using externalized template..."
curl -X POST http://localhost:8080/api/documents/generate \
  -H "Content-Type: application/json" \
  -d '{
    "templateId": "base-enrollment.yaml",
    "data": {
      "application": {
        "applicationId": "CONFIG-SERVER-TEST",
        "primaryFirstName": "Config",
        "primaryLastName": "User"
      }
    }
  }' --output config-test-output.pdf

if [ -f config-test-output.pdf ]; then
    echo "SUCCESS: PDF generated using template from Config Server!"
    ls -lh config-test-output.pdf
else
    echo "FAILURE: PDF not generated."
fi

# Cleanup
echo "Cleaning up..."
kill $CONFIG_SERVER_PID
kill $APP_PID
