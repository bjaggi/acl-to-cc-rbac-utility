#!/bin/bash

# MSK ACL Extractor Runner Script
# Simple wrapper to run the Java application

set -e

JAR_FILE="target/acl-to-cc-rbac-utility-1.0.0.jar"
RELEASE_JAR="release/acl-to-cc-rbac-utility-1.0.0.jar"

# Check if JAR file exists (prefer release version)
if [ -f "$RELEASE_JAR" ]; then
    JAR_FILE="$RELEASE_JAR"
elif [ -f "$JAR_FILE" ]; then
    # Use target version if release doesn't exist
    echo "ℹ️  Using JAR from target directory (consider running './build.sh' to update release)"
else
    echo "❌ Error: JAR file not found in either location:"
    echo "   - $RELEASE_JAR"
    echo "   - $JAR_FILE"
    echo "🔨 Please run './build.sh' first to build the application."
    exit 1
fi

# Check if Java is available
if ! command -v java &> /dev/null; then
    echo "❌ Error: Java is not installed. Please install Java 11 or higher."
    exit 1
fi

# Run the application with all provided arguments
echo "🚀 Running MSK ACL Extractor..."
java -jar "$JAR_FILE" "$@" 