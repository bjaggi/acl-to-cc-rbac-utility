#!/bin/bash

# MSK ACL to Confluent Cloud RBAC Converter Script
# This script converts MSK ACLs from JSON format to Confluent Cloud RBAC format

set -e

# Default values
INPUT_FILE="generated_jsons/msk_jsons/msk_acls.json"
OUTPUT_FILE="generated_jsons/cc_jsons/cc_rbac.json"
ENVIRONMENT="env-xxxxx"
CLUSTER_ID="lkc-xxxxx"
VERBOSE=false

# Function to display help
show_help() {
    cat << EOF
MSK ACL to Confluent Cloud RBAC Converter

Usage: $0 [OPTIONS]

Options:
    -i, --input FILE        Input MSK ACLs JSON file (default: generated_jsons/msk_jsons/msk_acls.json)
    -o, --output FILE       Output Confluent Cloud RBAC JSON file (default: generated_jsons/cc_jsons/cc_rbac.json)
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

print_processing() {
    echo -e "${BLUE}[PROCESSING]${NC} $1"
}

show_conversion_summary() {
    if [[ -f "$OUTPUT_FILE" ]]; then
        print_info "Conversion Summary:"
        print_info "=================="
        print_info "Input file:  $INPUT_FILE"
        print_info "Output file: $OUTPUT_FILE"
        
        # Count role bindings if the file contains JSON
        if command -v jq &> /dev/null && [[ -s "$OUTPUT_FILE" ]]; then
            local role_count=$(jq -r '.roleBindings | length' "$OUTPUT_FILE" 2>/dev/null || echo "0")
            print_info "Role bindings created: $role_count"
        fi
        
        print_info "File size: $(du -h "$OUTPUT_FILE" | cut -f1)"
    else
        print_warning "Output file not found: $OUTPUT_FILE"
    fi
}

check_prerequisites() {
    print_processing "Validating prerequisites..."
    
    # Check if Java is available
    if ! command -v java &> /dev/null; then
        print_error "Java is not installed or not in PATH"
        print_info "Please install Java 11 or higher"
        exit 1
    fi
    
    # Check if JAR file exists
    if [[ ! -f "release/msk-to-confluent-cloud.jar" ]]; then
        print_error "JAR file not found: release/msk-to-confluent-cloud.jar"
        print_info "Please run './build.sh' to build the project"
        exit 1
    fi
    
    # Copy JAR to release directory if it doesn't exist there
    if [[ -f "target/msk-to-confluent-cloud.jar" && ! -f "release/msk-to-confluent-cloud.jar" ]]; then
        mkdir -p release
        cp target/msk-to-confluent-cloud.jar release/
        print_success "JAR file copied to release directory"
    fi
    
    print_success "Java ACL converter available"
}

convert_acls_to_rbac() {
    print_processing "Converting ACLs to RBAC using Java..."
    
    # Create output directory if it doesn't exist
    mkdir -p "$(dirname "$OUTPUT_FILE")"
    
    # Prepare Java command
    JAVA_CMD="java -jar release/msk-to-confluent-cloud.jar convert"
    
    # Add required arguments
    JAVA_CMD="$JAVA_CMD --input-file \"$INPUT_FILE\""
    JAVA_CMD="$JAVA_CMD --output-file \"$OUTPUT_FILE\""
    
    # Add optional arguments
    if [[ "$DRY_RUN" == "true" ]]; then
        JAVA_CMD="$JAVA_CMD --dry-run"
    fi
    
    if [[ "$VERBOSE" == "true" ]]; then
        JAVA_CMD="$JAVA_CMD --verbose"
    fi
    
    if [[ "$FORCE" == "true" ]]; then
        JAVA_CMD="$JAVA_CMD --force"
    fi
    
    if [[ -n "$CLUSTER_ID" ]]; then
        JAVA_CMD="$JAVA_CMD --cluster-id \"$CLUSTER_ID\""
    fi
    
    if [[ -n "$ENVIRONMENT" ]]; then
        JAVA_CMD="$JAVA_CMD --environment-id \"$ENVIRONMENT\""
    fi
    
    print_info "Executing: $JAVA_CMD"
    
    # Execute the conversion
    if eval "$JAVA_CMD"; then
        print_success "ACL to RBAC conversion completed successfully"
        
        # Show conversion summary if output file exists
        if [[ -f "$OUTPUT_FILE" ]]; then
            show_conversion_summary
        fi
        
        return 0
    else
        print_error "ACL to RBAC conversion failed"
        return 1
    fi
}

# Main execution
main() {
    print_info "MSK ACL to Confluent Cloud RBAC Converter"
    print_info "=========================================="
    
    # Perform checks
    check_prerequisites
    
    # Convert ACLs to RBAC
    convert_acls_to_rbac
    
    print_success "All done! 🎉"
    print_info "Next steps:"
    print_info "1. Review the generated RBAC file: $OUTPUT_FILE"
    print_info "2. Validate the role bindings match your requirements"
    print_info "3. Apply the role bindings to your Confluent Cloud cluster"
    print_info "4. Test the permissions with your applications"
}

# Run main function
main 