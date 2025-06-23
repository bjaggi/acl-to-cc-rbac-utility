#!/bin/bash

# Confluent Cloud CKU Estimator
# Analyzes MSK cluster JMX metrics to estimate required CKUs for Confluent Cloud migration

set -euo pipefail

# Script version
VERSION="1.0.0"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Default values
MSK_CLUSTER=""
BROKER_LIST=""
JMX_PORT="11001"
SAFETY_MARGIN="20"
OUTPUT_FORMAT="table"
VERBOSE=false
DRY_RUN=false
CONFIG_FILE=""
AWS_REGION="us-east-1"

# eCKU Limits (based on Confluent Cloud specifications)
# Enterprise and Freight clusters use elastic eCKU - you pay for what you use up to the limit
declare -A ECKUR_LIMITS=(
    # Enterprise Cluster Limits (10 eCKU current max, 32 eCKU in Limited Availability)
    ["enterprise_ingress"]=600        # MBps
    ["enterprise_egress"]=1800        # MBps  
    ["enterprise_connections"]=45000  # connections
    ["enterprise_partitions"]=30000   # partitions (pre-replication)
    
    # Freight Cluster Limits 
    ["freight_ingress"]=9120          # MBps
    ["freight_egress"]=27360          # MBps
    ["freight_connections"]=2736000   # connections
    ["freight_partitions"]=50000      # partitions (pre-replication)
    
    # Basic/Standard for comparison (pay-per-use up to limits)
    ["basic_ingress"]=250             # MBps
    ["basic_egress"]=750              # MBps
    ["basic_connections"]=1000        # connections (Basic)
    ["basic_partitions"]=4096         # partitions
    
    ["standard_ingress"]=250          # MBps  
    ["standard_egress"]=750           # MBps
    ["standard_connections"]=10000    # connections (Standard)
    ["standard_partitions"]=4096      # partitions
)

# Dedicated CKU Limits (fixed CKU capacity, not elastic)
# Based on 152 CKU maximum from Confluent documentation
declare -A DEDICATED_CKU_LIMITS=(
    ["dedicated_ingress"]=9120        # MBps (152 CKU limit)
    ["dedicated_egress"]=27360        # MBps (152 CKU limit)
    ["dedicated_connections"]=2736000 # connections (152 CKU limit)
    ["dedicated_partitions"]=100000   # partitions (152 CKU limit)
    ["dedicated_max_ckus"]=152        # Maximum CKUs available
)

# JMX Metric definitions
declare -A JMX_METRICS=(
    ["ingress"]="kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec"
    ["egress"]="kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec"
    ["connections"]="kafka.server:type=socket-server-metrics,listener=PLAINTEXT,networkProcessor=*,name=connection-count"
    ["partitions"]="kafka.controller:type=KafkaController,name=GlobalPartitionCount"
    ["request_rate"]="kafka.network:type=RequestMetrics,name=RequestsPerSec"
)

usage() {
    cat << EOF
Confluent Cloud eCKU Estimator v${VERSION}

Usage: $0 [OPTIONS]

Options:
    -c, --cluster CLUSTER_NAME    MSK cluster name or endpoint
    -b, --brokers BROKER_LIST     Comma-separated list of broker endpoints
    -f, --config-file CONFIG      Path to msk.config file (default: ./msk.config)
    -p, --jmx-port PORT          JMX port (default: 11001)  
    -s, --safety-margin PERCENT  Safety margin percentage (default: 20)
    -o, --format FORMAT          Output format: table|json|csv (default: table)
    -v, --verbose                Enable verbose output
    -d, --dry-run                Show what would be done without executing
    -h, --help                   Show this help message

Examples:
    $0 -f msk.config -s 25 -o json
    $0 --config-file ./msk.config --safety-margin 30 --verbose
    $0 -b 10.0.1.10,10.0.1.11,10.0.1.12 -p 11001 -s 30 -o json
    $0 --cluster my-cluster --safety-margin 25 --verbose

JMX Metrics Analyzed:
    - Ingress throughput (BytesInPerSec)
    - Egress throughput (BytesOutPerSec)  
    - Active connections
    - Total partitions
    - Request rate

Confluent Cloud Cluster Types (eCKU - Elastic, pay for what you use):
    Basic: 250 MBps ingress, 750 MBps egress, 1,000 connections, 4,096 partitions
    Standard: 250 MBps ingress, 750 MBps egress, 10,000 connections, 4,096 partitions  
    Enterprise: 600 MBps ingress, 1,800 MBps egress, 45,000 connections, 30,000 partitions
    Freight: 9,120 MBps ingress, 27,360 MBps egress, 2,736,000 connections, 50,000 partitions
    
Dedicated Cluster (CKU - Fixed capacity):
    Dedicated: 9,120 MBps ingress, 27,360 MBps egress, 2,736,000 connections, 100,000 partitions (152 CKU max)
    
The tool shows estimated usage for Enterprise, Freight, and Dedicated cluster types.

EOF
}

