#!/bin/bash

# MSK to Confluent Cloud Schema Migration Workflow
# Complete end-to-end schema migration orchestration

set -e

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
SCHEMA_ICON="📋"
WORKFLOW_ICON="🔄"

# Default values
DRY_RUN=false
VERBOSE=false
FORCE=false
SKIP_EXTRACTION=false
COMPATIBILITY_MODE="BACKWARD"

show_help() {
    cat << 'EOF'
MSK to Confluent Cloud Schema Migration Workflow

This script orchestrates the complete end-to-end migration of schemas from MSK (AWS Glue Schema Registry) 
to Confluent Cloud Schema Registry.

Usage: $0 [OPTIONS]

Options:
    --dry-run              Show what would be done without making changes
    --skip-extraction      Skip MSK schema extraction (use existing data)
    --compatibility MODE   Schema compatibility mode (default: BACKWARD)
    --force                Overwrite existing schemas (use with caution)
    -v, --verbose          Enable verbose logging
    -h, --help             Show this help message

Workflow Steps:
1. Extract schemas from MSK (AWS Glue Schema Registry)
2. Validate extracted schema data
3. Migrate schemas to Confluent Cloud Schema Registry
4. Verify successful migration
5. Generate migration report

Examples:
    # Complete workflow with dry run
    $0 --dry-run

    # Skip extraction and migrate existing data
    $0 --skip-extraction

    # Force migration with verbose logging
    $0 --force -v

Requirements:
    - Valid MSK cluster configuration (msk.config)
    - Valid Confluent Cloud configuration (ccloud.config)
    - Schema Registry credentials configured
    - Java runtime for MSK extraction
    - jq and curl for API operations

EOF
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --skip-extraction)
            SKIP_EXTRACTION=true
            shift
            ;;
        --compatibility)
            COMPATIBILITY_MODE="$2"
            shift 2
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

print_step() {
    echo -e "${PURPLE}[STEP]${NC} $1"
}

