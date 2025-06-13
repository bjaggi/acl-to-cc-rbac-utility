# MSK ACL to Confluent Cloud RBAC Utility

This Java-based utility connects to Amazon MSK (Managed Streaming for Apache Kafka) clusters and extracts all Access Control Lists (ACLs) to a JSON file. The exported ACLs can then be used for migration to Confluent Cloud RBAC (Role-Based Access Control).

## Features

- ✅ Connect to Amazon MSK clusters using various authentication methods
- ✅ Export all ACLs to structured JSON format
- ✅ Support for SSL, SASL_SSL, and IAM authentication
- ✅ **Automatic JAVA_HOME detection and setup**
- ✅ **Smart bootstrap server selection** (fixes port/authentication mismatches)
- ✅ **Robust SSL configuration handling** (handles invalid truststore paths)
- ✅ Include cluster metadata in export
- ✅ Interactive mode for easy configuration
- ✅ Configuration file support
- ✅ Comprehensive logging and error handling
- ✅ Shell scripts for building and running

## Prerequisites

- **Java 11 or higher** (Java 17 recommended)
  - For Amazon Linux/EC2: `sudo yum install java-17-amazon-corretto`
  - For Ubuntu/Debian: `sudo apt-get install openjdk-17-jdk`
- **Maven 3.6 or higher**
  - For Amazon Linux: `sudo yum install maven`
  - For Ubuntu/Debian: `sudo apt-get install maven`
- **JAVA_HOME environment variable** properly set
  - The build and run scripts will attempt to set this automatically
  - Manual setup: `export JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto`
- **AWS credentials configured** (via AWS CLI, environment variables, or IAM roles)
  - Test with: `aws sts get-caller-identity`
- **Network access to your MSK cluster**
  - Ensure security groups allow connections from your IP

## Quick Start

1. **Make scripts executable:**
   ```bash
   chmod +x build.sh extract-msk-acls.sh extract-acls.sh
   ```

2. **Build the application:**
   ```bash
   ./build.sh
   ```

3. **Configure MSK connection in msk.config:**
   ```bash
   # Edit msk.config with your actual MSK cluster details
   vi msk.config
   ```

4. **Extract ACLs and topics from MSK:**
   ```bash
   ./extract-msk-acls.sh
   ```

5. **Convert to Confluent Cloud RBAC (optional):**
   ```bash
   ./scripts/convert-acl-to-rbac.sh -e env-12345 -c lkc-67890
   ```

## Alternative: Advanced Usage with extract-acls.sh

For advanced usage, interactive mode, or custom configurations:

```bash
# Interactive mode
./extract-acls.sh --interactive

# Direct command line
./extract-acls.sh --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123
```

## Authentication Methods

The utility automatically selects the correct bootstrap servers and ports based on your authentication method:

| Authentication Method | Security Protocol | Port | Bootstrap Server Type |
|-----------------------|-------------------|------|----------------------|
| **SSL** (Default)     | `SSL`            | 9094 | TLS                  |
| **IAM** (Recommended) | `SASL_SSL`       | 9098 | SASL_IAM            |
| **SCRAM**             | `SASL_SSL`       | 9096 | SASL_SCRAM          |
| **Plaintext**         | `PLAINTEXT`      | 9092 | Plaintext           |

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
- `extract-msk-acls.sh` - Simple MSK ACL and topic extractor (reads from msk.config)
- `extract-acls.sh` - Advanced script with interactive mode and flexible configuration

### Manual Build

```bash
mvn clean package
java -jar target/acl-to-cc-rbac-utility-1.0.0.jar --help
```

## Troubleshooting

### Common Issues

1. **JAVA_HOME Not Set Error**
   ```
   The JAVA_HOME environment variable is not defined correctly
   ```
   **Solution:**
   - The build and run scripts automatically detect and set JAVA_HOME
   - For manual setup: `export JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto`
   - Verify with: `echo $JAVA_HOME && java -version`

2. **SSL Keystore Loading Error**
   ```
   KafkaException: Failed to load SSL keystore of type JKS
   Caused by: java.io.IOException: Is a directory
   ```
   **Solution:**
   - This happens when SSL truststore points to a directory instead of a file
   - The application now automatically handles this by skipping invalid truststore paths
   - For SASL_SSL with IAM: No truststore configuration needed

3. **Wrong Bootstrap Server Port Error**
   ```
   Unexpected handshake request with client mechanism AWS_MSK_IAM, enabled mechanisms are []
   ```
   **Solution:**
   - This happens when using wrong port for authentication method
   - Fixed: Application now automatically selects correct bootstrap servers:
     - `SASL_SSL` with `AWS_MSK_IAM` → Uses port 9098 (SASL_IAM servers)
     - `SSL` → Uses port 9094 (TLS servers)
     - `SASL_SSL` with `SCRAM` → Uses port 9096 (SASL_SCRAM servers)

4. **AWS Credentials Error**
   - Ensure AWS credentials are configured: `aws configure` or `aws sts get-caller-identity`
   - Check if credentials have required MSK permissions (see Permissions section below)

5. **Connection Timeout**
   - Check network connectivity to MSK cluster
   - Verify security groups allow access from your IP
   - For port 9098 (SASL_IAM), ensure security group allows TCP 9098

6. **Authentication Failed**
   - For IAM auth: Ensure your AWS credentials have necessary MSK permissions
   - For SCRAM: Verify username/password are correct
   - Check that the authentication method matches your MSK cluster configuration

### Permissions Required

For IAM authentication, your AWS credentials need these permissions:

**MSK Cluster Permissions:**
- `kafka:DescribeCluster`
- `kafka:GetBootstrapBrokers`

**MSK Connect Permissions:**
- `kafka-cluster:Connect` (for cluster ARN)
- `kafka-cluster:DescribeCluster` (for cluster ARN)
- `kafka-cluster:AlterCluster` (if modifying ACLs)
- `kafka-cluster:DescribeClusterDynamicConfiguration`

**Example IAM Policy:**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "kafka:DescribeCluster",
        "kafka:GetBootstrapBrokers"
      ],
      "Resource": "arn:aws:kafka:*:*:cluster/*"
    },
    {
      "Effect": "Allow", 
      "Action": [
        "kafka-cluster:Connect",
        "kafka-cluster:DescribeCluster",
        "kafka-cluster:DescribeClusterDynamicConfiguration"
      ],
      "Resource": "arn:aws:kafka:*:*:cluster/*"
    }
  ]
}
```

### Logs

Check logs in the `logs/` directory for detailed error information:
- `logs/msk-acl-extractor.log`

## License

This project is licensed under the Apache License 2.0.