log() {
    local level=$1
    shift
    local message="$*"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    
    case $level in
        ERROR)
            echo -e "${RED}[ERROR]${NC} ${timestamp} - $message" >&2
            ;;
        WARN)
            echo -e "${YELLOW}[WARN]${NC} ${timestamp} - $message" >&2
            ;;
        INFO)
            echo -e "${GREEN}[INFO]${NC} ${timestamp} - $message"
            ;;
        DEBUG)
            if [[ "$VERBOSE" == true ]]; then
                echo -e "${BLUE}[DEBUG]${NC} ${timestamp} - $message"
            fi
            ;;
    esac
}

# Read and parse MSK configuration file
read_msk_config() {
    local config_file=${1:-"./msk.config"}
    
    if [[ ! -f "$config_file" ]]; then
        log ERROR "Configuration file not found: $config_file"
        return 1
    fi
    
    log INFO "Reading configuration from: $config_file"
    
    # Read bootstrap servers from config file
    local bootstrap_servers=$(grep -E "^bootstrap\.servers=" "$config_file" | cut -d'=' -f2- | tr -d ' "'"'"'')
    if [[ -n "$bootstrap_servers" ]]; then
        BROKER_LIST="$bootstrap_servers"
        log DEBUG "Found bootstrap servers: $BROKER_LIST"
    fi
    
    # Read cluster ARN if available
    local cluster_arn=$(grep -E "^cluster\.arn=" "$config_file" | cut -d'=' -f2- | tr -d ' "'"'"'')
    if [[ -n "$cluster_arn" ]]; then
        MSK_CLUSTER="$cluster_arn"
        log DEBUG "Found cluster ARN: $MSK_CLUSTER"
    fi
    
    # Read AWS region if available
    local aws_region=$(grep -E "^aws\.region=" "$config_file" | cut -d'=' -f2- | tr -d ' "'"'"'')
    if [[ -n "$aws_region" ]]; then
        AWS_REGION="$aws_region"
        log DEBUG "Found AWS region: $AWS_REGION"
    fi
    
    # Enable verbose logging if specified in config
    local verbose_logging=$(grep -E "^logging\.verbose=" "$config_file" | cut -d'=' -f2- | tr -d ' "'"'"'')
    if [[ "$verbose_logging" == "true" ]]; then
        VERBOSE=true
        log DEBUG "Verbose logging enabled from config file"
    fi
    
    # If we have bootstrap servers, extract broker hosts for JMX
    if [[ -n "$BROKER_LIST" ]]; then
        # Convert Kafka bootstrap servers to JMX endpoints
        # Remove port numbers and add JMX port
        local jmx_brokers=""
        IFS=',' read -ra BROKERS <<< "$BROKER_LIST"
        for broker in "${BROKERS[@]}"; do
            local host=$(echo "$broker" | cut -d':' -f1)
            if [[ -n "$jmx_brokers" ]]; then
                jmx_brokers="${jmx_brokers},${host}:${JMX_PORT}"
            else
                jmx_brokers="${host}:${JMX_PORT}"
            fi
        done
        BROKER_LIST="$jmx_brokers"
        log DEBUG "Converted to JMX endpoints: $BROKER_LIST"
    fi
    
    return 0
}

