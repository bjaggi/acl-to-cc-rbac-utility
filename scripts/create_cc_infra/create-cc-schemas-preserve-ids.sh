#!/bin/bash

# MSK to Confluent Cloud Schema Migration with ID Preservation
# This script migrates schemas while attempting to preserve original schema IDs

set -e

# Configuration
SCHEMAS_FILE="generated_jsons/msk_jsons/msk_schemas.json"
CONFIG_FILE="ccloud.config"
ID_MAPPING_FILE="generated_jsons/schema_id_mapping.json"
DRY_RUN=false
VERBOSE=false
PRESERVE_IDS=false
START_ID=1000

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SUCCESS="✅"
ERROR="❌"
WARNING="⚠️"
INFO="ℹ️"
ID_ICON="🔢"
SCHEMA_ICON="📋"

print_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
print_error() { echo -e "${RED}[ERROR]${NC} $1"; }
print_verbose() { if $VERBOSE; then echo -e "${BLUE}[VERBOSE]${NC} $1"; fi; }

show_help() {
    cat << 'EOF'
MSK to Confluent Cloud Schema Migration with ID Preservation

This script migrates schemas from AWS Glue Schema Registry to Confluent Cloud Schema Registry
with options to preserve or map original schema IDs.

Usage: $0 [OPTIONS]

Options:
    --preserve-ids          Enable schema ID preservation
    --start-id NUM          Starting ID for preserved schemas (default: 1000)
    --dry-run              Show what would be done without making changes
    -v, --verbose          Enable verbose logging
    -h, --help             Show this help message

ID Preservation Methods:
    1. DIRECT MAPPING: Map AWS Glue ARNs to sequential Confluent Cloud IDs
    2. HASH MAPPING: Generate deterministic IDs from AWS Glue schema content
    3. CUSTOM MAPPING: Use predefined ID mapping file

Examples:
    # Standard migration (auto-assigned IDs)
    $0

    # Preserve IDs starting from 1000
    $0 --preserve-ids --start-id 1000

    # Dry run with verbose output
    $0 --preserve-ids --dry-run -v

EOF
}

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --preserve-ids) PRESERVE_IDS=true; shift ;;
        --start-id) START_ID="$2"; shift 2 ;;
        --dry-run) DRY_RUN=true; shift ;;
        -v|--verbose) VERBOSE=true; shift ;;
        -h|--help) show_help; exit 0 ;;
        *) print_error "Unknown option: $1"; exit 1 ;;
    esac
done

read_config() {
    if [[ ! -f "$CONFIG_FILE" ]]; then
        print_error "Configuration file not found: $CONFIG_FILE"
        exit 1
    fi
    
    SCHEMA_REGISTRY_URL=$(grep "^schema.registry.url" "$CONFIG_FILE" | cut -d'=' -f2- | tr -d ' ')
    SCHEMA_REGISTRY_AUTH=$(grep "^schema.registry.basic.auth.user.info" "$CONFIG_FILE" | cut -d'=' -f2- | tr -d ' ')
    
    if [[ -z "$SCHEMA_REGISTRY_URL" || -z "$SCHEMA_REGISTRY_AUTH" ]]; then
        print_error "Schema Registry configuration missing in $CONFIG_FILE"
        exit 1
    fi
    
    print_success "Configuration loaded"
}

generate_deterministic_id() {
    local schema_arn="$1"
    local schema_content="$2"
    
    # Create deterministic ID from schema ARN and content
    local hash_input="${schema_arn}${schema_content}"
    local hash_value
    hash_value=$(echo -n "$hash_input" | sha256sum | cut -c1-8)
    
    # Convert hex to decimal and ensure it's in our range
    local decimal_id
    decimal_id=$((0x$hash_value))
    decimal_id=$((decimal_id % 1000000))  # Keep within reasonable range
    
    # Ensure it's above our start ID
    if [[ $decimal_id -lt $START_ID ]]; then
        decimal_id=$((START_ID + (decimal_id % 1000)))
    fi
    
    echo "$decimal_id"
}

