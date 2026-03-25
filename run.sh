#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/java"
./mvnw -q install -DskipTests
./mvnw -pl app javafx:run