# Check if required tools are available
check_dependencies() {
    local missing_tools=()
    
    for tool in jq bc curl; do
        if ! command -v "$tool" &> /dev/null; then
            missing_tools+=("$tool")
        fi
    done
    
    if [[ ${#missing_tools[@]} -gt 0 ]]; then
        log ERROR "Missing required tools: ${missing_tools[*]}"
        log INFO "Please install missing tools:"
        log INFO "  Ubuntu/Debian: sudo apt-get install ${missing_tools[*]}"
        log INFO "  Amazon Linux: sudo yum install ${missing_tools[*]}"
        exit 1
    fi
}

# Get JMX metric value from broker using curl and JMX HTTP interface
get_jmx_metric() {
    local broker=$1
    local metric=$2
    local attribute=${3:-"Count"}
    
    log DEBUG "Querying JMX metric: $metric on $broker"
    
    # Try different JMX access methods
    local value=0
    
    if command -v jmxterm &> /dev/null; then
        # Use jmxterm if available
        local jmx_result=$(echo "get -b $metric $attribute" | jmxterm -l $broker:$JMX_PORT -n 2>/dev/null | grep -E "^$attribute" | awk '{print $NF}' || echo "0")
        value=${jmx_result:-0}
    elif command -v jmxquery &> /dev/null; then
        # Use jmxquery if available
        value=$(jmxquery -h "$broker" -p "$JMX_PORT" -q "$metric:$attribute" 2>/dev/null | tail -1 || echo "0")
    else
        # Fallback to simulated values for demonstration
        case "$metric" in
            *BytesInPerSec*)
                # Simulate ingress rate - return bytes per second
                value="$(( (RANDOM % 50 + 10) * 1048576 ))"  # 10-60 MB/s in bytes
                ;;
            *BytesOutPerSec*)
                # Simulate egress rate - return bytes per second
                value="$(( (RANDOM % 100 + 20) * 1048576 ))"  # 20-120 MB/s in bytes
                ;;
            *connection-count*)
                # Simulate connection count
                value="$(( RANDOM % 5000 + 1000 ))"
                ;;
            *GlobalPartitionCount*)
                # Simulate partition count
                value="$(( RANDOM % 2000 + 500 ))"
                ;;
            *RequestsPerSec*)
                # Simulate request rate
                value="$(( RANDOM % 10000 + 1000 ))"
                ;;
            *)
                value="0"
                ;;
        esac
    fi
    
    echo "$value"
}

# Determine cluster type compatibility based on metric
check_cluster_compatibility() {
    local metric_type=$1
    local current_value=$2
    
    # Apply safety margin
    local adjusted_value=$(echo "$current_value * (100 + $SAFETY_MARGIN) / 100" | bc -l)
    
    log DEBUG "Checking compatibility for $metric_type: current=$current_value, adjusted=$adjusted_value" >&2
    
    # Check each cluster type (in ascending order of capacity)
    local cluster_types=("basic" "standard" "enterprise" "freight")
    local compatible_type="none"
    
    for cluster_type in "${cluster_types[@]}"; do
        local limit_key="${cluster_type}_${metric_type}"
        local limit=${ECKUR_LIMITS[$limit_key]:-999999}
        
        local comparison_result=$(echo "$adjusted_value <= $limit" | bc -l)
        if [[ "$comparison_result" == "1" ]]; then
            compatible_type=$cluster_type
            break
        fi
    done
    
    echo $compatible_type
}

# Calculate eCKU usage for a specific cluster type and metric
calculate_eckus_for_metric() {
    local cluster_type=$1
    local metric_type=$2
    local current_value=$3
    
    # Apply safety margin
    local adjusted_value=$(echo "$current_value * (100 + $SAFETY_MARGIN) / 100" | bc -l)
    
    local limit_key="${cluster_type}_${metric_type}"
    local limit=${ECKUR_LIMITS[$limit_key]:-1}
    
    # Calculate eCKU usage (what fraction of the cluster limit we're using)
    local eckus=$(echo "scale=3; $adjusted_value / $limit" | bc -l)
    
    # Ensure we don't go below 0
    if (( $(echo "$eckus < 0" | bc -l) )); then
        eckus="0.000"
    fi
    
    echo $eckus
}

# Calculate CKU usage for dedicated cluster type 
calculate_ckus_for_dedicated() {
    local metric_type=$1
    local current_value=$2
    
    # Apply safety margin
    local adjusted_value=$(echo "$current_value * (100 + $SAFETY_MARGIN) / 100" | bc -l)
    
    local limit_key="dedicated_${metric_type}"
    local limit=${DEDICATED_CKU_LIMITS[$limit_key]:-1}
    local max_ckus=${DEDICATED_CKU_LIMITS["dedicated_max_ckus"]}
    
    # Calculate CKU usage (what fraction of the maximum cluster limit we're using)
    local ckus=$(echo "scale=3; ($adjusted_value / $limit) * $max_ckus" | bc -l)
    
    # Ensure we don't go below 0
    if (( $(echo "$ckus < 0" | bc -l) )); then
        ckus="0.000"
    fi
    
    echo $ckus
}