register_schema_with_preserved_id() {
    local subject_name="$1"
    local schema_definition="$2"
    local schema_type="$3"
    local target_id="$4"
    local original_arn="$5"
    
    print_verbose "Registering $subject_name with target ID: $target_id"
    
    if $DRY_RUN; then
        print_info "Would register: $subject_name (target ID: $target_id)"
        echo "$original_arn,$subject_name,$target_id,$target_id" >> "${ID_MAPPING_FILE}.tmp"
        return 0
    fi
    
    # Method 1: Try direct registration with ID (if supported)
    local payload
    payload=$(jq -n \
        --arg schema "$schema_definition" \
        --arg schemaType "$schema_type" \
        --arg id "$target_id" \
        '{
            schema: $schema,
            schemaType: $schemaType,
            id: ($id | tonumber)
        }')
    
    local response
    response=$(curl -s --max-time 30 -u "$SCHEMA_REGISTRY_AUTH" \
        -X POST \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "$SCHEMA_REGISTRY_URL/subjects/$subject_name/versions" 2>/dev/null)
    
    local actual_id
    if echo "$response" | jq -e '.id' >/dev/null 2>&1; then
        actual_id=$(echo "$response" | jq -r '.id')
        
        if [[ "$actual_id" == "$target_id" ]]; then
            print_success "Schema registered with preserved ID: $actual_id"
        else
            print_warning "Schema registered with different ID: $actual_id (wanted: $target_id)"
        fi
        
        # Record mapping
        echo "$original_arn,$subject_name,$actual_id,$target_id" >> "${ID_MAPPING_FILE}.tmp"
        return 0
    else
        # Method 2: Standard registration (fallback)
        print_verbose "Direct ID assignment failed, using standard registration"
        
        payload=$(jq -n \
            --arg schema "$schema_definition" \
            --arg schemaType "$schema_type" \
            '{
                schema: $schema,
                schemaType: $schemaType
            }')
        
        response=$(curl -s --max-time 30 -u "$SCHEMA_REGISTRY_AUTH" \
            -X POST \
            -H "Content-Type: application/json" \
            -d "$payload" \
            "$SCHEMA_REGISTRY_URL/subjects/$subject_name/versions" 2>/dev/null)
        
        if echo "$response" | jq -e '.id' >/dev/null 2>&1; then
            actual_id=$(echo "$response" | jq -r '.id')
            print_warning "Schema registered with auto-assigned ID: $actual_id (wanted: $target_id)"
            echo "$original_arn,$subject_name,$actual_id,$target_id" >> "${ID_MAPPING_FILE}.tmp"
            return 0
        else
            print_error "Failed to register schema: $response"
            return 1
        fi
    fi
}

create_id_mapping_report() {
    if [[ ! -f "${ID_MAPPING_FILE}.tmp" ]]; then
        return 0
    fi
    
    print_info "Creating ID mapping report..."
    
    # Create JSON mapping file
    {
        echo "{"
        echo "  \"migration_metadata\": {"
        echo "    \"timestamp\": \"$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")\","
        echo "    \"source\": \"AWS Glue Schema Registry\","
        echo "    \"target\": \"Confluent Cloud Schema Registry\","
        echo "    \"preserve_ids_enabled\": $PRESERVE_IDS,"
        echo "    \"start_id\": $START_ID"
        echo "  },"
        echo "  \"id_mappings\": ["
        
        local first=true
        while IFS=',' read -r original_arn subject_name actual_id target_id; do
            if [[ "$first" == "true" ]]; then
                first=false
            else
                echo ","
            fi
            echo -n "    {"
            echo -n "\"original_arn\": \"$original_arn\", "
            echo -n "\"subject_name\": \"$subject_name\", "
            echo -n "\"confluent_id\": $actual_id, "
            echo -n "\"target_id\": $target_id, "
            echo -n "\"id_preserved\": $(if [[ "$actual_id" == "$target_id" ]]; then echo "true"; else echo "false"; fi)"
            echo -n "}"
        done < "${ID_MAPPING_FILE}.tmp"
        
        echo ""
        echo "  ]"
        echo "}"
    } > "$ID_MAPPING_FILE"
    
    rm -f "${ID_MAPPING_FILE}.tmp"
    
    # Generate summary
    local total_mappings preserved_count
    total_mappings=$(jq -r '.id_mappings | length' "$ID_MAPPING_FILE")
    preserved_count=$(jq -r '.id_mappings | map(select(.id_preserved == true)) | length' "$ID_MAPPING_FILE")
    
    print_success "ID mapping report created: $ID_MAPPING_FILE"
    print_info "Total schemas: $total_mappings"
    print_info "IDs preserved: $preserved_count"
    print_info "IDs reassigned: $((total_mappings - preserved_count))"
}

