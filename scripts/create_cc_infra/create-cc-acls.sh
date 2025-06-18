#!/bin/bash

# MSK to Confluent Cloud ACL Migration Script
# Migrates ACLs from MSK (msk_acls.json) to Confluent Cloud
# Converts IAM roles/users to Confluent Cloud service account IDs
# Uses Java Kafka Admin API instead of CLI tools

set -e

# Default values
ACLS_FILE="generated_jsons/msk_jsons/msk_acls.json"
CONFIG_FILE="ccloud.config"
SERVICE_ACCOUNTS_FILE="generated_jsons/cc_jsons/cc_service_accounts.json"
OUTPUT_FILE="generated_jsons/cc_jsons/cc_acls_migrated.json"
DRY_RUN=false
VERBOSE=false
FORCE=false
SKIP_EXISTING=true
JAVA_CLASS="com.confluent.migration.confluent.creator.ACLCreator"

# Colors and icons
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m'

SUCCESS="✅"
ERROR="❌"
WARNING="⚠️"
INFO="ℹ️"
PROCESSING="⏳"
BULLET="•"
ARROW="➤"
DRY_RUN_ICON="🔍"
ACL_ICON="🔐"
MAPPING_ICON="🔄"

show_help() {
    cat << 'EOF'
MSK to Confluent Cloud ACL Migration

This script migrates ACLs from MSK to Confluent Cloud, converting IAM roles/users 
to Confluent Cloud service account IDs and applying them using Java Kafka Admin API.

Usage: $0 [OPTIONS]

Options:
    -a, --acls FILE         MSK ACLs JSON file (default: generated_jsons/msk_jsons/msk_acls.json)
    -c, --config FILE       Confluent Cloud config file (default: ccloud.config)
    -s, --service-accounts FILE  Service accounts mapping file (default: generated_jsons/cc_jsons/cc_service_accounts.json)
    -o, --output FILE       Output file for migration results (default: generated_jsons/cc_jsons/cc_acls_migrated.json)
    --dry-run              Show what would be done without making changes
    --force                Overwrite existing ACLs
    --skip-existing        Skip existing ACLs (default: true)
    -v, --verbose          Enable verbose logging
    -h, --help             Show this help message

Migration Process:
    1. Read MSK ACLs from msk_acls.json
    2. Map MSK principals (User:role-name) to Confluent Cloud service account IDs
    3. Convert MSK ACL operations to Confluent Cloud Kafka ACL format
    4. Apply ACLs to Confluent Cloud cluster using Java Kafka Admin API
    5. Generate migration report

Principal Mapping:
    MSK Format:     User:jaggi-msk-role
    CC Format:      User:sa-abc123 (service account ID)

Examples:
    # Basic ACL migration
    $0

    # Dry run to see what would be migrated
    $0 --dry-run

    # Migrate with verbose output and force overwrite
    $0 --force -v

    # Custom files
    $0 -a my_acls.json -s my_service_accounts.json

Requirements:
    - Java 8+ with Kafka libraries
    - jq (for JSON processing)
    - Valid Confluent Cloud cluster configuration
    - Service accounts must already exist in Confluent Cloud

EOF
}

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
    echo -e "${PURPLE}[PROCESSING]${NC} $1"
}

print_verbose() {
    if $VERBOSE; then
        echo -e "${CYAN}[VERBOSE]${NC} $1"
    fi
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -a|--acls)
            ACLS_FILE="$2"
            shift 2
            ;;
        -c|--config)
            CONFIG_FILE="$2"
            shift 2
            ;;
        -s|--service-accounts)
            SERVICE_ACCOUNTS_FILE="$2"
            shift 2
            ;;
        -o|--output)
            OUTPUT_FILE="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --force)
            FORCE=true
            SKIP_EXISTING=false
            shift
            ;;
        --skip-existing)
            SKIP_EXISTING=true
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
            print_error "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
done