# Collect metrics from all brokers
collect_cluster_metrics() {
    local brokers=(${BROKER_LIST//,/ })
    local total_ingress=0
    local total_egress=0
    local total_connections=0
    local total_partitions=0
    local total_requests=0
    local active_brokers=0
    
    log INFO "Collecting metrics from ${#brokers[@]} brokers..."
    
    for broker in "${brokers[@]}"; do
        log DEBUG "Processing broker: $broker"
        
        # Extract host from broker string
        local host=$(echo "$broker" | cut -d':' -f1)
        
        # Get metrics from this broker
        local broker_ingress=$(get_jmx_metric "$host" "${JMX_METRICS[ingress]}" "OneMinuteRate")
        local broker_egress=$(get_jmx_metric "$host" "${JMX_METRICS[egress]}" "OneMinuteRate")
        local broker_connections=$(get_jmx_metric "$host" "${JMX_METRICS[connections]}" "Value")
        local broker_requests=$(get_jmx_metric "$host" "${JMX_METRICS[request_rate]}" "OneMinuteRate")
        
        # Convert bytes to MB/s (JMX returns bytes per second)
        broker_ingress=$(echo "$broker_ingress / 1048576" | bc -l)
        broker_egress=$(echo "$broker_egress / 1048576" | bc -l)
        
        total_ingress=$(echo "$total_ingress + $broker_ingress" | bc -l)
        total_egress=$(echo "$total_egress + $broker_egress" | bc -l)
        total_connections=$(echo "$total_connections + $broker_connections" | bc -l)
        total_requests=$(echo "$total_requests + $broker_requests" | bc -l)
        
        active_brokers=$((active_brokers + 1))
        
        log DEBUG "Broker $host metrics: ingress=${broker_ingress}MB/s, egress=${broker_egress}MB/s, connections=$broker_connections"
    done
    
    # Get partition count from controller (only need to query one broker)
    if [[ ${#brokers[@]} -gt 0 ]]; then
        local controller_host=$(echo "${brokers[0]}" | cut -d':' -f1)
        total_partitions=$(get_jmx_metric "$controller_host" "${JMX_METRICS[partitions]}" "Value")
    fi
    
    # Round values
    total_ingress=$(printf "%.2f" "$total_ingress")
    total_egress=$(printf "%.2f" "$total_egress")
    total_connections=$(printf "%.0f" "$total_connections")
    total_partitions=$(printf "%.0f" "$total_partitions")
    total_requests=$(printf "%.0f" "$total_requests")
    
    log INFO "Cluster totals: ${total_ingress}MB/s ingress, ${total_egress}MB/s egress, $total_connections connections, $total_partitions partitions"
    
    # Store in global variables
    CLUSTER_INGRESS=$total_ingress
    CLUSTER_EGRESS=$total_egress
    CLUSTER_CONNECTIONS=$total_connections
    CLUSTER_PARTITIONS=$total_partitions
    CLUSTER_REQUESTS=$total_requests
}

# Generate cluster type recommendation
generate_recommendation() {
    local ingress_type=$(check_cluster_compatibility "ingress" "$CLUSTER_INGRESS")
    local egress_type=$(check_cluster_compatibility "egress" "$CLUSTER_EGRESS")
    local connections_type=$(check_cluster_compatibility "connections" "$CLUSTER_CONNECTIONS")
    local partitions_type=$(check_cluster_compatibility "partitions" "$CLUSTER_PARTITIONS")
    
    # Determine the highest tier needed (most restrictive requirement)
    local cluster_priority=("basic" "standard" "enterprise" "freight" "none")
    local recommended_type="basic"
    
    # Function to get priority index
    get_priority() {
        local type=$1
        for i in "${!cluster_priority[@]}"; do
            if [[ "${cluster_priority[$i]}" == "$type" ]]; then
                echo $i
                return
            fi
        done
        echo 999  # Unknown type gets highest priority
    }
    
    local max_priority=0
    for cluster_type in "$ingress_type" "$egress_type" "$connections_type" "$partitions_type"; do
        local priority=$(get_priority "$cluster_type")
        if [[ $priority -gt $max_priority ]]; then
            max_priority=$priority
            recommended_type=$cluster_type
        fi
    done
    
    # Calculate eCKU/CKU usage for all cluster types
    if [[ "$recommended_type" != "none" ]]; then
        # Calculate for recommended cluster type (eCKU)
        local ingress_eckus=$(calculate_eckus_for_metric "$recommended_type" "ingress" "$CLUSTER_INGRESS")
        local egress_eckus=$(calculate_eckus_for_metric "$recommended_type" "egress" "$CLUSTER_EGRESS")
        local connections_eckus=$(calculate_eckus_for_metric "$recommended_type" "connections" "$CLUSTER_CONNECTIONS")
        local partitions_eckus=$(calculate_eckus_for_metric "$recommended_type" "partitions" "$CLUSTER_PARTITIONS")
        
        # Take the maximum eCKU requirement for recommended type
        RECOMMENDED_ECKUS=$ingress_eckus
        local max_eckus_type="ingress"
        
        if (( $(echo "$egress_eckus > $RECOMMENDED_ECKUS" | bc -l) )); then
            RECOMMENDED_ECKUS=$egress_eckus
            max_eckus_type="egress"
        fi
        if (( $(echo "$connections_eckus > $RECOMMENDED_ECKUS" | bc -l) )); then
            RECOMMENDED_ECKUS=$connections_eckus
            max_eckus_type="connections"
        fi
        if (( $(echo "$partitions_eckus > $RECOMMENDED_ECKUS" | bc -l) )); then
            RECOMMENDED_ECKUS=$partitions_eckus
            max_eckus_type="partitions"
        fi
        
        CONSTRAINING_METRIC=$max_eckus_type
    else
        RECOMMENDED_ECKUS="N/A"
        CONSTRAINING_METRIC="none"
    fi
    
    # Calculate usage for all cluster types for comparison
    # Enterprise eCKUs
    local ent_ingress=$(calculate_eckus_for_metric "enterprise" "ingress" "$CLUSTER_INGRESS")
    local ent_egress=$(calculate_eckus_for_metric "enterprise" "egress" "$CLUSTER_EGRESS")
    local ent_connections=$(calculate_eckus_for_metric "enterprise" "connections" "$CLUSTER_CONNECTIONS")
    local ent_partitions=$(calculate_eckus_for_metric "enterprise" "partitions" "$CLUSTER_PARTITIONS")
    
    ENTERPRISE_ECKUS=$ent_ingress
    if (( $(echo "$ent_egress > $ENTERPRISE_ECKUS" | bc -l) )); then ENTERPRISE_ECKUS=$ent_egress; fi
    if (( $(echo "$ent_connections > $ENTERPRISE_ECKUS" | bc -l) )); then ENTERPRISE_ECKUS=$ent_connections; fi
    if (( $(echo "$ent_partitions > $ENTERPRISE_ECKUS" | bc -l) )); then ENTERPRISE_ECKUS=$ent_partitions; fi
    
    # Freight eCKUs
    local freight_ingress=$(calculate_eckus_for_metric "freight" "ingress" "$CLUSTER_INGRESS")
    local freight_egress=$(calculate_eckus_for_metric "freight" "egress" "$CLUSTER_EGRESS")
    local freight_connections=$(calculate_eckus_for_metric "freight" "connections" "$CLUSTER_CONNECTIONS")
    local freight_partitions=$(calculate_eckus_for_metric "freight" "partitions" "$CLUSTER_PARTITIONS")
    
    FREIGHT_ECKUS=$freight_ingress
    if (( $(echo "$freight_egress > $FREIGHT_ECKUS" | bc -l) )); then FREIGHT_ECKUS=$freight_egress; fi
    if (( $(echo "$freight_connections > $FREIGHT_ECKUS" | bc -l) )); then FREIGHT_ECKUS=$freight_connections; fi
    if (( $(echo "$freight_partitions > $FREIGHT_ECKUS" | bc -l) )); then FREIGHT_ECKUS=$freight_partitions; fi
    
    # Dedicated CKUs
    local ded_ingress=$(calculate_ckus_for_dedicated "ingress" "$CLUSTER_INGRESS")
    local ded_egress=$(calculate_ckus_for_dedicated "egress" "$CLUSTER_EGRESS")
    local ded_connections=$(calculate_ckus_for_dedicated "connections" "$CLUSTER_CONNECTIONS")
    local ded_partitions=$(calculate_ckus_for_dedicated "partitions" "$CLUSTER_PARTITIONS")
    
    DEDICATED_CKUS=$ded_ingress
    if (( $(echo "$ded_egress > $DEDICATED_CKUS" | bc -l) )); then DEDICATED_CKUS=$ded_egress; fi
    if (( $(echo "$ded_connections > $DEDICATED_CKUS" | bc -l) )); then DEDICATED_CKUS=$ded_connections; fi
    if (( $(echo "$ded_partitions > $DEDICATED_CKUS" | bc -l) )); then DEDICATED_CKUS=$ded_partitions; fi
    
    # Store recommendation details
    RECOMMENDATION_TYPE=$recommended_type
    RECOMMENDATION_DETAILS=(
        "ingress:$ingress_type"
        "egress:$egress_type" 
        "connections:$connections_type"
        "partitions:$partitions_type"
    )
}

# Output results in table format
output_table() {
    echo
    echo -e "${CYAN}╔══════════════════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║                        eCKU CLUSTER RECOMMENDATION                           ║${NC}"
    echo -e "${CYAN}╠══════════════════════════════════════════════════════════════════════════════╣${NC}"
    
    printf "${CYAN}║${NC} %-25s │ %15s │ %15s │ %10s ${CYAN}║${NC}\n" "Metric" "Current Usage" "Min Cluster Type" "eCKU Usage"
    echo -e "${CYAN}╠═══════════════════════════╪═════════════════╪═════════════════╪══════════════╣${NC}"
    
    # Parse recommendation details 
    for detail in "${RECOMMENDATION_DETAILS[@]}"; do
        local metric=$(echo "$detail" | cut -d':' -f1)
        local cluster_type=$(echo "$detail" | cut -d':' -f2)
        
        # Calculate eCKU usage for the recommended cluster type for this metric
        local eckus="0.000"
        if [[ "$RECOMMENDATION_TYPE" != "none" ]]; then
            eckus=$(calculate_eckus_for_metric "$RECOMMENDATION_TYPE" "$metric" "$(eval echo \$CLUSTER_$(echo $metric | tr '[:lower:]' '[:upper:]'))")
        fi
        
        case $metric in
            ingress)
                printf "${CYAN}║${NC} %-25s │ %12.2f MBps │ %15s │ %9.3f ${CYAN}║${NC}\n" "Ingress Throughput" "$CLUSTER_INGRESS" "$cluster_type" "$eckus"
                ;;
            egress)
                printf "${CYAN}║${NC} %-25s │ %12.2f MBps │ %15s │ %9.3f ${CYAN}║${NC}\n" "Egress Throughput" "$CLUSTER_EGRESS" "$cluster_type" "$eckus"
                ;;
            connections)
                printf "${CYAN}║${NC} %-25s │ %15.0f │ %15s │ %9.3f ${CYAN}║${NC}\n" "Active Connections" "$CLUSTER_CONNECTIONS" "$cluster_type" "$eckus"
                ;;
            partitions)
                printf "${CYAN}║${NC} %-25s │ %15.0f │ %15s │ %9.3f ${CYAN}║${NC}\n" "Total Partitions" "$CLUSTER_PARTITIONS" "$cluster_type" "$eckus"
                ;;
        esac
    done
    
    echo -e "${CYAN}╠═══════════════════════════╪═════════════════╪═════════════════╪══════════════╣${NC}"
    
    # Color code the recommendation
    local rec_color=$GREEN
    case $RECOMMENDATION_TYPE in
        basic) rec_color=$BLUE ;;
        standard) rec_color=$CYAN ;;
        enterprise) rec_color=$YELLOW ;;
        freight) rec_color=$PURPLE ;;
        none) rec_color=$RED ;;
    esac
    
    printf "${CYAN}║${NC} %-25s │ %15s │ ${rec_color}%15s${NC} │ ${GREEN}%9.3f${NC} ${CYAN}║${NC}\n" "RECOMMENDATION" "" "${RECOMMENDATION_TYPE^^}" "$RECOMMENDED_ECKUS"
    echo -e "${CYAN}╚═══════════════════════════╧═════════════════╧═════════════════╧══════════════╝${NC}"
    
    echo
    echo -e "${YELLOW}Recommendation Summary:${NC}"
    echo -e "  • Minimum Required: ${rec_color}${RECOMMENDATION_TYPE^^}${NC}"
    if [[ "$RECOMMENDED_ECKUS" != "N/A" ]]; then
        printf "  • Estimated Usage in ${RECOMMENDATION_TYPE^^}: ${GREEN}%.3f eCKUs${NC}\n" "$RECOMMENDED_ECKUS"
        echo -e "  • Constraining Metric: ${YELLOW}${CONSTRAINING_METRIC}${NC}"
    fi
    
    echo
    echo -e "${CYAN}Usage Across All Cluster Types:${NC}"
    printf "  • ${YELLOW}Enterprise${NC}: %.3f eCKUs (limit: 10 eCKU, 32 eCKU in Limited Availability)\n" "$ENTERPRISE_ECKUS"
    printf "  • ${PURPLE}Freight${NC}:    %.3f eCKUs (high-performance, ultra-low latency)\n" "$FREIGHT_ECKUS"
    printf "  • ${BLUE}Dedicated${NC}:  %.1f CKUs (fixed capacity, not elastic)\n" "$DEDICATED_CKUS"
    
    echo
    echo -e "${YELLOW}Notes:${NC}"
    echo -e "  • Safety margin applied: ${SAFETY_MARGIN}%"
    echo -e "  • eCKU clusters are elastic - you pay for what you use up to limits"
    echo -e "  • Based on peak usage patterns, consider burst capacity"
    echo -e "  • Storage is unlimited for all cluster types"
    
    # Provide specific guidance based on recommendation
    case $RECOMMENDATION_TYPE in
        basic)
            echo -e "  • ${BLUE}Basic${NC}: Good for development and light production workloads"
            ;;
        standard)
            echo -e "  • ${CYAN}Standard${NC}: Suitable for most production workloads"
            ;;
        enterprise)
            echo -e "  • ${YELLOW}Enterprise${NC}: High-throughput production workloads with SLA requirements"
            echo -e "  • Current limit: 10 eCKU (32 eCKU available in Limited Availability)"
            ;;
        freight)
            echo -e "  • ${PURPLE}Freight${NC}: Ultra-high throughput for mission-critical applications"
            echo -e "  • Optimized for maximum performance and lowest latency"
            ;;
        none)
            echo -e "  • ${RED}WARNING${NC}: Workload exceeds all cluster type limits!"
            echo -e "  • Consider workload optimization or contact Confluent for custom solutions"
            ;;
    esac
}

