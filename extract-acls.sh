#!/bin/bash

# MSK ACL Extractor - Comprehensive Shell Script
# This script provides examples and interactive mode for extracting ACLs from Amazon MSK

set -e

# Configuration
JAR_FILE="target/acl-to-cc-rbac-utility-1.0.0.jar"
RELEASE_JAR="release/acl-to-cc-rbac-utility-1.0.0.jar"
DEFAULT_OUTPUT_FILE="msk_acls_$(date +%Y%m%d_%H%M%S).json"
CONFIG_FILE="config.properties"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Functions
print_header() {
    echo -e "${BLUE}"
    echo "=============================================="
    echo "    MSK ACL Extractor Utility"
    echo "=============================================="
    echo -e "${NC}"
}

print_usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -h, --help              Show this help message"
    echo "  -i, --interactive       Run in interactive mode"
    echo "  -c, --config FILE       Use configuration file"
    echo "  --cluster-arn ARN       MSK cluster ARN"
    echo "  --region REGION         AWS region (default: us-east-1)"
    echo "  --output-file FILE      Output JSON file"
    echo "  --security-protocol     Security protocol (SSL, SASL_SSL, etc.)"
    echo "  --sasl-mechanism        SASL mechanism (AWS_MSK_IAM, SCRAM-SHA-256, etc.)"
    echo "  --sasl-username         SASL username"
    echo "  --sasl-password         SASL password"
    echo "  --no-metadata           Exclude cluster metadata"
    echo "  --verbose               Enable verbose logging"
    echo ""
    echo "Examples:"
    echo ""
    echo "1. Basic SSL connection:"
    echo "   $0 --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123"
    echo ""
    echo "2. With IAM authentication:"
    echo "   $0 --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123 \\"
    echo "      --security-protocol SASL_SSL --sasl-mechanism AWS_MSK_IAM"
    echo ""
    echo "3. With SCRAM authentication:"
    echo "   $0 --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123 \\"
    echo "      --security-protocol SASL_SSL --sasl-mechanism SCRAM-SHA-256 \\"
    echo "      --sasl-username myuser --sasl-password mypassword"
    echo ""
    echo "4. Interactive mode:"
    echo "   $0 --interactive"
    echo ""
    echo "5. Using configuration file:"
    echo "   $0 --config config.properties"
    echo ""
    echo "6. Create sample configuration file:"
    echo "   $0 --create-sample-config"
}

check_prerequisites() {
    # Check if JAR file exists (prefer release version)
    if [ -f "$RELEASE_JAR" ]; then
        JAR_FILE="$RELEASE_JAR"
        echo -e "${GREEN}✅ Using JAR from release directory${NC}"
    elif [ -f "$JAR_FILE" ]; then
        # Use target version if release doesn't exist
        echo -e "${YELLOW}ℹ️  Using JAR from target directory (consider running './build.sh' to update release)${NC}"
    else
        echo -e "${RED}❌ Error: JAR file not found in either location:${NC}"
        echo -e "${RED}   - $RELEASE_JAR${NC}"
        echo -e "${RED}   - $JAR_FILE${NC}"
        echo -e "${YELLOW}🔨 Please run './build.sh' first to build the application.${NC}"
        exit 1
    fi

    # Check if Java is available
    if ! command -v java &> /dev/null; then
        echo -e "${RED}❌ Error: Java is not installed. Please install Java 11 or higher.${NC}"
        exit 1
    fi

    # Check if AWS CLI is configured (optional but recommended)
    if ! aws sts get-caller-identity &> /dev/null; then
        echo -e "${YELLOW}⚠️  Warning: AWS credentials may not be configured properly.${NC}"
        echo -e "${YELLOW}   Please ensure you have valid AWS credentials configured.${NC}"
        echo ""
    else
        echo -e "${GREEN}✅ AWS credentials configured${NC}"
    fi
}

