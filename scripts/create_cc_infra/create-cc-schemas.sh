#!/bin/bash

# MSK to Confluent Cloud Schema Migration Script
# Migrates schemas from AWS Glue Schema Registry to Confluent Cloud Schema Registry

set -e

# Default values
SCHEMAS_FILE="generated_jsons/msk_jsons/msk_schemas.json"
CONFIG_FILE="ccloud.config"
DRY_RUN=false
VERBOSE=false
FORCE=false
COMPATIBILITY_MODE="BACKWARD"

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
SCHEMA_ICON="📋"

show_help() {
    cat << 'EOF'
MSK to Confluent Cloud Schema Migration

This script migrates schemas from AWS Glue Schema Registry (MSK) to Confluent Cloud Schema Registry.

Usage: $0 [OPTIONS]

Options:
    -s, --schemas FILE      MSK schemas JSON file (default: generated_jsons/msk_jsons/msk_schemas.json)
    -c, --config FILE       Confluent Cloud config file (default: ccloud.config)
    --compatibility MODE   Schema compatibility mode (default: BACKWARD)
    --dry-run              Show what would be done without making changes
    --force                Overwrite existing schemas (use with caution)
    -v, --verbose          Enable verbose logging
    -h, --help             Show this help message

Examples:
    # Basic schema migration
    $0

    # Dry run to see what would be migrated
    $0 --dry-run

    # Migrate with specific compatibility mode
    $0 --compatibility FULL

Requirements:
    - jq (for JSON processing)
    - curl (for API calls)
    - Valid Confluent Cloud Schema Registry credentials in config file
EOF
}

while [[ $# -gt 0 ]]; do
    case $1 in
        -s|--schemas)
            SCHEMAS_FILE="$2"
            shift 2
            ;;
        -c|--config)
            CONFIG_FILE="$2"
            shift 2
            ;;
        --compatibility)
            COMPATIBILITY_MODE="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --force)
            FORCE=true
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

check_prerequisites() {
    print_info "Checking prerequisites..."
    
    if ! command -v jq &> /dev/null; then
        print_error "jq is not installed. Please install jq for JSON processing."
        print_info "Ubuntu/Debian: sudo apt-get install jq"
        exit 1
    fi
    
    if ! command -v curl &> /dev/null; then
        print_error "curl is not installed. Please install curl for API calls."
        exit 1
    fi
    
    print_success "Prerequisites check passed"
}

read_config() {
    print_info "Reading configuration from: $CONFIG_FILE"
    
    if [[ ! -f "$CONFIG_FILE" ]]; then
        print_error "Configuration file not found: $CONFIG_FILE"
        exit 1
    fi
    
    SCHEMA_REGISTRY_URL=$(grep "^schema.registry.url" "$CONFIG_FILE" | cut -d'=' -f2- | tr -d ' ')
    SCHEMA_REGISTRY_AUTH=$(grep "^schema.registry.basic.auth.user.info" "$CONFIG_FILE" | cut -d'=' -f2- | tr -d ' ')
    
    if [[ -z "$SCHEMA_REGISTRY_URL" ]]; then
        print_error "Schema Registry URL not found in config file"
        print_error "Please add: schema.registry.url=https://your-schema-registry-url"
        exit 1
    fi
    
    if [[ -z "$SCHEMA_REGISTRY_AUTH" ]]; then
        print_error "Schema Registry authentication not found in config file"
        print_error "Please add: schema.registry.basic.auth.user.info=key:secret"
        exit 1
    fi
    
    print_success "Configuration loaded successfully"
    print_verbose "Schema Registry URL: $SCHEMA_REGISTRY_URL"
    print_verbose "Authentication configured: ${SCHEMA_REGISTRY_AUTH%%:*}:***"
}

validate_schemas_file() {
    print_info "Validating schemas file: $SCHEMAS_FILE"
    
    if [[ ! -f "$SCHEMAS_FILE" ]]; then
        print_error "Schemas file not found: $SCHEMAS_FILE"
        print_error "Please run the MSK schema extractor first:"
        print_error "  ./scripts/extract_msk_metadata/extract-msk-metadata.sh"
        exit 1
    fi
    
    if ! jq empty "$SCHEMAS_FILE" 2>/dev/null; then
        print_error "Schemas file is not valid JSON: $SCHEMAS_FILE"
        exit 1
    fi
    
    SCHEMA_COUNT=$(jq -r '.schema_count // 0' "$SCHEMAS_FILE")
    if [[ "$SCHEMA_COUNT" -eq 0 ]]; then
        print_warning "No schemas found in file: $SCHEMAS_FILE"
        print_info "Nothing to migrate."
        exit 0
    fi
    
    print_success "Found $SCHEMA_COUNT schemas to migrate"
}

