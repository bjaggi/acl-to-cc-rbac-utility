#!/bin/bash

# Apply RBAC Role Bindings to Confluent Cloud
# This script reads the generated RBAC JSON and applies role bindings using Confluent CLI

set -e

# Default values
RBAC_FILE="cc_rbac.json"
DRY_RUN=false
VERBOSE=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to display help
show_help() {
    cat << EOF
Apply RBAC Role Bindings to Confluent Cloud

Usage: $0 [OPTIONS]

Options:
    -f, --file FILE         RBAC JSON file (default: cc_rbac.json)
    -d, --dry-run           Show CLI commands without executing them
    -v, --verbose           Enable verbose output
    -h, --help              Show this help message

Examples:
    # Apply role bindings from default file
    $0

    # Dry run to see what commands would be executed
    $0 --dry-run

    # Apply from custom file with verbose output
    $0 -f my_rbac.json -v

Prerequisites:
    - Confluent Cloud CLI installed and configured
    - Authenticated with: confluent login
    - Access to target environment and cluster

The script will:
1. Parse the RBAC JSON file
2. Generate confluent iam rbac role-binding create commands
3. Execute the commands (unless --dry-run is specified)
4. Report success/failure for each role binding

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

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -f|--file)
            RBAC_FILE="$2"
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
    # Check if Confluent CLI is installed
    if ! command -v confluent &> /dev/null; then
        print_error "Confluent CLI is not installed"
        print_info "Install from: https://docs.confluent.io/confluent-cli/current/install.html"
        exit 1
    fi
    
    print_info "Confluent CLI version: $(confluent version)"
    
    # Check if user is logged in
    if ! confluent auth list &> /dev/null; then
        print_error "Not authenticated with Confluent Cloud"
        print_info "Please run: confluent login"
        exit 1
    fi
    
    print_success "Confluent CLI authentication verified"
    
    # Check if RBAC file exists
    if [[ ! -f "$RBAC_FILE" ]]; then
        print_error "RBAC file not found: $RBAC_FILE"
        exit 1
    fi
    
    # Check if file is valid JSON
    if ! python3 -c "import json; json.load(open('$RBAC_FILE'))" 2>/dev/null; then
        print_error "Invalid JSON in RBAC file: $RBAC_FILE"
        exit 1
    fi
    
    print_info "RBAC file validated: $RBAC_FILE"
}

# Parse RBAC JSON and generate CLI commands
generate_cli_commands() {
    print_info "Parsing RBAC file and generating CLI commands..."
    
    # Use Python to parse JSON and generate commands
    python3 << EOF
import json
import sys

try:
    with open('$RBAC_FILE', 'r') as f:
        rbac_data = json.load(f)
    
    role_bindings = rbac_data.get('role_bindings', [])
    conversion_metadata = rbac_data.get('conversion_metadata', {})
    
    print(f"# Generated from: $RBAC_FILE")
    print(f"# Source cluster: {conversion_metadata.get('source_cluster', 'unknown')}")
    print(f"# Original ACL count: {conversion_metadata.get('original_acl_count', 0)}")
    print(f"# Role bindings: {len(role_bindings)}")
    print("# Commands:")
    print("")
    
    for i, binding in enumerate(role_bindings, 1):
        principal = binding.get('principal', '')
        role = binding.get('role', '')
        resource_type = binding.get('resource_type', '')
        resource_name = binding.get('resource_name', '')
        pattern_type = binding.get('pattern_type', 'LITERAL')
        environment = binding.get('environment', '')
        cluster_id = binding.get('cluster_id', '')
        
        # Generate confluent CLI command
        cmd = f"confluent iam rbac role-binding create"
        cmd += f" --principal User:{principal}"
        cmd += f" --role {role}"
        cmd += f" --resource {resource_type}:{resource_name}"
        
        if pattern_type != 'LITERAL':
            cmd += f" --pattern-type {pattern_type}"
        
        cmd += f" --environment {environment}"
        
        if resource_type.lower() != 'cluster':
            cmd += f" --kafka-cluster {cluster_id}"
        
        print(f"# Role binding {i}/{len(role_bindings)}")
        print(f"echo 'Creating role binding: {principal} -> {role} on {resource_type}:{resource_name}'")
        print(cmd)
        print("")
    
except Exception as e:
    print(f"Error parsing RBAC file: {e}", file=sys.stderr)
    sys.exit(1)
EOF
}

# Apply role bindings
apply_role_bindings() {
    local commands_file="/tmp/rbac_commands.sh"
    
    # Generate commands to temporary file
    generate_cli_commands > "$commands_file"
    
    if $DRY_RUN; then
        print_warning "DRY RUN MODE - Commands that would be executed:"
        echo "================================================="
        cat "$commands_file"
        echo "================================================="
        return 0
    fi
    
    print_info "Applying role bindings to Confluent Cloud..."
    
    # Execute commands
    local success_count=0
    local error_count=0
    
    # Read and execute each command
    while IFS= read -r line; do
        if [[ $line =~ ^confluent ]]; then
            if $VERBOSE; then
                print_info "Executing: $line"
            fi
            
            if eval "$line"; then
                ((success_count++))
                if $VERBOSE; then
                    print_success "Role binding created successfully"
                fi
            else
                ((error_count++))
                print_error "Failed to create role binding"
            fi
            echo ""
        elif [[ $line =~ ^echo ]]; then
            eval "$line"
        fi
    done < "$commands_file"
    
    # Cleanup
    rm -f "$commands_file"
    
    # Report results
    print_info "Application completed:"
    print_success "$success_count role bindings created successfully"
    if [[ $error_count -gt 0 ]]; then
        print_error "$error_count role bindings failed"
    fi
}

# Verify applied role bindings
verify_role_bindings() {
    if $DRY_RUN; then
        return 0
    fi
    
    print_info "Verifying applied role bindings..."
    
    # Extract environment and cluster from RBAC file
    local env_id=$(python3 -c "import json; data=json.load(open('$RBAC_FILE')); print(data['role_bindings'][0]['environment'] if data['role_bindings'] else '')")
    local cluster_id=$(python3 -c "import json; data=json.load(open('$RBAC_FILE')); print(data['role_bindings'][0]['cluster_id'] if data['role_bindings'] else '')")
    
    if [[ -n "$env_id" ]]; then
        print_info "Listing current role bindings for environment: $env_id"
        if confluent iam rbac role-binding list --environment "$env_id" 2>/dev/null; then
            print_success "Role bindings verification completed"
        else
            print_warning "Could not verify role bindings (this may be expected)"
        fi
    fi
}

# Main execution
main() {
    print_info "Confluent Cloud RBAC Role Binding Applicator"
    print_info "============================================="
    
    # Perform checks
    check_prerequisites
    
    # Apply role bindings
    apply_role_bindings
    
    # Verify results
    verify_role_bindings
    
    if $DRY_RUN; then
        print_info "Dry run completed. Use without --dry-run to apply changes."
    else
        print_success "RBAC role bindings application completed! 🎉"
        print_info "Next steps:"
        print_info "1. Verify role bindings in Confluent Cloud console"
        print_info "2. Test application access with new permissions"
        print_info "3. Monitor access logs for any issues"
    fi
}

# Run main function
main 