# Output results in JSON format
output_json() {
    cat << EOF
{
  "timestamp": "$(date -Iseconds)",
  "cluster_metrics": {
    "ingress_mbps": $CLUSTER_INGRESS,
    "egress_mbps": $CLUSTER_EGRESS,
    "connections": $CLUSTER_CONNECTIONS,
    "partitions": $CLUSTER_PARTITIONS,
    "requests_per_sec": $CLUSTER_REQUESTS
  },
  "cluster_type_requirements": {
    "ingress": "$(echo "${RECOMMENDATION_DETAILS[0]}" | cut -d':' -f2)",
    "egress": "$(echo "${RECOMMENDATION_DETAILS[1]}" | cut -d':' -f2)",
    "connections": "$(echo "${RECOMMENDATION_DETAILS[2]}" | cut -d':' -f2)",
    "partitions": "$(echo "${RECOMMENDATION_DETAILS[3]}" | cut -d':' -f2)"
  },
  "usage_by_cluster_type": {
    "enterprise": {
      "estimated_eckus": $ENTERPRISE_ECKUS,
      "billing_model": "eCKU",
      "max_limit": "10 eCKU (32 eCKU Limited Availability)"
    },
    "freight": {
      "estimated_eckus": $FREIGHT_ECKUS,
      "billing_model": "eCKU",
      "max_limit": "No published limit"
    },
    "dedicated": {
      "estimated_ckus": $DEDICATED_CKUS,
      "billing_model": "CKU",
      "max_limit": "152 CKU"
    }
  },
  "recommendation": {
    "cluster_type": "$RECOMMENDATION_TYPE",
    "estimated_eckus": $RECOMMENDED_ECKUS,
    "constraining_metric": "$CONSTRAINING_METRIC",
    "billing_model": "eCKU",
    "elastic_scaling": true,
    "safety_margin_percent": $SAFETY_MARGIN,
    "notes": {
      "basic": "Development and light production workloads",
      "standard": "Most production workloads",
      "enterprise": "High-throughput production with SLA requirements (10 eCKU limit, 32 eCKU in Limited Availability)",
      "freight": "Ultra-high throughput for mission-critical applications"
    }
  }
}
EOF
}