get_existing_subjects() {
    print_verbose "Fetching existing subjects from Schema Registry..."
    
    local response
    response=$(curl -s --max-time 30 -u "$SCHEMA_REGISTRY_AUTH" \
        -H "Content-Type: application/json" \
        "$SCHEMA_REGISTRY_URL/subjects" 2>/dev/null)
    
    local curl_exit_code=$?
    if [[ $curl_exit_code -ne 0 ]]; then
        if [[ $curl_exit_code -eq 28 ]]; then
            print_error "Connection to Schema Registry timed out"
        else
            print_error "Failed to connect to Schema Registry (exit code: $curl_exit_code)"
        fi
        return 1
    fi
    
    if echo "$response" | jq empty 2>/dev/null; then
        echo "$response" | jq -r '.[]' 2>/dev/null || echo ""
    else
        print_error "Invalid response from Schema Registry: ${response:0:200}..."
        return 1
    fi
}

subject_exists() {
    local subject_name="$1"
    local existing_subjects="$2"
    
    echo "$existing_subjects" | grep -q "^${subject_name}$"
}

convert_compatibility() {
    local aws_compatibility="$1"
    
    case "$aws_compatibility" in
        "NONE"|"DISABLED")
            echo "NONE"
            ;;
        "BACKWARD")
            echo "BACKWARD"
            ;;
        "FORWARD")
            echo "FORWARD"
            ;;
        "FULL")
            echo "FULL"
            ;;
        *)
            echo "$COMPATIBILITY_MODE"
            ;;
    esac
}

register_schema() {
    local subject_name="$1"
    local schema_definition="$2"
    local data_format="$3"
    local compatibility="$4"
    local description="$5"
    
    print_processing "Registering schema: $subject_name"
    
    if $DRY_RUN; then
        print_info "  $DRY_RUN_ICON Would register schema '$subject_name' with format '$data_format'"
        return 0
    fi
    
    # Set compatibility mode for the subject
    local compat_response
    compat_response=$(curl -s --max-time 30 -u "$SCHEMA_REGISTRY_AUTH" \
        -X PUT \
        -H "Content-Type: application/json" \
        -d "{\"compatibility\": \"$compatibility\"}" \
        "$SCHEMA_REGISTRY_URL/config/$subject_name" 2>/dev/null)
    
    print_verbose "Compatibility set to: $compatibility"
    
    # Prepare schema registration payload
    local schema_type
    case "$data_format" in
        "AVRO")
            schema_type="AVRO"
            ;;
        "JSON")
            schema_type="JSON"
            ;;
        "PROTOBUF")
            schema_type="PROTOBUF"
            ;;
        *)
            schema_type="AVRO"
            ;;
    esac
    
    local payload
    payload=$(jq -n \
        --arg schema "$schema_definition" \
        --arg schemaType "$schema_type" \
        '{
            schema: $schema,
            schemaType: $schemaType
        }')
    
    print_verbose "Registration payload: $payload"
    
    # Register the schema
    local response
    response=$(curl -s --max-time 30 -u "$SCHEMA_REGISTRY_AUTH" \
        -X POST \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "$SCHEMA_REGISTRY_URL/subjects/$subject_name/versions" 2>/dev/null)
    
    if echo "$response" | jq -e '.id' >/dev/null 2>&1; then
        local schema_id
        schema_id=$(echo "$response" | jq -r '.id')
        print_success "  $SUCCESS Schema registered successfully (ID: $schema_id)"
        return 0
    else
        print_error "  $ERROR Failed to register schema: $response"
        return 1
    fi
}