check_prerequisites() {
    print_step "Checking prerequisites..."
    
    local missing_deps=()
    
    # Check Java
    if ! command -v java &> /dev/null; then
        missing_deps+=("java")
    fi
    
    # Check jq
    if ! command -v jq &> /dev/null; then
        missing_deps+=("jq")
    fi
    
    # Check curl
    if ! command -v curl &> /dev/null; then
        missing_deps+=("curl")
    fi
    
    if [[ ${#missing_deps[@]} -gt 0 ]]; then
        print_error "Missing required dependencies: ${missing_deps[*]}"
        print_info "Please install the missing dependencies and try again."
        exit 1
    fi
    
    # Check for required files
    if [[ ! -f "msk.config" ]]; then
        print_error "MSK configuration file not found: msk.config"
        exit 1
    fi
    
    if [[ ! -f "ccloud.config" ]]; then
        print_error "Confluent Cloud configuration file not found: ccloud.config"
        exit 1
    fi
    
    # Check for JAR file
    if [[ ! -f "release/msk-to-confluent-cloud.jar" ]]; then
        print_error "MSK utility JAR not found: release/msk-to-confluent-cloud.jar"
        print_error "Please build the project first: mvn clean package && cp target/msk-to-confluent-cloud.jar release/"
        exit 1
    fi
    
    print_success "Prerequisites check passed"
}

extract_msk_schemas() {
    if $SKIP_EXTRACTION; then
        print_info "Skipping MSK schema extraction (--skip-extraction specified)"
        
        # Check if existing schema file exists
        if [[ ! -f "generated_jsons/msk_jsons/msk_schemas.json" ]]; then
            print_error "No existing schema file found: generated_jsons/msk_jsons/msk_schemas.json"
            print_error "Remove --skip-extraction to extract schemas from MSK"
            exit 1
        fi
        
        local schema_count
        schema_count=$(jq -r '.schema_count // 0' "generated_jsons/msk_jsons/msk_schemas.json")
        print_info "Using existing schema file with $schema_count schemas"
        return 0
    fi
    
    print_step "Extracting schemas from MSK..."
    
    # Read MSK configuration
    local cluster_arn region
    cluster_arn=$(grep "^cluster.arn" msk.config | cut -d'=' -f2- | tr -d ' ')
    region=$(grep "^region" msk.config | cut -d'=' -f2- | tr -d ' ')
    
    if [[ -z "$cluster_arn" ]]; then
        print_error "MSK cluster ARN not found in msk.config"
        exit 1
    fi
    
    if [[ -z "$region" ]]; then
        print_error "AWS region not found in msk.config"
        exit 1
    fi
    
    print_verbose "MSK Cluster ARN: $cluster_arn"
    print_verbose "AWS Region: $region"
    
    # Extract schemas using Java utility
    local extract_cmd="java -jar release/msk-to-confluent-cloud.jar extract"
    extract_cmd+=" --cluster-arn \"$cluster_arn\""
    extract_cmd+=" --region \"$region\""
    extract_cmd+=" --security-protocol \"SASL_SSL\""
    extract_cmd+=" --sasl-mechanism \"AWS_MSK_IAM\""
    
    if $VERBOSE; then
        extract_cmd+=" --verbose"
    fi
    
    print_verbose "Extraction command: $extract_cmd"
    
    if $DRY_RUN; then
        print_info "🔍 Would extract schemas from MSK cluster: $cluster_arn"
        return 0
    fi
    
    if eval "$extract_cmd"; then
        print_success "Schema extraction completed successfully"
        
        # Validate extracted data
        if [[ -f "generated_jsons/msk_jsons/msk_schemas.json" ]]; then
            local schema_count
            schema_count=$(jq -r '.schema_count // 0' "generated_jsons/msk_jsons/msk_schemas.json")
            print_info "Extracted $schema_count schemas from MSK"
        else
            print_error "Schema extraction failed - no output file generated"
            exit 1
        fi
    else
        print_error "Schema extraction failed"
        exit 1
    fi
}

validate_schema_data() {
    print_step "Validating extracted schema data..."
    
    local schemas_file="generated_jsons/msk_jsons/msk_schemas.json"
    
    if [[ ! -f "$schemas_file" ]]; then
        print_error "Schema file not found: $schemas_file"
        exit 1
    fi
    
    # Validate JSON structure
    if ! jq empty "$schemas_file" 2>/dev/null; then
        print_error "Invalid JSON in schema file: $schemas_file"
        exit 1
    fi
    
    # Check schema count
    local schema_count
    schema_count=$(jq -r '.schema_count // 0' "$schemas_file")
    
    if [[ "$schema_count" -eq 0 ]]; then
        print_warning "No schemas found in MSK cluster"
        print_info "Schema migration workflow completed (nothing to migrate)"
        exit 0
    fi
    
    # Validate schema structure
    local valid_schemas=0
    local invalid_schemas=0
    
    while IFS= read -r schema; do
        local schema_name status data_format
        schema_name=$(echo "$schema" | jq -r '.schema_name // "unknown"')
        status=$(echo "$schema" | jq -r '.status // "unknown"')
        data_format=$(echo "$schema" | jq -r '.data_format // "unknown"')
        
        if [[ "$status" == "AVAILABLE" && "$data_format" != "unknown" ]]; then
            valid_schemas=$((valid_schemas + 1))
            print_verbose "  ✓ Valid schema: $schema_name ($data_format)"
        else
            invalid_schemas=$((invalid_schemas + 1))
            print_verbose "  ✗ Invalid/unavailable schema: $schema_name (status: $status)"
        fi
    done < <(jq -c '.schemas[]' "$schemas_file")
    
    print_success "Schema validation completed"
    print_info "  Valid schemas: $valid_schemas"
    print_info "  Invalid/unavailable schemas: $invalid_schemas"
    
    if [[ "$valid_schemas" -eq 0 ]]; then
        print_warning "No valid schemas available for migration"
        exit 0
    fi
}

migrate_schemas() {
    print_step "Migrating schemas to Confluent Cloud..."
    
    local migration_cmd="./scripts/create_cc_infra/create-cc-schemas.sh"
    migration_cmd+=" --compatibility $COMPATIBILITY_MODE"
    
    if $DRY_RUN; then
        migration_cmd+=" --dry-run"
    fi
    
    if $VERBOSE; then
        migration_cmd+=" --verbose"
    fi
    
    if $FORCE; then
        migration_cmd+=" --force"
    fi
    
    print_verbose "Migration command: $migration_cmd"
    
    if eval "$migration_cmd"; then
        print_success "Schema migration completed successfully"
    else
        print_error "Schema migration failed"
        exit 1
    fi
}

generate_migration_report() {
    print_step "Generating migration report..."
    
    local report_file="generated_jsons/schema_migration_report.json"
    local timestamp=$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")
    
    if $DRY_RUN; then
        print_info "🔍 Would generate migration report: $report_file"
        return 0
    fi
    
    # Read source schema data
    local source_schemas=0
    if [[ -f "generated_jsons/msk_jsons/msk_schemas.json" ]]; then
        source_schemas=$(jq -r '.schema_count // 0' "generated_jsons/msk_jsons/msk_schemas.json")
    fi
    
    # Get current Schema Registry subjects (if possible)
    local target_subjects=0
    local schema_registry_url schema_registry_auth
    
    if [[ -f "ccloud.config" ]]; then
        schema_registry_url=$(grep "^schema.registry.url" "ccloud.config" | cut -d'=' -f2- | tr -d ' ')
        schema_registry_auth=$(grep "^schema.registry.basic.auth.user.info" "ccloud.config" | cut -d'=' -f2- | tr -d ' ')
        
        if [[ -n "$schema_registry_url" && -n "$schema_registry_auth" ]]; then
            local subjects_response
            subjects_response=$(curl -s -u "$schema_registry_auth" \
                -H "Content-Type: application/json" \
                "$schema_registry_url/subjects" 2>/dev/null || echo "[]")
            
            if echo "$subjects_response" | jq empty 2>/dev/null; then
                target_subjects=$(echo "$subjects_response" | jq '. | length' 2>/dev/null || echo 0)
            fi
        fi
    fi
    
    # Generate report
    local report
    report=$(jq -n \
        --arg timestamp "$timestamp" \
        --argjson source_schemas "$source_schemas" \
        --argjson target_subjects "$target_subjects" \
        --arg compatibility_mode "$COMPATIBILITY_MODE" \
        --argjson dry_run "$DRY_RUN" \
        --argjson force "$FORCE" \
        --argjson verbose "$VERBOSE" \
        '{
            migration_report: {
                timestamp: $timestamp,
                workflow_configuration: {
                    dry_run: $dry_run,
                    force: $force,
                    verbose: $verbose,
                    compatibility_mode: $compatibility_mode
                },
                source_msk: {
                    total_schemas: $source_schemas
                },
                target_confluent_cloud: {
                    total_subjects: $target_subjects
                },
                migration_status: "completed"
            }
        }')
    
    echo "$report" > "$report_file"
    print_success "Migration report generated: $report_file"
    
    # Display summary
    print_info ""
    print_info "$SCHEMA_ICON Migration Summary:"
    print_info "========================"
    print_info "  Source MSK schemas: $source_schemas"
    print_info "  Target CC subjects: $target_subjects"
    print_info "  Compatibility mode: $COMPATIBILITY_MODE"
    print_info "  Report file: $report_file"
}

main() {
    print_info "$WORKFLOW_ICON MSK to Confluent Cloud Schema Migration Workflow"
    print_info "=============================================================="
    echo
    
    # Show configuration
    print_info "Workflow Configuration:"
    print_info "  Dry run: $DRY_RUN"
    print_info "  Skip extraction: $SKIP_EXTRACTION"
    print_info "  Compatibility mode: $COMPATIBILITY_MODE"
    print_info "  Force overwrite: $FORCE"
    print_info "  Verbose: $VERBOSE"
    echo
    
    # Execute workflow steps
    check_prerequisites
    echo
    
    extract_msk_schemas
    echo
    
    validate_schema_data
    echo
    
    migrate_schemas
    echo
    
    generate_migration_report
    echo
    
    if $DRY_RUN; then
        print_info "🔍 Dry run completed successfully!"
        print_info "Remove --dry-run to perform actual migration."
    else
        print_success "$SUCCESS Schema migration workflow completed successfully! $SUCCESS"
        echo
        print_info "$ARROW Next Steps:"
        print_info "  $BULLET Verify schemas in Confluent Cloud Schema Registry console"
        print_info "  $BULLET Update application configurations to use new Schema Registry"
        print_info "  $BULLET Test schema compatibility and evolution"
        print_info "  $BULLET Update producer/consumer applications"
        print_info "  $BULLET Monitor schema usage and performance"
    fi
}

main "$@" 