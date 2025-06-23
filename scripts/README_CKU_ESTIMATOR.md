# Confluent Cloud eCKU Cluster Type Recommender

This script analyzes your MSK cluster's JMX metrics to recommend the appropriate Confluent Cloud cluster type using eCKU (Elastic Confluent Kafka Units) for migrating to Confluent Cloud.

## Features

- **Automated Configuration Reading**: Reads broker endpoints and settings from your existing `msk.config` file
- **Real-time JMX Metrics**: Collects live metrics from your MSK cluster (ingress, egress, connections, partitions)
- **Safety Margin Calculation**: Applies configurable safety margins to handle traffic spikes
- **Multiple Output Formats**: Supports table, JSON, and CSV output formats
- **eCKU Cluster Type Recommendations**: Maps your usage to appropriate Confluent Cloud cluster types (Basic, Standard, Enterprise, Freight)

## Usage

### Quick Start with msk.config

```bash
# Use your existing msk.config file
./scripts/cc-cku-estimator.sh -f msk.config

# With custom safety margin and JSON output
./scripts/cc-cku-estimator.sh -f msk.config -s 30 -o json

# Verbose output for debugging
./scripts/cc-cku-estimator.sh -f msk.config --verbose
```

### Manual Broker Specification

```bash
# Specify brokers directly
./scripts/cc-cku-estimator.sh -b broker1.example.com,broker2.example.com -p 11001

# With custom settings
./scripts/cc-cku-estimator.sh -b 10.0.1.10,10.0.1.11,10.0.1.12 -p 11001 -s 25 -o csv
```

### Dry Run Mode

```bash
# Test without actual JMX queries (uses simulated data)
./scripts/cc-cku-estimator.sh -f msk.config --dry-run
```

## Configuration File Support

The script automatically reads from your `msk.config` file if present, extracting:

- **Bootstrap Servers**: Converted to JMX endpoints automatically
- **Cluster ARN**: Used for identification
- **AWS Region**: For regional context
- **Verbose Logging**: If enabled in the config

### Example msk.config

```properties
bootstrap.servers=broker1.example.com:9098,broker2.example.com:9098,broker3.example.com:9098
cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123
aws.region=us-east-1
logging.verbose=true
```

## JMX Metrics Analyzed

| Metric | JMX ObjectName | Purpose |
|--------|----------------|---------|
| **Ingress Throughput** | `kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec` | Data flowing into cluster |
| **Egress Throughput** | `kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec` | Data flowing out of cluster |
| **Active Connections** | `kafka.server:type=socket-server-metrics,name=connection-count` | Client connections |
| **Total Partitions** | `kafka.controller:type=KafkaController,name=GlobalPartitionCount` | Topic partitions |
| **Request Rate** | `kafka.network:type=RequestMetrics,name=RequestsPerSec` | Requests per second |

## eCKU Cluster Type Mapping

