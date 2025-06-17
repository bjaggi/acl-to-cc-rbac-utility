#!/bin/bash

# Confluent Cloud Service Account Credentials Creator
# Creates service accounts, generates API keys, and organizes credentials in structured folders

set -e

# Default values
RBAC_FILE="generated_jsons/cc_jsons/cc_rbac.json"
CONFIG_FILE="ccloud.config"
CREDENTIALS_DIR="generated_jsons/cc_jsons/cc_credentials"
DRY_RUN=false
VERBOSE=false
FORCE=false
SKIP_EXISTING=true

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
KEY_ICON="🔑"
SA_ICON="👤"

show_help() {
    cat << 'EOF'
Confluent Cloud Service Account Credentials Creator

This script creates service accounts in Confluent Cloud, generates API keys for them,
and organizes the credentials in a structured folder hierarchy.

Usage: $0 [OPTIONS]

Options:
    -r, --rbac FILE         RBAC JSON file (default: generated_jsons/cc_jsons/cc_rbac.json)
    -c, --config FILE       Confluent Cloud config file (default: ccloud.config)
    -o, --output DIR        Credentials output directory (default: generated_jsons/cc_jsons/cc_credentials)
    --dry-run              Show what would be done without making changes
    --force                Recreate existing service accounts and keys
    --no-skip-existing     Process all service accounts (don't skip existing ones)
    -v, --verbose          Enable verbose logging
    -h, --help             Show this help message

Examples:
    # Create credentials for all service accounts in RBAC file
    $0

    # Dry run to see what would be created
    $0 --dry-run

    # Force recreation of all credentials
    $0 --force --verbose

    # Use custom RBAC file
    $0 --rbac my_rbac.json

Output Structure:
    generated_jsons/cc_jsons/cc_credentials/
    ├── service-account-1/
    │   ├── credentials.json
    │   ├── api-key.txt
    │   └── api-secret.txt
    ├── service-account-2/
    │   ├── credentials.json
    │   ├── api-key.txt
    │   └── api-secret.txt
    └── credentials-summary.json

Features:
    - Creates service accounts if they don't exist
    - Generates Kafka API keys for each service account
    - Organizes credentials in dedicated folders per service account
    - Creates both JSON and text formats for easy integration
    - Generates summary report of all created credentials
    - Supports dry-run mode for safe testing
    - Handles existing service accounts gracefully

Requirements:
    - Valid Confluent Cloud API credentials in config file
    - curl and jq for API operations
    - Proper permissions to create service accounts and API keys

EOF
}

while [[ $# -gt 0 ]]; do
    case $1 in
        -r|--rbac)
            RBAC_FILE="$2"
            shift 2
            ;;
        -c|--config)
            CONFIG_FILE="$2"
            shift 2
            ;;
        -o|--output)
            CREDENTIALS_DIR="$2"
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
        --no-skip-existing)
            SKIP_EXISTING=false
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
    
    local missing_deps=()
    
    if ! command -v jq &> /dev/null; then
        missing_deps+=("jq")
    fi
    
    if ! command -v curl &> /dev/null; then
        missing_deps+=("curl")
    fi
    
    if [[ ${#missing_deps[@]} -gt 0 ]]; then
        print_error "Missing required dependencies: ${missing_deps[*]}"
        print_info "Ubuntu/Debian: sudo apt-get install ${missing_deps[*]}"
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
    
    # Read Confluent Cloud API credentials
    CLOUD_API_KEY=$(grep "^confluent_cloud_key" "$CONFIG_FILE" | cut -d'=' -f2- | tr -d ' ')
    CLOUD_API_SECRET=$(grep "^confluent_cloud_secret" "$CONFIG_FILE" | cut -d'=' -f2- | tr -d ' ')
    ENVIRONMENT=$(grep "^confluent.cloud.environment" "$CONFIG_FILE" | cut -d'=' -f2- | tr -d ' ')
    CLUSTER_ID=$(grep "^confluent.cloud.cluster" "$CONFIG_FILE" | cut -d'=' -f2- | tr -d ' ')
    
    if [[ -z "$CLOUD_API_KEY" || -z "$CLOUD_API_SECRET" ]]; then
        print_error "Cloud API credentials not found in config file"
        print_error "Please add: confluent_cloud_key=YOUR_KEY and confluent_cloud_secret=YOUR_SECRET"
        exit 1
    fi
    
    if [[ -z "$ENVIRONMENT" || -z "$CLUSTER_ID" ]]; then
        print_error "Environment or cluster ID not found in config file"
        print_error "Please add: confluent.cloud.environment=env-xxxxx and confluent.cloud.cluster=lkc-xxxxx"
        exit 1
    fi
    
    print_success "Configuration loaded successfully"
    print_verbose "Environment: $ENVIRONMENT"
    print_verbose "Cluster: $CLUSTER_ID"
    print_verbose "Cloud API Key: ${CLOUD_API_KEY:0:8}..."
}

validate_rbac_file() {
    print_info "Validating RBAC file: $RBAC_FILE"
    
    if [[ ! -f "$RBAC_FILE" ]]; then
        print_error "RBAC file not found: $RBAC_FILE"
        print_error "Please run the RBAC conversion first:"
        print_error "  ./scripts/convert-acl-to-rbac.sh -e $ENVIRONMENT -c $CLUSTER_ID"
        exit 1
    fi
    
    if ! jq empty "$RBAC_FILE" 2>/dev/null; then
        print_error "RBAC file is not valid JSON: $RBAC_FILE"
        exit 1
    fi
    
    local service_account_count
    service_account_count=$(jq -r '.service_accounts // [] | length' "$RBAC_FILE" 2>/dev/null || echo 0)
    
    if [[ "$service_account_count" -eq 0 ]]; then
        print_warning "No service accounts found in RBAC file"
        print_info "Nothing to process."
        exit 0
    fi
    
    print_success "Found $service_account_count service accounts in RBAC file"
}

setup_credentials_directory() {
    print_info "Setting up credentials directory: $CREDENTIALS_DIR"
    
    if $DRY_RUN; then
        print_info "🔍 Would create directory structure: $CREDENTIALS_DIR"
        return 0
    fi
    
    # Create main credentials directory
    mkdir -p "$CREDENTIALS_DIR"
    
    # Create .gitignore to prevent accidental commit of credentials
    cat > "$CREDENTIALS_DIR/.gitignore" << 'EOF'
# Ignore all credential files
*.txt
*.json
*/

# But allow this .gitignore file
!.gitignore
EOF
    
    print_success "Credentials directory created"
    print_verbose "Directory: $CREDENTIALS_DIR"
}

check_service_account_exists() {
    local sa_name="$1"
    
    print_verbose "Checking if service account exists: $sa_name"
    
    local response
    response=$(curl -s --max-time 30 \
        -u "$CLOUD_API_KEY:$CLOUD_API_SECRET" \
        -H "Content-Type: application/json" \
        "https://api.confluent.cloud/iam/v2/service-accounts?display_name=$(printf '%s' "$sa_name" | jq -sRr @uri)" \
        2>/dev/null)
    
    if [[ $? -ne 0 ]]; then
        print_error "Failed to check service account: $sa_name"
        return 1
    fi
    
    local sa_count
    sa_count=$(echo "$response" | jq -r '.data | length' 2>/dev/null || echo 0)
    
    if [[ "$sa_count" -gt 0 ]]; then
        local sa_id
        sa_id=$(echo "$response" | jq -r '.data[0].id' 2>/dev/null)
        echo "$sa_id"
        return 0
    else
        return 1
    fi
}

create_service_account() {
    local sa_name="$1"
    local description="$2"
    
    print_processing "Creating service account: $sa_name"
    
    if $DRY_RUN; then
        print_info "  🔍 Would create service account: $sa_name"
        echo "sa-dry-run-$(date +%s)"
        return 0
    fi
    
    local payload
    payload=$(jq -n \
        --arg display_name "$sa_name" \
        --arg description "$description" \
        '{
            display_name: $display_name,
            description: $description
        }')
    
    print_verbose "Service account payload: $payload"
    
    local response
    response=$(curl -s --max-time 30 \
        -u "$CLOUD_API_KEY:$CLOUD_API_SECRET" \
        -X POST \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "https://api.confluent.cloud/iam/v2/service-accounts" \
        2>/dev/null)
    
    if echo "$response" | jq -e '.id' >/dev/null 2>&1; then
        local sa_id
        sa_id=$(echo "$response" | jq -r '.id')
        print_success "  $SUCCESS Service account created (ID: $sa_id)"
        echo "$sa_id"
        return 0
    else
        print_error "  $ERROR Failed to create service account: $response"
        return 1
    fi
}

create_api_key() {
    local sa_id="$1"
    local sa_name="$2"
    
    print_processing "Creating API key for: $sa_name"
    
    if $DRY_RUN; then
        print_info "  🔍 Would create API key for service account: $sa_name"
        echo '{"key": "DRY-RUN-KEY", "secret": "DRY-RUN-SECRET"}'
        return 0
    fi
    
    local payload
    payload=$(jq -n \
        --arg owner_id "$sa_id" \
        --arg cluster_id "$CLUSTER_ID" \
        --arg environment "$ENVIRONMENT" \
        '{
            spec: {
                owner: {
                    id: $owner_id
                },
                resource: {
                    id: $cluster_id,
                    environment: {
                        id: $environment
                    }
                }
            }
        }')
    
    print_verbose "API key payload: $payload"
    
    local response
    response=$(curl -s --max-time 30 \
        -u "$CLOUD_API_KEY:$CLOUD_API_SECRET" \
        -X POST \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "https://api.confluent.cloud/iam/v2/api-keys" \
        2>/dev/null)
    
    if echo "$response" | jq -e '.spec.secret' >/dev/null 2>&1; then
        local api_key api_secret
        api_key=$(echo "$response" | jq -r '.spec.display_name // .id')
        api_secret=$(echo "$response" | jq -r '.spec.secret')
        
        print_success "  $SUCCESS API key created successfully"
        print_verbose "  Key: ${api_key:0:8}..."
        
        echo "$response" | jq -r '{key: (.spec.display_name // .id), secret: .spec.secret}'
        return 0
    else
        print_error "  $ERROR Failed to create API key: $response"
        return 1
    fi
}

save_credentials() {
    local sa_name="$1"
    local sa_id="$2"
    local api_key="$3"
    local api_secret="$4"
    local original_principal="$5"
    
    print_processing "Saving credentials for: $sa_name"
    
    local sa_dir="$CREDENTIALS_DIR/$sa_name"
    
    if $DRY_RUN; then
        print_info "  🔍 Would save credentials to: $sa_dir"
        return 0
    fi
    
    # Create service account directory
    mkdir -p "$sa_dir"
    
    # Create comprehensive credentials JSON
    local credentials_json
    credentials_json=$(jq -n \
        --arg sa_name "$sa_name" \
        --arg sa_id "$sa_id" \
        --arg api_key "$api_key" \
        --arg api_secret "$api_secret" \
        --arg original_principal "$original_principal" \
        --arg environment "$ENVIRONMENT" \
        --arg cluster_id "$CLUSTER_ID" \
        --arg created_at "$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")" \
        '{
            service_account: {
                name: $sa_name,
                id: $sa_id,
                original_principal: $original_principal
            },
            api_credentials: {
                key: $api_key,
                secret: $api_secret
            },
            confluent_cloud: {
                environment: $environment,
                cluster_id: $cluster_id
            },
            metadata: {
                created_at: $created_at,
                created_by: "create-cc-sa-creds.sh"
            }
        }')
    
    # Save files
    echo "$credentials_json" > "$sa_dir/credentials.json"
    echo "$api_key" > "$sa_dir/api-key.txt"
    echo "$api_secret" > "$sa_dir/api-secret.txt"
    
    # Create Kafka client properties file
    cat > "$sa_dir/kafka.properties" << EOF
# Kafka Client Configuration for $sa_name
bootstrap.servers=$(grep "^bootstrap.servers" "$CONFIG_FILE" | cut -d'=' -f2- | tr -d ' ')
security.protocol=SASL_SSL
sasl.mechanisms=PLAIN
sasl.username=$api_key
sasl.password=$api_secret

# Additional settings
client.id=$sa_name
EOF
    
    print_success "  $SUCCESS Credentials saved to: $sa_dir"
    print_verbose "  Files: credentials.json, api-key.txt, api-secret.txt, kafka.properties"
}

process_service_accounts() {
    print_info "$SA_ICON Processing service accounts..."
    
    local total_sa=0
    local created_sa=0
    local skipped_sa=0
    local failed_sa=0
    local credentials_summary=()
    
    while IFS= read -r sa_data; do
        total_sa=$((total_sa + 1))
        
        local sa_name original_principal description
        sa_name=$(echo "$sa_data" | jq -r '.name')
        original_principal=$(echo "$sa_data" | jq -r '.original_principal // "unknown"')
        description=$(echo "$sa_data" | jq -r '.description // "Service account migrated from MSK"')
        
        print_info "[$total_sa] Processing: $sa_name"
        print_verbose "  Original principal: $original_principal"
        
        # Check if service account already exists
        local existing_sa_id
        if existing_sa_id=$(check_service_account_exists "$sa_name"); then
            if $SKIP_EXISTING && ! $FORCE; then
                print_warning "  $WARNING Service account exists (ID: $existing_sa_id). Skipping."
                skipped_sa=$((skipped_sa + 1))
                continue
            elif $FORCE; then
                print_warning "  $WARNING Service account exists, but --force specified. Using existing SA."
                sa_id="$existing_sa_id"
            else
                print_warning "  $WARNING Service account exists (ID: $existing_sa_id). Use --force to recreate."
                skipped_sa=$((skipped_sa + 1))
                continue
            fi
        else
            # Create new service account
            if ! sa_id=$(create_service_account "$sa_name" "$description"); then
                failed_sa=$((failed_sa + 1))
                continue
            fi
        fi
        
        # Create API key
        local api_credentials
        if api_credentials=$(create_api_key "$sa_id" "$sa_name"); then
            local api_key api_secret
            api_key=$(echo "$api_credentials" | jq -r '.key')
            api_secret=$(echo "$api_credentials" | jq -r '.secret')
            
            # Save credentials
            if save_credentials "$sa_name" "$sa_id" "$api_key" "$api_secret" "$original_principal"; then
                created_sa=$((created_sa + 1))
                
                # Add to summary
                credentials_summary+=("$(jq -n \
                    --arg sa_name "$sa_name" \
                    --arg sa_id "$sa_id" \
                    --arg api_key "$api_key" \
                    --arg original_principal "$original_principal" \
                    '{
                        service_account: $sa_name,
                        id: $sa_id,
                        api_key: $api_key,
                        original_principal: $original_principal,
                        folder: ($sa_name)
                    }')")
            else
                failed_sa=$((failed_sa + 1))
            fi
        else
            failed_sa=$((failed_sa + 1))
        fi
        
        echo
        
    done < <(jq -c '.service_accounts[]' "$RBAC_FILE")
    
    # Create summary report
    if [[ ${#credentials_summary[@]} -gt 0 ]] && ! $DRY_RUN; then
        local summary_json
        summary_json=$(printf '%s\n' "${credentials_summary[@]}" | jq -s \
            --arg created_at "$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")" \
            --argjson total "$total_sa" \
            --argjson created "$created_sa" \
            --argjson skipped "$skipped_sa" \
            --argjson failed "$failed_sa" \
            '{
                summary: {
                    total_service_accounts: $total,
                    created_credentials: $created,
                    skipped: $skipped,
                    failed: $failed,
                    created_at: $created_at
                },
                credentials: .
            }')
        
        echo "$summary_json" > "$CREDENTIALS_DIR/credentials-summary.json"
        print_success "Summary report saved: $CREDENTIALS_DIR/credentials-summary.json"
    fi
    
    # Print final summary
    print_info ""
    print_info "$KEY_ICON Service Account Credentials Summary:"
    print_info "==========================================="
    print_info "$SUCCESS Credentials created: $created_sa"
    print_info "$WARNING Service accounts skipped: $skipped_sa"
    print_info "$ERROR Failed operations: $failed_sa"
    print_info "$INFO Total service accounts processed: $total_sa"
    
    if [[ $failed_sa -gt 0 ]]; then
        print_error "Some operations failed. Check the logs above for details."
        return 1
    elif [[ $created_sa -eq 0 ]]; then
        print_warning "No credentials were created."
        return 1
    else
        print_success "Credential creation completed successfully!"
        return 0
    fi
}

main() {
    print_info "$KEY_ICON Confluent Cloud Service Account Credentials Creator"
    print_info "========================================================="
    echo
    
    # Show configuration
    print_info "Configuration:"
    print_info "  RBAC file: $RBAC_FILE"
    print_info "  Config file: $CONFIG_FILE"
    print_info "  Output directory: $CREDENTIALS_DIR"
    print_info "  Dry run: $DRY_RUN"
    print_info "  Force: $FORCE"
    print_info "  Skip existing: $SKIP_EXISTING"
    print_info "  Verbose: $VERBOSE"
    echo
    
    # Execute workflow
    check_prerequisites
    read_config
    validate_rbac_file
    setup_credentials_directory
    echo
    
    if process_service_accounts; then
        if $DRY_RUN; then
            print_info "🔍 Dry run completed. Remove --dry-run to create actual credentials."
        else
            print_success "$SUCCESS Service account credentials created successfully! $SUCCESS"
            echo
            print_info "$ARROW Next Steps:"
            print_info "  $BULLET Check credentials in: $CREDENTIALS_DIR"
            print_info "  $BULLET Each service account has its own folder with:"
            print_info "    - credentials.json (complete metadata)"
            print_info "    - api-key.txt (just the API key)"
            print_info "    - api-secret.txt (just the API secret)"
            print_info "    - kafka.properties (ready-to-use Kafka config)"
            print_info "  $BULLET Review summary: $CREDENTIALS_DIR/credentials-summary.json"
            print_info "  $BULLET Distribute credentials securely to application teams"
            print_warning "  $BULLET Keep credentials secure - they are ignored by git"
        fi
    else
        print_error "$ERROR Service account credential creation failed!"
        exit 1
    fi
}

main "$@" 