#!/bin/bash

# Apply RBAC Role Bindings to Confluent Cloud
# This script reads the generated RBAC JSON and applies role bindings using Confluent Cloud Java API
# It uses service account IDs from cc_service_accounts.json file for the principal field

set -e

# Default values
RBAC_FILE="generated_jsons/cc_jsons/cc_rbac.json"
SERVICE_ACCOUNTS_FILE="generated_jsons/cc_jsons/cc_service_accounts.json"
CONFIG_FILE="ccloud.config"
OUTPUT_FILE="generated_jsons/cc_jsons/cc_rbac_applied.json"
DRY_RUN=false
VERBOSE=false
FORCE=false
SKIP_EXISTING=true

# Icons
RBAC_ICON="🔐"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
PURPLE='\033[0;35m'
NC='\033[0m' # No Color

# Function to display help
show_help() {
    cat << EOF
Apply RBAC Role Bindings to Confluent Cloud

Usage: $0 [OPTIONS]

Description:
    This script applies RBAC role bindings to Confluent Cloud using service account IDs 
    from the cc_service_accounts.json file. The principal field in role bindings will use 
    the format "User:{service_account_id}" where service_account_id comes from the 
    cc_service_accounts.json file.

    Example role binding format:
    {
        "principal": "User:sa-abc123",
        "role_name": "DeveloperRead", 
        "crn_pattern": "crn://confluent.cloud/organization=.../topic=my-topic"
    }

Options:
    -f, --file FILE         RBAC JSON file (default: generated_jsons/cc_jsons/cc_rbac.json)
    -s, --service-accounts FILE  Service accounts JSON file (default: generated_jsons/cc_jsons/cc_service_accounts.json)
    -d, --dry-run           Show CLI commands without executing them
    -v, --verbose           Enable verbose output
    -h, --help              Show this help message

Examples:
    # Apply role bindings from default files
    $0

    # Dry run to see what commands would be executed
    $0 --dry-run

    # Apply from custom files with verbose output
    $0 -f my_rbac.json -s my_service_accounts.json -v

Prerequisites:
    - Java 11 or higher installed
    - Confluent Cloud API credentials in ccloud.config
    - Service accounts file (cc_service_accounts.json) with valid service account IDs
    - Access to target environment and cluster

Workflow:
    1. Load service account name-to-ID mappings from cc_service_accounts.json
    2. Parse the RBAC JSON file
    3. Apply role bindings using service account IDs as principals
    4. Verify the applied role bindings
    5. Report success/failure for each operation

Note: Service accounts must be created first using create-cc-service-accounts.sh

EOF
}

# Function to print colored output with icons
print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

print_processing() {
    echo -e "${CYAN}⏳ $1${NC}"
}

print_file() {
    echo -e "${PURPLE}📄 $1${NC}"
}

print_stats() {
    echo -e "${GREEN}📊 $1${NC}"
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
            RBAC_FILE="$2"
            shift 2
            ;;
        -s|--service-accounts)
            SERVICE_ACCOUNTS_FILE="$2"
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
    print_processing "Checking prerequisites..."
    
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
    
    # Check if unified JAR exists
    if [[ ! -f "release/msk-to-confluent-cloud.jar" ]]; then
        print_error "Unified JAR not found: release/msk-to-confluent-cloud.jar"
        print_error "Please build the project first: mvn clean package && cp target/msk-to-confluent-cloud.jar release/"
        exit 1
    fi
    
    print_success "Prerequisites check passed"
}

# Show file summary
show_file_summary() {
    print_info "Configuration Summary:"
    echo "  📄 RBAC file: $RBAC_FILE"
    echo "  👤 Service accounts file: $SERVICE_ACCOUNTS_FILE"
    echo "  🔍 Dry run: $DRY_RUN"
    echo "  📝 Verbose: $VERBOSE"
    echo
    
    # Show service accounts summary if file exists
    if [[ -f "$SERVICE_ACCOUNTS_FILE" ]]; then
        local total=$(jq -r '.metadata.total_principals_processed // 0' "$SERVICE_ACCOUNTS_FILE" 2>/dev/null || echo "0")
        local created=$(jq -r '.metadata.created_count // 0' "$SERVICE_ACCOUNTS_FILE" 2>/dev/null || echo "0")
        local existing=$(jq -r '.metadata.existing_count // 0' "$SERVICE_ACCOUNTS_FILE" 2>/dev/null || echo "0")
        
        print_stats "Service Accounts Summary:"
        echo "  📊 Total processed: $total"
        echo "  ✅ Created: $created"
        echo "  📋 Already existing: $existing"
        echo
    fi
    
    # Show RBAC summary
    if [[ -f "$RBAC_FILE" ]]; then
        local role_bindings=$(jq -r '.role_bindings | length' "$RBAC_FILE" 2>/dev/null || echo "0")
        
        print_stats "RBAC Summary:"
        echo "  🔐 Role bindings to apply: $role_bindings"
        echo
    fi
}