interactive_mode() {
    echo -e "${BLUE}🤖 Interactive Mode${NC}"
    echo "Please provide the following information:"
    echo ""

    # Cluster ARN
    read -p "MSK Cluster ARN: " CLUSTER_ARN
    if [ -z "$CLUSTER_ARN" ]; then
        echo -e "${RED}Error: Cluster ARN is required${NC}"
        exit 1
    fi

    # Region
    read -p "AWS Region [us-east-1]: " REGION
    REGION=${REGION:-us-east-1}

    # Output file
    read -p "Output JSON file [$DEFAULT_OUTPUT_FILE]: " OUTPUT_FILE
    OUTPUT_FILE=${OUTPUT_FILE:-$DEFAULT_OUTPUT_FILE}

    # Security protocol
    echo ""
    echo "Security Protocol Options:"
    echo "1. SSL (default)"
    echo "2. SASL_SSL"
    echo "3. PLAINTEXT"
    echo "4. SASL_PLAINTEXT"
    read -p "Choose security protocol [1]: " SECURITY_CHOICE
    
    case $SECURITY_CHOICE in
        2) SECURITY_PROTOCOL="SASL_SSL" ;;
        3) SECURITY_PROTOCOL="PLAINTEXT" ;;
        4) SECURITY_PROTOCOL="SASL_PLAINTEXT" ;;
        *) SECURITY_PROTOCOL="SSL" ;;
    esac

    # SASL mechanism if needed
    if [[ "$SECURITY_PROTOCOL" == *"SASL"* ]]; then
        echo ""
        echo "SASL Mechanism Options:"
        echo "1. AWS_MSK_IAM (recommended for MSK)"
        echo "2. SCRAM-SHA-256"
        echo "3. SCRAM-SHA-512"
        echo "4. PLAIN"
        read -p "Choose SASL mechanism [1]: " SASL_CHOICE
        
        case $SASL_CHOICE in
            2) SASL_MECHANISM="SCRAM-SHA-256" ;;
            3) SASL_MECHANISM="SCRAM-SHA-512" ;;
            4) SASL_MECHANISM="PLAIN" ;;
            *) SASL_MECHANISM="AWS_MSK_IAM" ;;
        esac

        # Username and password for non-IAM mechanisms
        if [ "$SASL_MECHANISM" != "AWS_MSK_IAM" ]; then
            read -p "SASL Username: " SASL_USERNAME
            read -s -p "SASL Password: " SASL_PASSWORD
            echo ""
        fi
    fi

    # Include metadata
    read -p "Include cluster metadata in output? [Y/n]: " INCLUDE_METADATA
    if [[ "$INCLUDE_METADATA" =~ ^[Nn]$ ]]; then
        NO_METADATA="--no-metadata"
    fi

    # Verbose logging
    read -p "Enable verbose logging? [y/N]: " VERBOSE_LOGGING
    if [[ "$VERBOSE_LOGGING" =~ ^[Yy]$ ]]; then
        VERBOSE="--verbose"
    fi

    # Build command
    COMMAND="java -jar $JAR_FILE --cluster-arn \"$CLUSTER_ARN\" --region \"$REGION\" --output-file \"$OUTPUT_FILE\" --security-protocol \"$SECURITY_PROTOCOL\""
    
    if [ -n "$SASL_MECHANISM" ]; then
        COMMAND="$COMMAND --sasl-mechanism \"$SASL_MECHANISM\""
    fi
    
    if [ -n "$SASL_USERNAME" ]; then
        COMMAND="$COMMAND --sasl-username \"$SASL_USERNAME\""
    fi
    
    if [ -n "$SASL_PASSWORD" ]; then
        COMMAND="$COMMAND --sasl-password \"$SASL_PASSWORD\""
    fi
    
    if [ -n "$NO_METADATA" ]; then
        COMMAND="$COMMAND $NO_METADATA"
    fi
    
    if [ -n "$VERBOSE" ]; then
        COMMAND="$COMMAND $VERBOSE"
    fi

    echo ""
    echo -e "${BLUE}Command to be executed:${NC}"
    echo "$COMMAND"
    echo ""
    
    read -p "Proceed with extraction? [Y/n]: " PROCEED
    if [[ "$PROCEED" =~ ^[Nn]$ ]]; then
        echo "Aborted."
        exit 0
    fi

    echo ""
    echo -e "${GREEN}🚀 Starting ACL extraction...${NC}"
    eval $COMMAND
}

