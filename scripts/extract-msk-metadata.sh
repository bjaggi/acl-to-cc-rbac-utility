#!/bin/bash

# MSK ACL and Topic Extractor Script
# Reads configuration from msk.config file and extracts ACLs/topics from MSK

set -e

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

# Function to show help
show_help() {
    cat << EOF
MSK ACL and Topic Extractor

This script reads MSK cluster configuration from msk.config file and extracts:
- All ACLs (Access Control Lists) → generated_jsons/msk_acls.json
- All topics with their configurations → generated_jsons/msk_topics.json
- Cluster metadata

Usage: $0 [OPTIONS]

Options:
    -h, --help      Show this help message
    -v, --verbose   Enable verbose logging (overrides config file setting)

Configuration:
    The script reads configuration from 'msk.config' file in the project root.
    
    Required settings in msk.config:
        cluster.arn     - ARN of your MSK cluster
        region          - AWS region (e.g., us-east-1)
    
    Optional settings:
        security.protocol    - SSL, SASL_SSL, PLAINTEXT (default: SSL)
        sasl.mechanism      - AWS_MSK_IAM, SCRAM-SHA-256, etc.
        sasl.username       - Username for SASL authentication
        sasl.password       - Password for SASL authentication
        include.metadata    - Include cluster metadata (default: true)
        verbose            - Enable verbose logging (default: false)

Output Files:
    generated_jsons/msk_acls.json    - All ACLs from the MSK cluster
    generated_jsons/msk_topics.json  - All topics with configurations

Examples:
    # Extract ACLs and topics using config file
    $0
    
    # Extract with verbose logging
    $0 --verbose

Requirements:
    - Java 11 or higher
    - AWS credentials configured (AWS CLI, IAM roles, etc.)
    - Network access to MSK cluster
    - msk.config file with cluster details

EOF
}

# Parse command line arguments
VERBOSE_OVERRIDE=""
while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            show_help
            exit 0
            ;;
        -v|--verbose)
            VERBOSE_OVERRIDE="true"
            shift
            ;;
        *)
            print_error "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
done

# Function to read configuration from msk.config
read_config() {
    local config_file="msk.config"
    
    if [[ ! -f "$config_file" ]]; then
        print_error "Configuration file not found: $config_file"
        print_error "Please create msk.config file with your MSK cluster details."
        print_error ""
        print_error "Example msk.config content:"
        print_error "cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123"
        print_error "region=us-east-1"
        print_error "security.protocol=SASL_SSL"
        print_error "sasl.mechanism=AWS_MSK_IAM"
        exit 1
    fi
    
    print_info "Reading configuration from $config_file"
    
    # Initialize variables with defaults
    CLUSTER_ARN=""
    REGION="us-east-1"
    SECURITY_PROTOCOL="SSL"
    SASL_MECHANISM=""
    SASL_USERNAME=""
    SASL_PASSWORD=""
    INCLUDE_METADATA="true"
    VERBOSE="false"
    
    # Read configuration file
    while IFS='=' read -r key value; do
        # Skip comments and empty lines
        if [[ $key =~ ^[[:space:]]*# ]] || [[ -z $key ]]; then
            continue
        fi
        
        # Remove leading/trailing whitespace
        key=$(echo "$key" | xargs)
        value=$(echo "$value" | xargs)
        
        case $key in
            cluster.arn)
                CLUSTER_ARN="$value"
                ;;
            region|aws.region)
                REGION="$value"
                ;;
            security.protocol)
                SECURITY_PROTOCOL="$value"
                ;;
            sasl.mechanism)
                SASL_MECHANISM="$value"
                ;;
            sasl.username)
                SASL_USERNAME="$value"
                ;;
            sasl.password)
                SASL_PASSWORD="$value"
                ;;
            include.metadata)
                INCLUDE_METADATA="$value"
                ;;
            verbose)
                VERBOSE="$value"
                ;;
        esac
    done < "$config_file"
    
    # Override verbose setting if specified on command line
    if [[ -n "$VERBOSE_OVERRIDE" ]]; then
        VERBOSE="$VERBOSE_OVERRIDE"
    fi
    
    # Validate required configuration
    if [[ -z "$CLUSTER_ARN" ]]; then
        print_error "cluster.arn is required in msk.config"
        print_error "Please add: cluster.arn=arn:aws:kafka:region:account:cluster/name/id"
        exit 1
    fi
    
    print_info "Configuration loaded successfully"
    print_info "Cluster ARN: $CLUSTER_ARN"
    print_info "Region: $REGION"
    print_info "Security Protocol: $SECURITY_PROTOCOL"
    if [[ -n "$SASL_MECHANISM" ]]; then
        print_info "SASL Mechanism: $SASL_MECHANISM"
    fi
}

# Check if Java is available
check_java() {
    if ! command -v java &> /dev/null; then
        print_error "Java is not installed or not in PATH"
        print_error "Please install Java 11 or higher"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -n1 | awk -F '"' '{print $2}' | awk -F '.' '{print $1}')
    if [[ $JAVA_VERSION -lt 11 ]]; then
        print_error "Java 11 or higher is required (found Java $JAVA_VERSION)"
        exit 1
    fi
    
    print_info "Using Java version: $(java -version 2>&1 | head -n1)"
}

