#!/bin/bash

# MSK to Confluent Cloud Migration Utility Build Script
# This script builds the Java application using Maven

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

# Check if Java is installed and version
check_java() {
    if ! command -v java &> /dev/null; then
        print_error "Java is not installed. Please install Java 11 or higher."
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 11 ]; then
        print_error "Java 11 or higher is required. Current version: $JAVA_VERSION"
        exit 1
    fi
    
    print_success "Java version: $(java -version 2>&1 | head -1 | cut -d'"' -f2)"
}

# Check if Maven is installed
check_maven() {
    if ! command -v mvn &> /dev/null; then
        print_error "Maven is not installed. Please install Maven first."
        print_info "  macOS: brew install maven"
        print_info "  Ubuntu/Debian: sudo apt-get install maven"
        print_info "  RHEL/CentOS: sudo yum install maven"
        exit 1
    fi
    
    print_success "Maven version: $(mvn -version | head -1)"
}

# Set JAVA_HOME if not already set
set_java_home() {
    if [ -z "$JAVA_HOME" ]; then
        if [ -d "/usr/lib/jvm/java-17-amazon-corretto" ]; then
            export JAVA_HOME="/usr/lib/jvm/java-17-amazon-corretto"
            print_info "JAVA_HOME set to: $JAVA_HOME"
        elif [ -d "/usr/lib/jvm/default-java" ]; then
            export JAVA_HOME="/usr/lib/jvm/default-java"
            print_info "JAVA_HOME set to: $JAVA_HOME"
        else
            print_error "Could not find Java installation directory"
            print_error "Please set JAVA_HOME manually or install Java"
            exit 1
        fi
    fi
}

# Parse command line arguments
VERBOSE=false
while [[ $# -gt 0 ]]; do
    case $1 in
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            echo "MSK to Confluent Cloud Migration Utility Build Script"
            echo ""
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  -v, --verbose    Enable verbose output"
            echo "  -h, --help       Show this help message"
            echo ""
            exit 0
            ;;
        *)
            print_error "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Main execution
main() {
    echo "🚀 Building MSK to Confluent Cloud Migration Utility"
    echo "=================================================="
    
    # Set up environment
    set_java_home
    
    # Check prerequisites
    check_java
    check_maven
    
    # Clean and compile
    print_step "Cleaning previous builds..."
    mvn clean -q
    
    print_step "Compiling and packaging..."
    if [[ "$VERBOSE" == "true" ]]; then
        mvn package
    else
        mvn package -q
    fi
    
    # Check if build was successful - Maven shade plugin creates the unified JAR
    if [[ ! -f "target/msk-to-confluent-cloud.jar" ]]; then
        print_error "Build failed - shaded JAR file not found"
        print_error "Expected: target/msk-to-confluent-cloud.jar"
        exit 1
    fi
    
    print_success "JAR file created: target/acl-to-cc-rbac-utility-1.0.0.jar"
    print_success "Unified JAR created: target/msk-to-confluent-cloud.jar"
    
    # Copy to release directory
    print_step "Creating release JAR..."
    
    # Create release directory
    mkdir -p release
    
    # Copy the shaded JAR (which has the correct manifest) to release
    cp target/msk-to-confluent-cloud.jar release/
    
    print_success "Release JAR created: release/msk-to-confluent-cloud.jar"
    
    # Make scripts executable
    print_step "Making scripts executable..."
    find scripts -name "*.sh" -type f -exec chmod +x {} \; 2>/dev/null || true
    chmod +x *.sh 2>/dev/null || true
    
    print_success "Build completed successfully!"
    
    echo ""
    echo "🎯 Next Steps:"
    echo "1. Configure your MSK cluster details in the scripts"
    echo "2. Run the extraction: ./scripts/extract_msk_metadata/extract-msk-metadata.sh"
    echo "3. Convert ACLs to RBAC: ./scripts/convert-acl-to-rbac.sh"
    echo "4. Create Confluent Cloud resources and apply RBAC"
    echo ""
    echo "📚 For detailed instructions, see the README.md file"
}

# Run main function
main "$@"