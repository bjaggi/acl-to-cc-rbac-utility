#!/bin/bash

# Apply RBAC Role Bindings to Confluent Cloud
# This script reads the generated RBAC JSON and applies role bindings using Confluent Cloud Java API
# It uses service account IDs from cc_service_accounts.json file for the principal field

set -e

# Default values
RBAC_FILE="generated_jsons/cc_jsons/cc_rbac.json"
SERVICE_ACCOUNTS_FILE="generated_jsons/cc_jsons/cc_service_accounts.json"
DRY_RUN=false
VERBOSE=false

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
    print_processing "Validating prerequisites..."
    
    # Check if Java is available
    if ! command -v java &> /dev/null; then
        print_error "Java is not installed or not in PATH"
        print_info "Please install Java 11 or higher"
        exit 1
    fi
    
    # Check if RBAC applicator JAR exists
    if [[ ! -f "target/msk-to-confluent-cloud.jar" ]]; then
        print_error "Unified JAR not found: target/msk-to-confluent-cloud.jar"
        print_info "Please run 'mvn clean package' to build the application"
        exit 1
    fi
    
    print_success "Java RBAC applicator available"
    
    # Check if RBAC file exists
    if [[ ! -f "$RBAC_FILE" ]]; then
        print_error "RBAC file not found: $RBAC_FILE"
        exit 1
    fi
    
    # Check if service accounts file exists
    if [[ ! -f "$SERVICE_ACCOUNTS_FILE" ]]; then
        print_warning "Service accounts file not found: $SERVICE_ACCOUNTS_FILE"
        print_info "The application will fall back to API lookup for service account IDs"
        print_info "For better performance, run create-cc-service-accounts.sh first"
    else
        print_success "Service accounts file found: $SERVICE_ACCOUNTS_FILE"
    fi
    
    # Check if files are valid JSON
    if ! python3 -c "import json; json.load(open('$RBAC_FILE'))" 2>/dev/null; then
        print_error "Invalid JSON in RBAC file: $RBAC_FILE"
        exit 1
    fi
    
    if [[ -f "$SERVICE_ACCOUNTS_FILE" ]] && ! python3 -c "import json; json.load(open('$SERVICE_ACCOUNTS_FILE'))" 2>/dev/null; then
        print_error "Invalid JSON in service accounts file: $SERVICE_ACCOUNTS_FILE"
        exit 1
    fi
    
    print_info "Input files validated successfully"
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
apply_rbac_java() {
    print_processing "Applying RBAC role bindings using service account IDs from file..."
    
    # Build Java command - use the correct class and always skip service accounts for RBAC-only script
    local java_cmd="java -cp target/msk-to-confluent-cloud.jar com.confluent.utility.ConfluentCloudRBACApplicator"
    java_cmd+=" --skip-service-accounts"  # Always skip service accounts for RBAC script
    
    if $DRY_RUN; then
        java_cmd+=" --dry-run"
    fi
    
    if $VERBOSE; then
        java_cmd+=" --verbose"
    fi
    
    if $VERBOSE; then
        print_info "Executing: $java_cmd"
    fi
    
    # Execute the Java application
    if eval "$java_cmd"; then
        print_success "RBAC role bindings applied successfully"
        return 0
    else
        print_error "RBAC role binding application failed"
        return 1
    fi
}

# This function is now replaced by apply_rbac_java
apply_role_bindings() {
    apply_rbac_java
}

# Verify applied role bindings (handled by Java application)
verify_role_bindings() {
    # Verification is now handled within the Java application
    print_info "Role binding verification is handled by the Java application"
}

# Main execution
main() {
    print_info "🔐 Confluent Cloud RBAC Role Binding Applicator"
    print_info "=============================================="
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