validate_config_file() {
    local file="$1"
    
    if [ ! -f "$file" ]; then
        echo -e "${RED}❌ Error: Configuration file not found: $file${NC}"
        return 1
    fi
    
    # Check if file is readable
    if [ ! -r "$file" ]; then
        echo -e "${RED}❌ Error: Configuration file is not readable: $file${NC}"
        return 1
    fi
    
    # Check for required cluster.arn property
    if ! grep -q "^cluster\.arn=" "$file" && ! grep -q "^CLUSTER_ARN=" "$file"; then
        echo -e "${RED}❌ Error: Configuration file must contain 'cluster.arn' property${NC}"
        echo -e "${YELLOW}💡 Run '$0 --create-sample-config' to create a sample configuration file${NC}"
        return 1
    fi
    
    return 0
}

load_config() {
    if validate_config_file "$1"; then
        echo -e "${GREEN}📄 Loading configuration from: $1${NC}"
        
        # Function to read properties from file
        read_property() {
            local file="$1"
            local key="$2"
            # Remove comments and empty lines, then extract the value for the given key
            grep "^${key}=" "$file" 2>/dev/null | head -1 | cut -d'=' -f2- | sed 's/^["'\'']*//;s/["'\'']*$//'
        }
        
        # Read properties from file
        CLUSTER_ARN=$(read_property "$1" "cluster.arn")
        [ -z "$CLUSTER_ARN" ] && CLUSTER_ARN=$(read_property "$1" "CLUSTER_ARN")
        
        REGION=$(read_property "$1" "aws.region")
        [ -z "$REGION" ] && REGION=$(read_property "$1" "REGION")
        
        OUTPUT_FILE=$(read_property "$1" "output.file")
        [ -z "$OUTPUT_FILE" ] && OUTPUT_FILE=$(read_property "$1" "OUTPUT_FILE")
        
        SECURITY_PROTOCOL=$(read_property "$1" "security.protocol")
        [ -z "$SECURITY_PROTOCOL" ] && SECURITY_PROTOCOL=$(read_property "$1" "SECURITY_PROTOCOL")
        
        SASL_MECHANISM=$(read_property "$1" "sasl.mechanism")
        [ -z "$SASL_MECHANISM" ] && SASL_MECHANISM=$(read_property "$1" "SASL_MECHANISM")
        
        SASL_USERNAME=$(read_property "$1" "sasl.username")
        [ -z "$SASL_USERNAME" ] && SASL_USERNAME=$(read_property "$1" "SASL_USERNAME")
        
        SASL_PASSWORD=$(read_property "$1" "sasl.password")
        [ -z "$SASL_PASSWORD" ] && SASL_PASSWORD=$(read_property "$1" "SASL_PASSWORD")
        
        NO_METADATA_PROP=$(read_property "$1" "output.no-metadata")
        [ -z "$NO_METADATA_PROP" ] && NO_METADATA_PROP=$(read_property "$1" "NO_METADATA")
        
        VERBOSE_PROP=$(read_property "$1" "logging.verbose")
        [ -z "$VERBOSE_PROP" ] && VERBOSE_PROP=$(read_property "$1" "VERBOSE")
        
        # Validate required properties
        if [ -z "$CLUSTER_ARN" ]; then
            echo -e "${RED}❌ Error: cluster.arn is required in configuration file${NC}"
            exit 1
        fi
        
        # Build arguments
        ARGS="--cluster-arn \"$CLUSTER_ARN\""
        [ -n "$REGION" ] && ARGS="$ARGS --region \"$REGION\""
        [ -n "$OUTPUT_FILE" ] && ARGS="$ARGS --output-file \"$OUTPUT_FILE\""
        [ -n "$SECURITY_PROTOCOL" ] && ARGS="$ARGS --security-protocol \"$SECURITY_PROTOCOL\""
        [ -n "$SASL_MECHANISM" ] && ARGS="$ARGS --sasl-mechanism \"$SASL_MECHANISM\""
        [ -n "$SASL_USERNAME" ] && ARGS="$ARGS --sasl-username \"$SASL_USERNAME\""
        [ -n "$SASL_PASSWORD" ] && ARGS="$ARGS --sasl-password \"$SASL_PASSWORD\""
        
        # Handle boolean flags
        if [ "$NO_METADATA_PROP" = "true" ]; then
            ARGS="$ARGS --no-metadata"
        fi
        
        if [ "$VERBOSE_PROP" = "true" ]; then
            ARGS="$ARGS --verbose"
        fi
        
        echo -e "${BLUE}Configuration loaded:${NC}"
        echo "  Cluster ARN: $CLUSTER_ARN"
        [ -n "$REGION" ] && echo "  Region: $REGION"
        [ -n "$OUTPUT_FILE" ] && echo "  Output File: $OUTPUT_FILE"
        [ -n "$SECURITY_PROTOCOL" ] && echo "  Security Protocol: $SECURITY_PROTOCOL"
        [ -n "$SASL_MECHANISM" ] && echo "  SASL Mechanism: $SASL_MECHANISM"
        [ -n "$SASL_USERNAME" ] && echo "  SASL Username: $SASL_USERNAME"
        [ "$NO_METADATA_PROP" = "true" ] && echo "  Exclude Metadata: Yes"
        [ "$VERBOSE_PROP" = "true" ] && echo "  Verbose Logging: Yes"
        echo ""
        
        echo -e "${GREEN}🚀 Running with config file settings...${NC}"
        eval "java -jar $JAR_FILE $ARGS"
    else
        exit 1
    fi
}

