#!/bin/bash

# MSK ACL Extractor Build Script
# This script builds the Java application using Maven

set -e  # Exit on any error

# Set JAVA_HOME if not already set
if [ -z "$JAVA_HOME" ]; then
    if [ -d "/usr/lib/jvm/java-17-amazon-corretto" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-17-amazon-corretto"
        echo "✅ JAVA_HOME set to: $JAVA_HOME"
    elif [ -d "/usr/lib/jvm/default-java" ]; then
        export JAVA_HOME="/usr/lib/jvm/default-java"
        echo "✅ JAVA_HOME set to: $JAVA_HOME"
    else
        echo "❌ Error: Could not find Java installation directory"
        echo "   Please set JAVA_HOME manually or install Java"
        exit 1
    fi
fi

echo "🔨 Building MSK ACL Extractor..."

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo "❌ Error: Maven is not installed. Please install Maven first."
    echo "   macOS: brew install maven"
    echo "   Ubuntu/Debian: sudo apt-get install maven"
    echo "   RHEL/CentOS: sudo yum install maven"
    exit 1
fi

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo "❌ Error: Java is not installed. Please install Java 11 or higher."
    exit 1
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 11 ]; then
    echo "❌ Error: Java 11 or higher is required. Current version: $JAVA_VERSION"
    exit 1
fi

echo "✅ Java version: $(java -version 2>&1 | head -1 | cut -d'"' -f2)"
echo "✅ Maven version: $(mvn -version | head -1)"

# Clean and compile
echo "🧹 Cleaning previous build..."
mvn clean

echo "📦 Compiling and packaging..."
mvn package -DskipTests

# Check if build was successful
if [ $? -eq 0 ]; then
    echo "✅ Build successful!"
    echo "📁 JAR file created: target/acl-to-cc-rbac-utility-1.0.0.jar"
    
    # Create release folder if it doesn't exist
    RELEASE_DIR="release"
    mkdir -p "$RELEASE_DIR"
    
    # Copy JAR to release folder
    JAR_FILE="target/acl-to-cc-rbac-utility-1.0.0.jar"
    RELEASE_JAR="$RELEASE_DIR/acl-to-cc-rbac-utility-1.0.0.jar"
    
    echo "📦 Copying JAR to release folder..."
    cp "$JAR_FILE" "$RELEASE_JAR"
    
    if [ $? -eq 0 ]; then
        echo "✅ JAR copied to: $RELEASE_JAR"
        
        # Create a timestamped copy as well
        TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
        TIMESTAMPED_JAR="$RELEASE_DIR/acl-to-cc-rbac-utility-1.0.0-$TIMESTAMP.jar"
        cp "$JAR_FILE" "$TIMESTAMPED_JAR"
        echo "📅 Timestamped copy created: $TIMESTAMPED_JAR"
        
        # Create latest symlink (if supported)
        if command -v ln &> /dev/null; then
            cd "$RELEASE_DIR"
            ln -sf "acl-to-cc-rbac-utility-1.0.0.jar" "acl-to-cc-rbac-utility-latest.jar"
            cd - > /dev/null
            echo "🔗 Latest symlink created: $RELEASE_DIR/acl-to-cc-rbac-utility-latest.jar"
        fi
    else
        echo "⚠️  Warning: Failed to copy JAR to release folder"
    fi
    
    echo ""
    echo "🚀 You can now run the utility with:"
    echo "   ./run.sh --help"
    echo "   or"
    echo "   java -jar $RELEASE_JAR --help"
    echo "   or"
    echo "   java -jar target/acl-to-cc-rbac-utility-1.0.0.jar --help"
else
    echo "❌ Build failed!"
    exit 1
fi

# Make run script executable
chmod +x run.sh
chmod +x extract-acls.sh

echo ""
echo "🔧 Shell scripts made executable:"
echo "   build.sh (this script)"
echo "   run.sh (simple runner)"
echo "   extract-acls.sh (comprehensive script with examples)" 