# Build the application if needed
build_application() {
    if [[ ! -f "target/msk-acl-extractor.jar" ]]; then
        print_info "Building the MSK ACL extractor..."
        if [[ "$VERBOSE" == "true" ]]; then
            mvn clean package
        else
            mvn clean package -q
        fi
        print_success "Application built successfully"
    else
        print_info "Using existing compiled application"
    fi
}

# Run the MSK ACL and Topic extraction
run_extraction() {
    print_info "Starting MSK ACL, Topic, and Schema extraction..."
    print_info "This will extract:"
    print_info "  • All ACLs → generated_jsons/msk_acls.json"
    print_info "  • All topics with configurations → generated_jsons/msk_topics.json"
    print_info "  • All schemas from Glue Schema Registry → generated_jsons/msk_schemas.json"
    print_info "  • Cluster metadata (if enabled)"
    
    # Construct Java command
    JAVA_CMD="java -jar target/msk-acl-extractor.jar"
    JAVA_CMD="$JAVA_CMD --cluster-arn \"$CLUSTER_ARN\""
    JAVA_CMD="$JAVA_CMD --region \"$REGION\""
    JAVA_CMD="$JAVA_CMD --security-protocol \"$SECURITY_PROTOCOL\""
    
    # Add optional parameters
    if [[ -n "$SASL_MECHANISM" ]]; then
        JAVA_CMD="$JAVA_CMD --sasl-mechanism \"$SASL_MECHANISM\""
    fi
    
    if [[ -n "$SASL_USERNAME" ]]; then
        JAVA_CMD="$JAVA_CMD --sasl-username \"$SASL_USERNAME\""
    fi
    
    if [[ -n "$SASL_PASSWORD" ]]; then
        JAVA_CMD="$JAVA_CMD --sasl-password \"$SASL_PASSWORD\""
    fi
    
    if [[ "$INCLUDE_METADATA" == "false" ]]; then
        JAVA_CMD="$JAVA_CMD --no-metadata"
    fi
    
    if [[ "$VERBOSE" == "true" ]]; then
        JAVA_CMD="$JAVA_CMD --verbose"
    fi
    
    if [[ "$VERBOSE" == "true" ]]; then
        print_info "Running command: $JAVA_CMD"
    fi
    
    # Run the extraction
    if eval $JAVA_CMD; then
        print_success "Extraction completed successfully!"
        
        # Display output file info
        if [[ -f "generated_jsons/msk_acls.json" ]]; then
            ACL_FILE_SIZE=$(wc -c < "generated_jsons/msk_acls.json")
            print_success "ACLs exported: generated_jsons/msk_acls.json (${ACL_FILE_SIZE} bytes)"
        fi
        
        if [[ -f "generated_jsons/msk_topics.json" ]]; then
            TOPIC_FILE_SIZE=$(wc -c < "generated_jsons/msk_topics.json")
            print_success "Topics exported: generated_jsons/msk_topics.json (${TOPIC_FILE_SIZE} bytes)"
        fi
        
        if [[ -f "generated_jsons/msk_schemas.json" ]]; then
            SCHEMA_FILE_SIZE=$(wc -c < "generated_jsons/msk_schemas.json")
            print_success "Schemas exported: generated_jsons/msk_schemas.json (${SCHEMA_FILE_SIZE} bytes)"
        fi
        
        # Show summary if verbose and jq is available
        if [[ "$VERBOSE" == "true" ]] && command -v jq &> /dev/null; then
            if [[ -f "generated_jsons/msk_acls.json" ]]; then
                ACL_COUNT=$(jq -r '.acl_count // 0' "generated_jsons/msk_acls.json" 2>/dev/null || echo "0")
                print_info "ACL count: $ACL_COUNT"
            fi
            if [[ -f "generated_jsons/msk_topics.json" ]]; then
                TOPIC_COUNT=$(jq -r '.topic_count // 0' "generated_jsons/msk_topics.json" 2>/dev/null || echo "0")
                print_info "Topic count: $TOPIC_COUNT"
            fi
            if [[ -f "generated_jsons/msk_schemas.json" ]]; then
                SCHEMA_COUNT=$(jq -r '.schema_count // 0' "generated_jsons/msk_schemas.json" 2>/dev/null || echo "0")
                print_info "Schema count: $SCHEMA_COUNT"
            fi
        fi
        
        print_success "✅ MSK data extraction completed!"
        print_info ""
        print_info "Generated files:"
        print_info "  📄 generated_jsons/msk_acls.json    - ACLs and cluster metadata"
        print_info "  📄 generated_jsons/msk_topics.json  - Topics and configurations"
        print_info "  📄 generated_jsons/msk_schemas.json - Schemas from Glue Schema Registry"
        print_info ""
        print_info "Next steps:"
        print_info "1. Review the generated files"
        print_info "2. Run the RBAC converter (if needed):"
        print_info "   ./scripts/convert-acl-to-rbac.sh -e env-12345 -c lkc-67890"
        
    else
        print_error "❌ Extraction failed!"
        exit 1
    fi
}

# Main execution
main() {
    print_info "MSK ACL and Topic Extractor"
    print_info "============================"
    print_info ""
    
    # Perform setup
    read_config
    check_java
    
    # Build and run
    build_application
    run_extraction
}

# Run main function
main 