check_prerequisites() {
    print_info "Checking prerequisites..."
    
    # Check for required tools
    local missing_tools=()
    
    if ! command -v jq &> /dev/null; then
        missing_tools+=("jq")
    fi
    
    if ! command -v java &> /dev/null; then
        missing_tools+=("java")
    fi
    
    if [[ ${#missing_tools[@]} -gt 0 ]]; then
        print_error "Missing required tools: ${missing_tools[*]}"
        print_error "Please install the missing tools and try again"
        exit 1
    fi
    
    # Check if Java application is compiled
    if [[ ! -f "release/msk-to-confluent-cloud.jar" ]]; then
        print_error "Java application not found. Please compile first:"
        print_error "  mvn clean package && cp target/msk-to-confluent-cloud.jar release/"
        exit 1
    fi
    
    print_success "Prerequisites check passed"
}

validate_input_files() {
    print_info "Validating input files..."
    
    # Check ACLs file
    if [[ ! -f "$ACLS_FILE" ]]; then
        print_error "ACLs file not found: $ACLS_FILE"
        print_error "Please run the MSK ACL extractor first:"
        print_error "  ./scripts/extract_msk_metadata/extract-msk-metadata.sh"
        exit 1
    fi
    
    if ! jq empty "$ACLS_FILE" 2>/dev/null; then
        print_error "ACLs file is not valid JSON: $ACLS_FILE"
        exit 1
    fi
    
    # Check service accounts file
    if [[ ! -f "$SERVICE_ACCOUNTS_FILE" ]]; then
        print_error "Service accounts file not found: $SERVICE_ACCOUNTS_FILE"
        print_error "Please create service accounts first:"
        print_error "  ./scripts/create_cc_infra/create-cc-service-accounts.sh"
        exit 1
    fi
    
    if ! jq empty "$SERVICE_ACCOUNTS_FILE" 2>/dev/null; then
        print_error "Service accounts file is not valid JSON: $SERVICE_ACCOUNTS_FILE"
        exit 1
    fi
    
    # Check config file
    if [[ ! -f "$CONFIG_FILE" ]]; then
        print_error "Configuration file not found: $CONFIG_FILE"
        exit 1
    fi
    
    # Get counts
    local acl_count sa_count
    acl_count=$(jq -r '.acl_count // (.acls | length)' "$ACLS_FILE")
    sa_count=$(jq -r '.service_accounts | length' "$SERVICE_ACCOUNTS_FILE")
    
    print_success "Input files validated"
    print_info "Found $acl_count ACLs and $sa_count service accounts"
}

build_java_classpath() {
    print_verbose "Building Java classpath..."
    
    # Use the shaded JAR which contains all dependencies
    local classpath="release/msk-to-confluent-cloud.jar"
    
    echo "$classpath"
}

migrate_acls_with_java() {
    print_info "$ACL_ICON Starting ACL migration with Java..."
    
    local classpath
    classpath=$(build_java_classpath)
    
    # Prepare Java arguments
    local java_args=()
    java_args+=("-cp" "$classpath")
    java_args+=("$JAVA_CLASS")
    java_args+=("--acls-file" "$ACLS_FILE")
    java_args+=("--config-file" "$CONFIG_FILE")
    java_args+=("--service-accounts-file" "$SERVICE_ACCOUNTS_FILE")
    java_args+=("--output-file" "$OUTPUT_FILE")
    
    if $DRY_RUN; then
        java_args+=("--dry-run")
    fi
    
    if $VERBOSE; then
        java_args+=("--verbose")
    fi
    
    if $FORCE; then
        java_args+=("--force")
    fi
    
    if $SKIP_EXISTING; then
        java_args+=("--skip-existing")
    fi
    
    print_verbose "Executing: java ${java_args[*]}"
    
    # Execute Java application
    if java "${java_args[@]}"; then
        print_success "ACL migration completed successfully"
        return 0
    else
        print_error "ACL migration failed"
        return 1
    fi
}

print_summary() {
    print_info ""
    print_info "$ACL_ICON ACL Migration Summary:"
    print_info "=========================="
    
    if [[ -f "$OUTPUT_FILE" ]]; then
        local total_acls migrated_acls skipped_acls failed_acls
        
        total_acls=$(jq -r '.migration_summary.total_acls // 0' "$OUTPUT_FILE" 2>/dev/null || echo "0")
        migrated_acls=$(jq -r '.migration_summary.migrated_acls // 0' "$OUTPUT_FILE" 2>/dev/null || echo "0")
        skipped_acls=$(jq -r '.migration_summary.skipped_acls // 0' "$OUTPUT_FILE" 2>/dev/null || echo "0")
        failed_acls=$(jq -r '.migration_summary.failed_acls // 0' "$OUTPUT_FILE" 2>/dev/null || echo "0")
        
        print_success "Total ACLs processed: $total_acls"
        print_success "ACLs migrated: $migrated_acls"
        print_warning "ACLs skipped: $skipped_acls"
        print_error "ACLs failed: $failed_acls"
        
        if [[ $failed_acls -gt 0 ]]; then
            print_error "Some ACLs failed to migrate. Check the migration report for details."
            return 1
        elif [[ $migrated_acls -eq 0 ]]; then
            print_warning "No ACLs were migrated."
            return 1
        else
            print_success "ACL migration completed successfully!"
            return 0
        fi
    else
        print_error "Migration report not found: $OUTPUT_FILE"
        return 1
    fi
}

main() {
    print_info "$ACL_ICON MSK to Confluent Cloud ACL Migration (Java)"
    print_info "=================================================="
    echo
    
    # Validate prerequisites and configuration
    check_prerequisites
    validate_input_files
    
    # Perform migration using Java
    if migrate_acls_with_java; then
        print_summary
        
        print_info ""
        print_success "$SUCCESS ACL migration completed!"
        
        if [[ -f "$OUTPUT_FILE" ]]; then
            print_info "Migration report: $OUTPUT_FILE"
        fi
        
        print_info ""
        print_info "Next Steps:"
        print_info "1. Review the migration report for any failed ACLs"
        print_info "2. Test application connectivity with new ACLs"
        print_info "3. Verify ACL permissions are working as expected"
        print_info "4. Update application configurations if needed"
        
    else
        print_error "$ERROR ACL migration failed!"
        exit 1
    fi
}

# Make script executable
chmod +x "$0"

# Run main function
main "$@" 