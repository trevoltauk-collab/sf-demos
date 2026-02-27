#!/bin/bash

# 1. Set Java 17
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

PORT=${1:-8080}

echo "Using Java version:"
java -version

# 2. Kill process on port if it exists
echo "Checking port $PORT..."
PID=$(lsof -t -i:$PORT)
if [ -n "$PID" ]; then
    echo "Killing process $PID on port $PORT..."
    kill -9 $PID
fi

# 3. Run the app
echo "Starting application on port $PORT..."
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=$PORT"