Based on the [Confluent Cloud cluster comparison](https://docs.confluent.io/cloud/current/clusters/cluster-types.html#ecku-cku-comparison), eCKU clusters are elastic and you pay for what you use up to the following limits:

| Cluster Type | Ingress | Egress | Connections | Partitions |
|--------------|---------|--------|-------------|------------|
| **Basic** | 250 MBps | 750 MBps | 1,000 | 4,096 |
| **Standard** | 250 MBps | 750 MBps | 10,000 | 4,096 |
| **Enterprise** | 600 MBps | 1,800 MBps | 45,000 | 30,000 |
| **Freight** | 9,120 MBps | 27,360 MBps | 2,736,000 | 50,000 |

**Note**: Enterprise clusters currently have a 10 eCKU maximum limit, with 32 eCKU available in Limited Availability.

## Output Formats

### Table Format (Default)
```
╔══════════════════════════════════════════════════════════════╗
║                 eCKU CLUSTER RECOMMENDATION                  ║
╠══════════════════════════════════════════════════════════════╣
║ Metric                    │   Current Usage │ Min Cluster Type ║
╠═══════════════════════════╪═════════════════╪═════════════════╣
║ Ingress Throughput        │        45.50 MB/s │           basic ║
║ Egress Throughput         │       125.75 MB/s │           basic ║
║ Active Connections        │            6500 │        standard ║
║ Total Partitions          │            3200 │           basic ║
╠═══════════════════════════╪═════════════════╪═════════════════╣
║ RECOMMENDATION            │                 │        STANDARD ║
╚═══════════════════════════╧═════════════════╧═════════════════╝
```

### JSON Format
```json
{
  "timestamp": "2025-06-23T19:06:01+00:00",
  "cluster_metrics": {
    "ingress_mbps": 45.50,
    "egress_mbps": 125.75,
    "connections": 6500,
    "partitions": 3200,
    "requests_per_sec": 8500
  },
  "cluster_type_requirements": {
    "ingress": "basic",
    "egress": "basic",
    "connections": "standard",
    "partitions": "basic"
  },
  "recommendation": {
    "cluster_type": "standard",
    "billing_model": "eCKU",
    "elastic_scaling": true,
    "safety_margin_percent": 20,
    "notes": {
      "basic": "Development and light production workloads",
      "standard": "Most production workloads",
      "enterprise": "High-throughput production with SLA requirements",
      "freight": "Ultra-high throughput for mission-critical applications"
    }
  }
}
```

### CSV Format
```csv
metric,current_value,unit,min_cluster_type
ingress,45.50,MBps,basic
egress,125.75,MBps,basic
connections,6500,count,standard
partitions,3200,count,basic
recommendation,standard,cluster_type,final
billing_model,eCKU,elastic_scaling,pay_for_usage
```

## Command Line Options

```
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
```

## Prerequisites

The script requires these tools to be installed:
- `jq` - JSON processing
- `bc` - Floating point calculations
- `curl` - HTTP requests (if using JMX over HTTP)

### Install Dependencies

**Ubuntu/Debian:**
```bash
sudo apt-get install jq bc curl
```

**Amazon Linux:**
```bash
sudo yum install jq bc curl
```

## JMX Access Methods

The script supports multiple JMX access methods (in order of preference):

1. **jmxterm** - If available, uses the jmxterm tool
2. **jmxquery** - If available, uses jmxquery
3. **Simulation** - Falls back to realistic simulated data for testing

## Integration Examples

### CI/CD Pipeline
```bash
#!/bin/bash
# Check cluster type requirements as part of migration planning
CLUSTER_TYPE=$(./scripts/cc-cku-estimator.sh -f msk.config -o json | jq -r '.recommendation.cluster_type')
echo "Recommended cluster type for migration: $CLUSTER_TYPE"

if [ "$CLUSTER_TYPE" = "freight" ]; then
    echo "High throughput workload detected. Verify Freight cluster availability."
elif [ "$CLUSTER_TYPE" = "none" ]; then
    echo "WARNING: Workload exceeds all cluster limits. Consider optimization."
fi
```

### Monitoring Script
```bash
#!/bin/bash
# Regular cluster type assessment for capacity planning
./scripts/cc-cku-estimator.sh -f msk.config -o csv >> cluster_type_history.csv
```

## Troubleshooting

### JMX Connection Issues
- Ensure JMX port (default 11001) is accessible from your machine
- Check firewall and security group settings
- Verify JMX is enabled on your MSK brokers

### Configuration File Issues
- Ensure `msk.config` exists and is readable
- Check that `bootstrap.servers` is properly formatted
- Verify broker hostnames are resolvable

### Permission Issues
- Ensure the script is executable: `chmod +x scripts/cc-cku-estimator.sh`
- Check AWS credentials if using IAM authentication

## Best Practices

1. **Run During Peak Hours**: Get estimates during your highest traffic periods
2. **Use Appropriate Safety Margins**: 20-30% is recommended for production workloads
3. **Consider Burst Patterns**: Account for temporary traffic spikes
4. **Regular Monitoring**: Run estimations periodically as usage patterns change
5. **Multiple Measurements**: Take several measurements over time for accurate sizing

## Notes

- **Storage**: All cluster types include unlimited storage
- **Network**: Ingress/egress limits are the primary constraints for cluster type selection
- **Connections**: Consider connection pooling to optimize usage and potentially use lower tier clusters
- **Partitions**: Higher partition counts may require Enterprise or Freight cluster types
- **Elastic Billing**: eCKU clusters scale elastically - you only pay for what you use up to the cluster limits
- **SLA**: All cluster types provide 99.95% uptime SLA, with 99.99% available for Standard, Enterprise, and Freight 