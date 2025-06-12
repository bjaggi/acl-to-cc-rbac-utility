#!/bin/bash

# MSK ACL Extractor Runner Script
# Simple wrapper to run the Java application

set -e

# Set JAVA_HOME if not already set
if [ -z "$JAVA_HOME" ]; then
    if [ -d "/usr/lib/jvm/java-17-amazon-corretto" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-17-amazon-corretto"
    elif [ -d "/usr/lib/jvm/default-java" ]; then
        export JAVA_HOME="/usr/lib/jvm/default-java"
    fi
fi

JAR_FILE="target/acl-to-rbac-converter.jar"
RELEASE_JAR="release/acl-to-rbac-converter.jar"



# Check if Java is available
if ! command -v java &> /dev/null; then
    echo "❌ Error: Java is not installed. Please install Java 11 or higher."
    exit 1
fi

# Run the application with all provided arguments
echo "🚀 Running MSK ACL Extractor..."
java -jar "$JAR_FILE" "$@" 