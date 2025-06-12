#!/bin/bash

# MSK ACL to Confluent Cloud RBAC Converter Script
# This script converts MSK ACLs from JSON format to Confluent Cloud RBAC format

set -e

# Default values
INPUT_FILE="msk_acls.json"
OUTPUT_FILE="cc_rbac.json"
ENVIRONMENT="env-xxxxx"
CLUSTER_ID="lkc-xxxxx"
VERBOSE=false

# Function to display help
show_help() {
    cat << EOF
MSK ACL to Confluent Cloud RBAC Converter

Usage: $0 [OPTIONS]

Options:
    -i, --input FILE        Input MSK ACLs JSON file (default: msk_acls.json)
    -o, --output FILE       Output Confluent Cloud RBAC JSON file (default: cc_rbac.json)
    -e, --environment ENV   Target Confluent Cloud environment ID (e.g., env-12345)
    -c, --cluster CLUSTER   Target Confluent Cloud cluster ID (e.g., lkc-67890)
    -v, --verbose           Enable verbose logging
    -h, --help              Show this help message

Examples:
    # Basic conversion with default values
    $0

    # Specify custom files and target cluster
    $0 -i my_acls.json -o my_rbac.json -e env-12345 -c lkc-67890

    # Convert with verbose logging
    $0 -v -e env-production -c lkc-my-cluster

Requirements:
    - Java 11 or higher
    - Maven (for building)
    - Input JSON file with MSK ACL data

The script will:
1. Build the Java application if needed
2. Run the ACL to RBAC conversion
3. Generate a Confluent Cloud RBAC JSON file
4. Display conversion summary

EOF
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -i|--input)
            INPUT_FILE="$2"
            shift 2
            ;;
        -o|--output)
            OUTPUT_FILE="$2"
            shift 2
            ;;
        -e|--environment)
            ENVIRONMENT="$2"
            shift 2
            ;;
        -c|--cluster)
            CLUSTER_ID="$2"
            shift 2
            ;;
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
done

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Java is available
check_java() {
    if ! command -v java &> /dev/null; then
        print_error "Java is not installed or not in PATH"
        print_info "Please install Java 11 or higher"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -n1 | awk -F '"' '{print $2}' | awk -F '.' '{print $1}')
    if [[ $JAVA_VERSION -lt 11 ]]; then
        print_error "Java 11 or higher is required (found Java $JAVA_VERSION)"
        exit 1
    fi
    
    print_info "Using Java version: $(java -version 2>&1 | head -n1)"
}

# Check if Maven is available
check_maven() {
    if ! command -v mvn &> /dev/null; then
        print_error "Maven is not installed or not in PATH"
        print_info "Please install Maven 3.6 or higher"
        exit 1
    fi
    
    print_info "Using Maven version: $(mvn -version | head -n1)"
}

# Build the application if needed
build_application() {
    if [[ ! -f "target/acl-to-rbac-converter.jar" ]]; then
        print_info "Building the application..."
        if $VERBOSE; then
            mvn clean package
        else
            mvn clean package -q
        fi
        print_success "Application built successfully"
        # Copy to release folder
        if [[ -f "target/acl-to-rbac-converter.jar" ]]; then
            cp target/acl-to-rbac-converter.jar release/
            print_info "Copied jar to release folder"
        fi
    else
        print_info "Using existing compiled application"
    fi
}

# Validate input file
validate_input() {
    if [[ ! -f "$INPUT_FILE" ]]; then
        print_error "Input file does not exist: $INPUT_FILE"
        exit 1
    fi
    
    # Check if file is valid JSON
    if ! python3 -m json.tool "$INPUT_FILE" > /dev/null 2>&1; then
        print_error "Input file is not valid JSON: $INPUT_FILE"
        exit 1
    fi
    
    print_info "Input file validated: $INPUT_FILE"
}

# Run the conversion
run_conversion() {
    print_info "Starting ACL to RBAC conversion..."
    print_info "Input file: $INPUT_FILE"
    print_info "Output file: $OUTPUT_FILE"
    print_info "Target environment: $ENVIRONMENT"
    print_info "Target cluster: $CLUSTER_ID"
    
    # Construct Java command
    JAVA_CMD="java -jar target/acl-to-rbac-converter.jar"
    JAVA_CMD="$JAVA_CMD \"$INPUT_FILE\" \"$OUTPUT_FILE\" \"$ENVIRONMENT\" \"$CLUSTER_ID\""
    
    if $VERBOSE; then
        print_info "Running command: $JAVA_CMD"
    fi
    
    # Run the conversion
    if eval $JAVA_CMD; then
        print_success "Conversion completed successfully!"
        
        # Display output file info
        if [[ -f "$OUTPUT_FILE" ]]; then
            FILE_SIZE=$(wc -c < "$OUTPUT_FILE")
            print_info "Output file created: $OUTPUT_FILE (${FILE_SIZE} bytes)"
            
            # Show first few lines of output if verbose
            if $VERBOSE; then
                print_info "Output file preview:"
                head -20 "$OUTPUT_FILE"
            fi
        fi
    else
        print_error "Conversion failed!"
        exit 1
    fi
}

# Main execution
main() {
    print_info "MSK ACL to Confluent Cloud RBAC Converter"
    print_info "=========================================="
    
    # Perform checks
    check_java
    check_maven
    validate_input
    
    # Build and run
    build_application
    run_conversion
    
    print_success "All done! 🎉"
    print_info "Next steps:"
    print_info "1. Review the generated RBAC file: $OUTPUT_FILE"
    print_info "2. Validate the role bindings match your requirements"
    print_info "3. Apply the role bindings to your Confluent Cloud cluster"
    print_info "4. Test the permissions with your applications"
}

# Run main function
main 