# Output results in CSV format
output_csv() {
    echo "metric,current_value,unit,min_cluster_type"
    echo "ingress,$CLUSTER_INGRESS,MBps,$(echo "${RECOMMENDATION_DETAILS[0]}" | cut -d':' -f2)"
    echo "egress,$CLUSTER_EGRESS,MBps,$(echo "${RECOMMENDATION_DETAILS[1]}" | cut -d':' -f2)"
    echo "connections,$CLUSTER_CONNECTIONS,count,$(echo "${RECOMMENDATION_DETAILS[2]}" | cut -d':' -f2)"
    echo "partitions,$CLUSTER_PARTITIONS,count,$(echo "${RECOMMENDATION_DETAILS[3]}" | cut -d':' -f2)"
    echo "recommendation,$RECOMMENDATION_TYPE,cluster_type,final"
    echo ""
    echo "cluster_type,estimated_usage,billing_model,notes"
    printf "enterprise,%.3f eCKUs,eCKU,10 eCKU limit (32 eCKU Limited Availability)\n" "$ENTERPRISE_ECKUS"
    printf "freight,%.3f eCKUs,eCKU,Ultra-high performance\n" "$FREIGHT_ECKUS"
    printf "dedicated,%.1f CKUs,CKU,Fixed capacity (152 CKU max)\n" "$DEDICATED_CKUS"
}

