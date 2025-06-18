#!/bin/bash

# Create Topics in Confluent Cloud
# This script reads MSK topics from JSON and creates them in Confluent Cloud

set -e

# Default values
TOPICS_FILE="generated_jsons/msk_jsons/msk_topics.json"
CONFIG_FILE="ccloud.config"
OUTPUT_FILE="generated_jsons/cc_jsons/cc_topics_created.json"
DRY_RUN=false
VERBOSE=false
FORCE=false
SKIP_EXISTING=true
PARTITIONS=""
REPLICATION_FACTOR=""
TOPIC_ICON="📝"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to display help
show_help() {
    cat << EOF
Create Topics in Confluent Cloud

Usage: $0 [OPTIONS]

Options:
    -f, --file FILE         MSK topics JSON file (default: generated_jsons/msk_jsons/msk_topics.json)
    -d, --dry-run           Show what topics would be created without creating them
    -v, --verbose           Enable verbose output
    -h, --help              Show this help message

Examples:
    # Create topics from default file
    $0

    # Dry run to see what topics would be created
    $0 --dry-run

    # Create topics from custom file with verbose output
    $0 -f my_topics.json -v

Prerequisites:
    - Java 11 or higher installed
    - Confluent Cloud API credentials in ccloud.config
    - Access to target environment and cluster

The script will:
1. Parse the MSK topics JSON file
2. Check for existing topics in Confluent Cloud
3. Create missing topics with their configurations
4. Skip internal topics and duplicates
5. Report success/failure for each topic

EOF
}

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

print_verbose() {
    if [[ "$VERBOSE" == "true" ]]; then
        echo -e "${BLUE}[VERBOSE]${NC} $1"
    fi
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -f|--file)
            TOPICS_FILE="$2"
            shift 2
            ;;
        -d|--dry-run)
            DRY_RUN=true
            shift
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

# Check prerequisites
check_prerequisites() {
    print_info "Checking prerequisites..."
    
    # Check for required tools
    if ! command -v jq &> /dev/null; then
        print_error "jq is required but not installed. Please install jq."
        exit 1
    fi
    
    if ! command -v java &> /dev/null; then
        print_error "Java is required but not installed. Please install Java 8 or higher."
        exit 1
    fi
    
    # Check if unified JAR exists
    if [[ ! -f "release/msk-to-confluent-cloud.jar" ]]; then
        print_error "Unified JAR not found: release/msk-to-confluent-cloud.jar"
        print_error "Please build the project first: mvn clean package && cp target/msk-to-confluent-cloud.jar release/"
        exit 1
    fi
    
    print_success "Prerequisites check passed"
    
    # Check if topics file exists
    if [[ ! -f "$TOPICS_FILE" ]]; then
        print_error "Topics file not found: $TOPICS_FILE"
        print_info "Please run ./scripts/extract_msk_metadata/extract-msk-metadata.sh first to generate the topics file"
        exit 1
    fi
    
    # Check if file is valid JSON
    if ! python3 -c "import json; json.load(open('$TOPICS_FILE'))" 2>/dev/null; then
        print_error "Invalid JSON in topics file: $TOPICS_FILE"
        exit 1
    fi
    
    print_info "Topics file validated: $TOPICS_FILE"
    
    # Check if ccloud.config exists
    if [[ ! -f "ccloud.config" ]]; then
        print_error "Confluent Cloud config file not found: ccloud.config"
        print_info "Please create ccloud.config with your Confluent Cloud credentials"
        exit 1
    fi
    
    print_info "Configuration file found: ccloud.config"
}

# Create topics using Java API
create_topics_with_java() {
    print_info "$TOPIC_ICON Creating topics using Java..."
    
    local java_cmd="java -jar release/msk-to-confluent-cloud.jar create-topics"
    
    # Add common arguments
    java_cmd+=" --topics-file \"$TOPICS_FILE\""
    java_cmd+=" --config-file \"$CONFIG_FILE\""
    java_cmd+=" --output-file \"$OUTPUT_FILE\""
    
    # Add optional arguments
    if $DRY_RUN; then
        java_cmd+=" --dry-run"
    fi
    
    if $VERBOSE; then
        java_cmd+=" --verbose"
    fi
    
    if $FORCE; then
        java_cmd+=" --force"
    fi
    
    if $SKIP_EXISTING; then
        java_cmd+=" --skip-existing"
    fi
    
    if [[ -n "$PARTITIONS" ]]; then
        java_cmd+=" --default-partitions $PARTITIONS"
    fi
    
    if [[ -n "$REPLICATION_FACTOR" ]]; then
        java_cmd+=" --default-replication-factor $REPLICATION_FACTOR"
    fi
    
    print_verbose "Executing: $java_cmd"
    
    # Execute the Java command
    if eval "$java_cmd"; then
        print_success "Topic creation completed successfully"
        return 0
    else
        print_error "Topic creation failed"
        return 1
    fi
}

# Main execution
main() {
    print_info "Confluent Cloud Topic Creator"
    print_info "============================="
    
    # Perform checks
    check_prerequisites
    
    # Create topics
    create_topics_with_java
    
    if $DRY_RUN; then
        print_info "Dry run completed. Use without --dry-run to create topics."
    else
        print_success "Topic creation completed! 🎉"
        print_info "Next steps:"
        print_info "1. Migrate schemas:                     ./scripts/create_cc_infra/create-cc-schemas.sh"
        print_info "2. Create service accounts:             ./scripts/create_cc_infra/create-cc-service-accounts.sh"
        print_info "3. Create RBAC permissions:             ./scripts/create_cc_infra/create-cc-rbac.sh"
        
    fi
}

# Run main function
main 