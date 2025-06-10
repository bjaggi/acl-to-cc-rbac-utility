# MSK ACL to Confluent Cloud RBAC Utility

This Java-based utility connects to Amazon MSK (Managed Streaming for Apache Kafka) clusters and extracts all Access Control Lists (ACLs) to a JSON file. The exported ACLs can then be used for migration to Confluent Cloud RBAC (Role-Based Access Control).

## Features

- ✅ Connect to Amazon MSK clusters using various authentication methods
- ✅ Export all ACLs to structured JSON format
- ✅ Support for SSL, SASL_SSL, and IAM authentication
- ✅ Include cluster metadata in export
- ✅ Interactive mode for easy configuration
- ✅ Configuration file support
- ✅ Comprehensive logging
- ✅ Shell scripts for building and running

## Prerequisites

- Java 11 or higher
- Maven 3.6 or higher
- AWS credentials configured (via AWS CLI, environment variables, or IAM roles)
- Network access to your MSK cluster

## Quick Start

1. **Make scripts executable:**
   ```bash
   chmod +x build.sh run.sh extract-acls.sh
   ```

2. **Build the application:**
   ```bash
   ./build.sh
   ```

3. **Run in interactive mode:**
   ```bash
   ./extract-acls.sh --interactive
   ```

4. **Or run directly with parameters:**
   ```bash
   ./extract-acls.sh --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123
   ```

## Authentication Methods

### SSL (Default)
```bash
./extract-acls.sh --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123
```

### IAM Authentication (Recommended for MSK)
```bash
./extract-acls.sh \
  --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123 \
  --security-protocol SASL_SSL \
  --sasl-mechanism AWS_MSK_IAM
```

### SCRAM Authentication
```bash
./extract-acls.sh \
  --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123 \
  --security-protocol SASL_SSL \
  --sasl-mechanism SCRAM-SHA-256 \
  --sasl-username myuser \
  --sasl-password mypassword
```

## Usage Options

### Command Line Interface

```bash
./extract-acls.sh [OPTIONS]

Options:
  -h, --help              Show help message
  -i, --interactive       Run in interactive mode
  -c, --config FILE       Use configuration file
  --cluster-arn ARN       MSK cluster ARN (required)
  --region REGION         AWS region (default: us-east-1)
  --output-file FILE      Output JSON file
  --security-protocol     Security protocol (SSL, SASL_SSL, etc.)
  --sasl-mechanism        SASL mechanism (AWS_MSK_IAM, SCRAM-SHA-256, etc.)
  --sasl-username         SASL username
  --sasl-password         SASL password
  --no-metadata           Exclude cluster metadata
  --verbose               Enable verbose logging
```

### Configuration File

Create and use a configuration file for repeated use:

```bash
# Create a sample configuration file
./extract-acls.sh --create-sample-config

# Copy and edit the configuration file
cp config.properties.sample config.properties
# Edit config.properties with your MSK cluster details

# Run with configuration file
./extract-acls.sh --config config.properties
```

**Configuration File Format:**
```properties
# Required settings
cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123

# Optional settings
aws.region=us-east-1
output.file=msk_acls.json
security.protocol=SSL
sasl.mechanism=AWS_MSK_IAM
output.no-metadata=false
logging.verbose=false
```

The configuration file supports both dot-notation (`cluster.arn`) and uppercase (`CLUSTER_ARN`) property names for backward compatibility.

### MSK Properties File

For more advanced MSK-specific configurations, you can also create a dedicated `msk.properties` file:

**Sample msk.properties for SSL (Default):**
```properties
# Basic MSK cluster configuration
cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-msk-cluster/abc-123-def-456
aws.region=us-east-1

# SSL Configuration (Default)
security.protocol=SSL
ssl.endpoint.identification.algorithm=https

# Client settings
client.id=msk-acl-extractor
request.timeout.ms=30000
admin.request.timeout.ms=60000
```

**Sample msk.properties for IAM Authentication:**
```properties
# Basic MSK cluster configuration
cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-msk-cluster/abc-123-def-456
aws.region=us-east-1

# IAM Authentication (Recommended for MSK)
security.protocol=SASL_SSL
sasl.mechanism=AWS_MSK_IAM
sasl.jaas.config=software.amazon.msk.auth.iam.IAMLoginModule required;
sasl.client.callback.handler.class=software.amazon.msk.auth.iam.IAMClientCallbackHandler

# Client settings
client.id=msk-acl-extractor
request.timeout.ms=30000
admin.request.timeout.ms=60000
```

**Sample msk.properties for SCRAM Authentication:**
```properties
# Basic MSK cluster configuration
cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-msk-cluster/abc-123-def-456
aws.region=us-east-1

# SCRAM Authentication
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-256
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="your-username" password="your-password";

# Client settings
client.id=msk-acl-extractor
request.timeout.ms=30000
admin.request.timeout.ms=60000
```

**Usage with MSK properties:**
```bash
# Create your msk.properties file with the appropriate configuration above
# Then run:
./extract-acls.sh --config msk.properties
```

### Interactive Mode

For guided setup:
```bash
./extract-acls.sh --interactive
```

## Output Format

The utility generates a JSON file with the following structure:

```json
{
  "acls": [
    {
      "principal": "User:alice",
      "host": "*",
      "operation": "READ",
      "permission_type": "ALLOW",
      "resource_type": "TOPIC",
      "resource_name": "my-topic",
      "pattern_type": "LITERAL"
    }
  ],
  "count": 1,
  "exported_at": "2024-01-01T12:00:00",
  "cluster_metadata": {
    "cluster_name": "my-cluster",
    "cluster_arn": "arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123",
    "state": "ACTIVE",
    "kafka_version": "2.8.1",
    "number_of_broker_nodes": 3,
    "instance_type": "kafka.m5.large",
    "region": "us-east-1"
  }
}
```

## Building and Running

### Build Scripts

- `build.sh` - Build the Java application using Maven
- `run.sh` - Simple runner script
- `extract-acls.sh` - Comprehensive script with examples and interactive mode

### Manual Build

```bash
mvn clean package
java -jar target/acl-to-cc-rbac-utility-1.0.0.jar --help
```

## Troubleshooting

### Common Issues

1. **AWS Credentials Error**
   - Ensure AWS credentials are configured: `aws configure` or `aws sts get-caller-identity`

2. **Connection Timeout**
   - Check network connectivity to MSK cluster
   - Verify security groups allow access from your IP

3. **Authentication Failed**
   - For IAM auth: Ensure your AWS credentials have necessary MSK permissions
   - For SCRAM: Verify username/password are correct

### Permissions Required

For IAM authentication, your AWS credentials need:
- `kafka:DescribeCluster`
- `kafka:GetBootstrapBrokers`
- `kafka-cluster:Connect`
- `kafka-cluster:DescribeCluster`

### Logs

Check logs in the `logs/` directory for detailed error information:
- `logs/msk-acl-extractor.log`

## License

This project is licensed under the Apache License 2.0.