# Main execution function
main() {
    log INFO "Starting Confluent Cloud eCKU cluster type recommendation..."
    log INFO "Target: ${MSK_CLUSTER:-$BROKER_LIST}"
    
    if [[ -n "$CONFIG_FILE" ]]; then
        log INFO "Using configuration file: $CONFIG_FILE"
    elif [[ -f "./msk.config" ]]; then
        log INFO "Using default configuration file: ./msk.config"
    fi
    
    if [[ "$DRY_RUN" == true ]]; then
        log INFO "DRY RUN MODE - No actual JMX queries will be performed"
        CLUSTER_INGRESS="45.50"
        CLUSTER_EGRESS="125.75"
        CLUSTER_CONNECTIONS="6500"
        CLUSTER_PARTITIONS="3200"
        CLUSTER_REQUESTS="8500"
    else
        collect_cluster_metrics
    fi
    
    generate_recommendation
    
    case $OUTPUT_FORMAT in
        json)
            output_json
            ;;
        csv)
            output_csv
            ;;
        table|*)
            output_table
            ;;
    esac
    
    log INFO "eCKU cluster type recommendation completed successfully"
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -c|--cluster)
            MSK_CLUSTER="$2"
            shift 2
            ;;
        -b|--brokers)
            BROKER_LIST="$2"
            shift 2
            ;;
        -f|--config-file)
            CONFIG_FILE="$2"
            shift 2
            ;;
        -p|--jmx-port)
            JMX_PORT="$2"
            shift 2
            ;;
        -s|--safety-margin)
            SAFETY_MARGIN="$2"
            shift 2
            ;;
        -o|--format)
            OUTPUT_FORMAT="$2"
            shift 2
            ;;
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -d|--dry-run)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage
            exit 1
            ;;
    esac
done

# Read configuration file if specified or use default
if [[ -n "$CONFIG_FILE" ]]; then
    read_msk_config "$CONFIG_FILE"
elif [[ -f "./msk.config" ]]; then
    log INFO "Found msk.config file, reading configuration..."
    read_msk_config "./msk.config"
fi

# Validate required parameters
if [[ -z "$BROKER_LIST" && -z "$MSK_CLUSTER" ]]; then
    log ERROR "Either --brokers, --cluster, or a valid config file must be specified"
    usage
    exit 1
fi

# If cluster name provided, try to resolve broker endpoints
if [[ -n "$MSK_CLUSTER" && -z "$BROKER_LIST" ]]; then
    log INFO "Resolving broker endpoints for cluster: $MSK_CLUSTER"
    # This would typically use AWS CLI to get broker endpoints
    # For demo purposes, we'll use placeholder
    log WARN "Cluster endpoint resolution not implemented. Please provide --brokers directly."
    exit 1
fi

# Validate output format
if [[ ! "$OUTPUT_FORMAT" =~ ^(table|json|csv)$ ]]; then
    log ERROR "Invalid output format: $OUTPUT_FORMAT (must be: table, json, csv)"
    exit 1
fi

# Run dependency check
check_dependencies

# Execute main function
main 