# Apply RBAC using Java API
apply_rbac_with_java() {
    print_info "$RBAC_ICON Applying RBAC role bindings with Java..."
    
    # Use the correct format for the RBAC applicator
    local java_cmd="java -jar release/msk-to-confluent-cloud.jar apply"
    
    # Add arguments in the correct format
    java_cmd+=" \"$RBAC_FILE\" \"$CONFIG_FILE\""
    
    if $DRY_RUN; then
        java_cmd+=" --dry-run"
    fi
    
    if $VERBOSE; then
        java_cmd+=" --verbose"
    fi
    
    print_verbose "Executing: $java_cmd"
    
    # Execute the Java command
    if eval "$java_cmd"; then
        print_success "RBAC application completed successfully"
        return 0
    else
        print_error "RBAC application failed"
        return 1
    fi
}

# This function is now replaced by apply_rbac_with_java
apply_role_bindings() {
    apply_rbac_with_java
}

# Verify applied role bindings (handled by Java application)
verify_role_bindings() {
    # Verification is now handled within the Java application
    print_info "Role binding verification is handled by the Java application"
}

# Convert ACLs to RBAC format
convert_acls_to_rbac() {
    print_processing "Converting MSK ACLs to RBAC format..."
    
    # Check if MSK ACLs file exists
    local msk_acls_file="generated_jsons/msk_jsons/msk_acls.json"
    if [[ ! -f "$msk_acls_file" ]]; then
        print_error "MSK ACLs file not found: $msk_acls_file"
        print_error "Please run extract-msk-metadata.sh first"
        exit 1
    fi
    
    # Always regenerate RBAC file to ensure it's up-to-date
    print_info "Regenerating RBAC file from latest MSK ACLs..."
    
    # Read environment and cluster from config
    local environment_id cluster_id
    if [[ -f "$CONFIG_FILE" ]]; then
        environment_id=$(grep -E '^confluent\.cloud\.environment=' "$CONFIG_FILE" | cut -d'=' -f2 | tr -d '"' || echo "")
        cluster_id=$(grep -E '^confluent\.cloud\.cluster=' "$CONFIG_FILE" | cut -d'=' -f2 | tr -d '"' || echo "")
    fi
    
    if [[ -z "$environment_id" ]] || [[ -z "$cluster_id" ]]; then
        print_error "Environment ID and Cluster ID must be configured in $CONFIG_FILE"
        print_error "Please add: confluent.cloud.environment=env-xxxxx and confluent.cloud.cluster=lkc-xxxxx"
        exit 1
    fi
    
    # Run the conversion
    if $DRY_RUN; then
        print_info "Would convert ACLs to RBAC format (dry run)"
        print_info "Command: ./scripts/convert-acl-to-rbac.sh -e $environment_id -c $cluster_id"
    else
        # Always run the conversion to ensure fresh RBAC data
        local convert_cmd="./scripts/convert-acl-to-rbac.sh -e $environment_id -c $cluster_id"
        if $VERBOSE; then
            convert_cmd+=" -v"
        fi
        
        print_verbose "Running: $convert_cmd"
        print_info "Converting ACLs to RBAC format (this may take a moment)..."
        
        if $VERBOSE; then
            # Show output in verbose mode
            if $convert_cmd; then
                print_success "ACLs converted to RBAC format successfully"
            else
                print_error "Failed to convert ACLs to RBAC format"
                exit 1
            fi
        else
            # Suppress output in normal mode
            if $convert_cmd >/dev/null 2>&1; then
                print_success "ACLs converted to RBAC format successfully"
            else
                print_error "Failed to convert ACLs to RBAC format"
                print_error "Run with -v flag to see detailed conversion output"
                exit 1
            fi
        fi
    fi
}

# Main execution
main() {
    print_info "🔐 Confluent Cloud RBAC Role Binding Applicator"
    print_info "=============================================="
    echo
    
    # Convert ACLs to RBAC format first
    convert_acls_to_rbac
    echo
    
    # Show file summary
    show_file_summary
    
    # Perform checks
    check_prerequisites
    echo
    
    # Apply role bindings
    apply_role_bindings
    
    # Verify results
    verify_role_bindings
    
    if $DRY_RUN; then
        print_info "Dry run completed. Use without --dry-run to apply changes."
    else
        print_success "RBAC role bindings application completed! 🎉"
        echo
        print_info "Next steps:"
        echo "1. 📋 Verify role bindings in Confluent Cloud console"
        echo "2. 🧪 Test application access with new permissions"
        echo "3. 📊 Monitor access logs for any issues"
    fi
}

# Run main function
main 