migrate_schemas() {
    print_info "$SCHEMA_ICON Starting schema migration..."
    
    local existing_subjects
    if ! existing_subjects=$(get_existing_subjects); then
        print_error "Failed to fetch existing subjects"
        exit 1
    fi
    
    local existing_count
    existing_count=$(echo "$existing_subjects" | wc -l)
    if [[ -n "$existing_subjects" ]]; then
        print_info "Found $existing_count existing subjects in Schema Registry"
    fi
    
    local total_schemas=0
    local migrated_schemas=0
    local skipped_schemas=0
    local failed_schemas=0
    
    while IFS= read -r schema; do
        total_schemas=$((total_schemas + 1))
        
        local schema_name registry_name schema_definition data_format compatibility description status
        
        schema_name=$(echo "$schema" | jq -r '.schema_name')
        registry_name=$(echo "$schema" | jq -r '.registry_name')
        schema_definition=$(echo "$schema" | jq -r '.schema_definition')
        data_format=$(echo "$schema" | jq -r '.data_format')
        compatibility=$(echo "$schema" | jq -r '.compatibility')
        description=$(echo "$schema" | jq -r '.description // empty')
        status=$(echo "$schema" | jq -r '.status')
        
        local subject_name="${registry_name}-${schema_name}"
        
        print_info "[$total_schemas] Processing schema: $subject_name"
        print_verbose "  Original name: $schema_name"
        print_verbose "  Registry: $registry_name"
        print_verbose "  Format: $data_format"
        print_verbose "  Status: $status"
        
        if [[ "$status" != "AVAILABLE" ]]; then
            print_warning "  $WARNING Skipping schema with status: $status"
            skipped_schemas=$((skipped_schemas + 1))
            continue
        fi
        
        if subject_exists "$subject_name" "$existing_subjects"; then
            if $FORCE; then
                print_warning "  $WARNING Subject exists, but --force specified. Will attempt to add new version."
            else
                print_warning "  $WARNING Subject already exists. Use --force to overwrite."
                skipped_schemas=$((skipped_schemas + 1))
                continue
            fi
        fi
        
        local cc_compatibility
        cc_compatibility=$(convert_compatibility "$compatibility")
        
        if register_schema "$subject_name" "$schema_definition" "$data_format" "$cc_compatibility" "$description"; then
            migrated_schemas=$((migrated_schemas + 1))
        else
            failed_schemas=$((failed_schemas + 1))
        fi
        
        echo
        
    done < <(jq -c '.schemas[]' "$SCHEMAS_FILE")
    
    print_info ""
    print_info "$SCHEMA_ICON Schema Migration Summary:"
    print_info "================================"
    print_info "$SUCCESS Schemas migrated: $migrated_schemas"
    print_info "$WARNING Schemas skipped: $skipped_schemas"
    print_info "$ERROR Schemas failed: $failed_schemas"
    print_info "$INFO Total schemas processed: $total_schemas"
    
    if [[ $failed_schemas -gt 0 ]]; then
        print_error "Some schemas failed to migrate. Check the logs above for details."
        return 1
    elif [[ $migrated_schemas -eq 0 && $skipped_schemas -gt 0 ]]; then
        print_success "All schemas already exist in Confluent Cloud - migration not needed!"
        return 0
    elif [[ $migrated_schemas -eq 0 ]]; then
        print_warning "No schemas were migrated."
        return 1
    else
        print_success "Schema migration completed successfully!"
        return 0
    fi
}

verify_migration() {
    if $DRY_RUN; then
        print_info "$DRY_RUN_ICON Would verify migrated schemas"
        return 0
    fi
    
    print_info "Verifying migrated schemas..."
    
    local current_subjects
    if ! current_subjects=$(get_existing_subjects); then
        print_warning "Could not verify migration - failed to fetch subjects"
        return 1
    fi
    
    local subject_count
    subject_count=$(echo "$current_subjects" | wc -l)
    print_success "Schema Registry now contains $subject_count subjects"
    
    if $VERBOSE; then
        print_info "Current subjects:"
        echo "$current_subjects" | while read -r subject; do
            print_info "  $BULLET $subject"
        done
    fi
}

main() {
    print_info "$SCHEMA_ICON MSK to Confluent Cloud Schema Migration"
    print_info "=============================================="
    echo
    
    print_info "Configuration:"
    print_info "  Schemas file: $SCHEMAS_FILE"
    print_info "  Config file: $CONFIG_FILE"
    print_info "  Compatibility mode: $COMPATIBILITY_MODE"
    print_info "  Dry run: $DRY_RUN"
    print_info "  Force overwrite: $FORCE"
    print_info "  Verbose: $VERBOSE"
    echo
    
    check_prerequisites
    read_config
    validate_schemas_file
    echo
    
    if migrate_schemas; then
        echo
        verify_migration
        
        if $DRY_RUN; then
            print_info "Dry run completed. Remove --dry-run to perform actual migration."
        else
            print_success "$SUCCESS Schema migration completed successfully! $SUCCESS"
            echo
            print_info "Next steps:"
            print_info "1. Create consumer groups (optional):   ./scripts/create_cc_infra/create-cc-consumer-groups.sh"
            print_info "2. Create service accounts:             ./scripts/create_cc_infra/create-cc-service-accounts.sh"
            print_info "3. Create RBAC permissions:             ./scripts/create_cc_infra/create-cc-rbac.sh"
        fi
    else
        print_error "$ERROR Schema migration failed!"
        exit 1
    fi
}

main "$@" 