create_sample_config() {
    cat > config.properties.sample << 'EOF'
# MSK ACL Extractor Configuration
# Copy this file to config.properties and update with your values
# Properties can be specified with or without quotes

# ===== REQUIRED SETTINGS =====

# MSK Cluster ARN (REQUIRED)
cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123

# ===== OPTIONAL SETTINGS =====

# AWS Region (default: us-east-1)
aws.region=us-east-1

# Output JSON file (default: timestamped file)
output.file=msk_acls.json

# ===== SECURITY SETTINGS =====

# Security Protocol: SSL, SASL_SSL, PLAINTEXT, SASL_PLAINTEXT
# Default: SSL
security.protocol=SSL

# SASL Mechanism: AWS_MSK_IAM, SCRAM-SHA-256, SCRAM-SHA-512, PLAIN
# Uncomment and set if using SASL
# sasl.mechanism=AWS_MSK_IAM

# SASL Credentials (only needed for SCRAM-SHA-256, SCRAM-SHA-512, PLAIN)
# Uncomment and set if using username/password authentication
# sasl.username=your-username
# sasl.password=your-password

# ===== OUTPUT SETTINGS =====

# Exclude cluster metadata from output (true/false)
# Default: false
output.no-metadata=false

# ===== LOGGING SETTINGS =====

# Enable verbose logging (true/false)
# Default: false
logging.verbose=false

# ===== EXAMPLES =====

# Example 1: Basic SSL connection (default)
# cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123
# aws.region=us-east-1
# security.protocol=SSL

# Example 2: IAM Authentication (recommended for MSK)
# cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123
# aws.region=us-east-1
# security.protocol=SASL_SSL
# sasl.mechanism=AWS_MSK_IAM

# Example 3: SCRAM Authentication
# cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123
# aws.region=us-east-1
# security.protocol=SASL_SSL
# sasl.mechanism=SCRAM-SHA-256
# sasl.username=myuser
# sasl.password=mypassword
EOF
    echo -e "${GREEN}📄 Sample configuration file created: config.properties.sample${NC}"
    echo -e "${YELLOW}💡 To use it:${NC}"
    echo "   1. cp config.properties.sample config.properties"
    echo "   2. Edit config.properties with your MSK cluster details"
    echo "   3. Run: ./extract-acls.sh --config config.properties"
}

# Main script
print_header

case "$1" in
    -h|--help)
        print_usage
        exit 0
        ;;
    -i|--interactive)
        check_prerequisites
        interactive_mode
        ;;
    -c|--config)
        if [ -z "$2" ]; then
            echo -e "${RED}Error: Configuration file path required${NC}"
            exit 1
        fi
        check_prerequisites
        load_config "$2"
        ;;
    --create-sample-config)
        create_sample_config
        exit 0
        ;;
    "")
        echo -e "${YELLOW}No arguments provided. Use --help for usage information or --interactive for interactive mode.${NC}"
        echo ""
        print_usage
        exit 1
        ;;
    *)
        # Pass all arguments to the Java application
        check_prerequisites
        echo -e "${GREEN}🚀 Running MSK ACL Extractor...${NC}"
        java -jar "$JAR_FILE" "$@"
        ;;
esac 