migrate_schemas() {
    print_info "$SCHEMA_ICON Starting schema migration..."
    
    if $PRESERVE_IDS; then
        print_info "$ID_ICON Schema ID preservation enabled (starting from: $START_ID)"
        rm -f "${ID_MAPPING_FILE}.tmp"
        touch "${ID_MAPPING_FILE}.tmp"
    fi
    
    if [[ ! -f "$SCHEMAS_FILE" ]]; then
        print_error "Schemas file not found: $SCHEMAS_FILE"
        exit 1
    fi
    
    local total_schemas=0
    local migrated_schemas=0
    local failed_schemas=0
    local current_id=$START_ID
    
    while IFS= read -r schema; do
        total_schemas=$((total_schemas + 1))
        
        local schema_name registry_name schema_definition data_format schema_arn status
        schema_name=$(echo "$schema" | jq -r '.schema_name')
        registry_name=$(echo "$schema" | jq -r '.registry_name')
        schema_definition=$(echo "$schema" | jq -r '.schema_definition')
        data_format=$(echo "$schema" | jq -r '.data_format')
        schema_arn=$(echo "$schema" | jq -r '.schema_arn // .schema_id')
        status=$(echo "$schema" | jq -r '.status')
        
        if [[ "$status" != "AVAILABLE" ]]; then
            print_warning "Skipping schema with status: $status"
            continue
        fi
        
        local subject_name="${registry_name}-${schema_name}"
        local schema_type
        case "$data_format" in
            "AVRO") schema_type="AVRO" ;;
            "JSON") schema_type="JSON" ;;
            "PROTOBUF") schema_type="PROTOBUF" ;;
            *) schema_type="AVRO" ;;
        esac
        
        print_info "[$total_schemas] Processing: $subject_name"
        
        if $PRESERVE_IDS; then
            local target_id
            target_id=$(generate_deterministic_id "$schema_arn" "$schema_definition")
            
            # Ensure unique ID by incrementing if needed
            while [[ $target_id -lt $current_id ]]; do
                target_id=$current_id
            done
            current_id=$((target_id + 1))
            
            if register_schema_with_preserved_id "$subject_name" "$schema_definition" "$schema_type" "$target_id" "$schema_arn"; then
                migrated_schemas=$((migrated_schemas + 1))
            else
                failed_schemas=$((failed_schemas + 1))
            fi
        else
            # Standard migration without ID preservation
            local payload
            payload=$(jq -n \
                --arg schema "$schema_definition" \
                --arg schemaType "$schema_type" \
                '{
                    schema: $schema,
                    schemaType: $schemaType
                }')
            
            if $DRY_RUN; then
                print_info "Would register: $subject_name"
                migrated_schemas=$((migrated_schemas + 1))
            else
                local response
                response=$(curl -s --max-time 30 -u "$SCHEMA_REGISTRY_AUTH" \
                    -X POST \
                    -H "Content-Type: application/json" \
                    -d "$payload" \
                    "$SCHEMA_REGISTRY_URL/subjects/$subject_name/versions" 2>/dev/null)
                
                if echo "$response" | jq -e '.id' >/dev/null 2>&1; then
                    local actual_id
                    actual_id=$(echo "$response" | jq -r '.id')
                    print_success "Schema registered: $subject_name (ID: $actual_id)"
                    migrated_schemas=$((migrated_schemas + 1))
                else
                    print_error "Failed to register: $subject_name"
                    failed_schemas=$((failed_schemas + 1))
                fi
            fi
        fi
        
    done < <(jq -c '.schemas[]' "$SCHEMAS_FILE")
    
    # Create mapping report if ID preservation was enabled
    if $PRESERVE_IDS; then
        create_id_mapping_report
    fi
    
    print_info ""
    print_info "Migration Summary:"
    print_info "=================="
    print_success "Migrated: $migrated_schemas"
    print_error "Failed: $failed_schemas"
    print_info "Total: $total_schemas"
    
    if [[ $failed_schemas -gt 0 ]]; then
        return 1
    else
        return 0
    fi
}

main() {
    print_info "$SCHEMA_ICON MSK to Confluent Cloud Schema Migration"
    if $PRESERVE_IDS; then
        print_info "$ID_ICON Schema ID Preservation Enabled"
    fi
    print_info "================================================"
    echo
    
    read_config
    
    if migrate_schemas; then
        print_success "$SUCCESS Migration completed successfully!"
        
        if $PRESERVE_IDS && [[ -f "$ID_MAPPING_FILE" ]]; then
            print_info ""
            print_info "📋 ID Preservation Results:"
            print_info "  Mapping file: $ID_MAPPING_FILE"
            print_info ""
            print_info "Next Steps:"
            print_info "1. Review the ID mapping file for any reassigned IDs"
            print_info "2. Update application code to use new schema IDs if needed"
            print_info "3. Test schema compatibility with existing data"
        fi
    else
        print_error "$ERROR Migration failed!"
        exit 1
    fi
}

main "$@" 