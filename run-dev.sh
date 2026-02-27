#!/bin/bash

# Dev profile (with config server)
echo "Starting app with DEV profile..."
echo "Make sure config server is running: cd config-server && mvn spring-boot:run"
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev"
