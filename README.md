# MSK to Confluent Cloud Migration Utility

A comprehensive Java-based utility that extracts metadata from Amazon MSK (Managed Streaming for Apache Kafka) clusters and facilitates migration to Confluent Cloud with automated RBAC (Role-Based Access Control) setup.

## 🚀 Features

- ✅ **Complete MSK Metadata Extraction**: ACLs, Topics, Consumer Groups, and Schemas (all versions)
- ✅ **Automated Schema Registry Integration**: AWS Glue Schema Registry support
- ✅ **Schema Migration**: Migrate schemas from AWS Glue to Confluent Cloud Schema Registry
- ✅ **Consumer Group Analysis**: Extract and analyze consumer group information
- ✅ **Confluent Cloud RBAC Conversion**: Convert MSK ACLs to CC role bindings
- ✅ **Automated RBAC Application**: Automatically apply roles to Confluent Cloud
- ✅ **Service Account Management**: Auto-create and manage service accounts
- ✅ **Credentials Management**: Generate and organize service account credentials
- ✅ **Multiple Authentication Methods**: SSL, SASL_SSL, and IAM authentication
- ✅ **Smart Configuration**: Automatic JAVA_HOME detection and bootstrap server selection
- ✅ **Comprehensive Logging**: Detailed logging with configurable levels
- ✅ **Dry Run Mode**: Preview changes before applying
- ✅ **End-to-End Workflow**: Complete migration pipeline from MSK to Confluent Cloud

## 🎯 Getting Started

**Want to migrate from MSK to Confluent Cloud?** It's simple:

1. **Setup**: Configure your MSK and Confluent Cloud connections
2. **Extract**: Run `./scripts/extract_msk_metadata/extract-msk-metadata.sh` 
3. **Migrate**: Follow the step-by-step sequence below

**Pre-built and ready to use!** - No compilation required.

## 📋 Prerequisites

### ✅ Required (Must Have)

- **☕ Java 11 or higher** (Java 17 recommended)
  ```bash
  # Amazon Linux/EC2
  sudo yum install java-17-amazon-corretto
  
  # Verify installation
  java -version
  ```

- **🌐 Network Access to Confluent Cloud**
  - HTTPS access to Confluent Cloud APIs (api.confluent.cloud)
  - Access to your Confluent Cloud Kafka cluster endpoints
  - Access to Schema Registry endpoints (if using schema migration)

- **🔑 AWS Credentials** (for MSK access)
  ```bash
  
  # If not configured, set up AWS CLI
  aws configure


  # Test your AWS access
  aws sts get-caller-identity

  ```

- **🌐 Network Access to MSK**
  - Your machine can reach MSK cluster (security groups/NACLs allow traffic)
  - MSK cluster endpoints are accessible from your location

### 🔧 Optional (Only if Customizing)

- **🛠️ Maven 3.6+** (only needed if rebuilding the application)
  ```bash
  # Amazon Linux
  sudo yum install maven
  
  # Ubuntu/Debian  
  sudo apt-get install maven
  
  # Verify
  mvn -version
  ```

## ⚡ Quick Start - Complete Migration

Follow this **simple sequence** for a smooth MSK to Confluent Cloud migration:

### 1. **Setup (Optional Build)**
```bash
# Make scripts executable
chmod +x scripts/extract_msk_metadata/extract-msk-metadata.sh scripts/create_cc_infra/*.sh

# Optional: Build latest JAR (latest version already included in release/ folder)
# ./build.sh
```

**Note**: The utility comes with a pre-built JAR file in the `release/` folder, so building is optional unless you've made code changes.

### 2. **Configure MSK Connection**

Create/edit the MSK configuration file in the project root:

```bash
# Copy sample configuration
cp msk.config.sample msk.config

# Edit with your MSK cluster details
vi msk.config
```

**Configuration Options:**

**Option A: IAM Authentication (Recommended)**
```properties
# MSK Cluster ARN (REQUIRED)
cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123

# AWS Region
aws.region=us-east-1

# IAM Authentication
security.protocol=SASL_SSL
sasl.mechanism=AWS_MSK_IAM
sasl.jaas.config=software.amazon.msk.auth.iam.IAMLoginModule required;
sasl.client.callback.handler.class=software.amazon.msk.auth.iam.IAMClientCallbackHandler
```

**Option B: SSL Authentication**
```properties
cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123
aws.region=us-east-1
security.protocol=SSL
```

**Option C: SCRAM Authentication**
```properties
cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123
aws.region=us-east-1
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-256
sasl.username=your-username
sasl.password=your-password
```

**How to find your MSK Cluster ARN:**
- AWS Console: MSK service → Select cluster → Copy ARN
- AWS CLI: `aws kafka list-clusters`

### 3. **Configure Confluent Cloud Connection**

Create the Confluent Cloud configuration file in the project root:

```bash
# Create Confluent Cloud configuration
vi ccloud.config
```

**Complete Configuration Template:**
```properties
# =============================================================================
# CONFLUENT CLOUD CONFIGURATION
# =============================================================================

# Environment and Cluster Details
confluent.cloud.environment=env-12345
confluent.cloud.cluster=lkc-67890
confluent.cloud.organization=your-org-id

# =============================================================================
# KAFKA API KEYS (for topic operations)
# =============================================================================
sasl.username=YOUR_KAFKA_API_KEY
sasl.password=YOUR_KAFKA_API_SECRET
bootstrap.servers=pkc-xxxxx.region.aws.confluent.cloud:9092
security.protocol=SASL_SSL
sasl.mechanisms=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="YOUR_KAFKA_API_KEY" password="YOUR_KAFKA_API_SECRET";

# =============================================================================
# CLOUD API KEYS (for RBAC operations)
# =============================================================================
confluent_cloud_key=YOUR_CLOUD_API_KEY
confluent_cloud_secret=YOUR_CLOUD_API_SECRET

# =============================================================================
# SCHEMA REGISTRY (optional)
# =============================================================================
schema.registry.url=https://psrc-xxxxx.region.gcp.confluent.cloud
schema.registry.basic.auth.user.info=SR_API_KEY:SR_API_SECRET
```

**How to find your Confluent Cloud details:**
- **Environment ID**: Visible in CC Console URL (`env-12345`)
- **Cluster ID**: Visible in cluster URL (`lkc-67890`)
- **Organization ID**: Administration → Billing & payment

**⚠️ Critical**: You need **TWO types of API keys**:

1. **Kafka API Keys** (for topic operations):
   - Go to your Kafka cluster → API Keys tab
   - Create cluster-scoped key with DeveloperRead/Operator permissions

2. **Cloud API Keys** (for RBAC operations):
   - Administration → Access → Service Accounts
   - Create service account with OrganizationAdmin/EnvironmentAdmin role
   - Generate API keys for the service account

### 4. **Setup AWS Credentials**

Configure AWS access for MSK cluster:

```bash
# Method 1: AWS CLI
aws configure

# Method 2: Environment variables
export AWS_ACCESS_KEY_ID=your-access-key
export AWS_SECRET_ACCESS_KEY=your-secret-key
export AWS_DEFAULT_REGION=us-east-1

# Verify access
aws sts get-caller-identity
```

**Required AWS Permissions:**
- `kafka:DescribeCluster`, `kafka:ListClusters`, `kafka:GetBootstrapBrokers`
- `kafka-cluster:Connect`, `kafka-cluster:DescribeCluster`
- `glue:GetRegistry`, `glue:ListRegistries`, `glue:GetSchema`, `glue:ListSchemas`

### 5. **Verify Configuration**

Test your connections before running migration:

```bash
# Test MSK connection
java -jar release/msk-to-confluent-cloud.jar extract --cluster-arn "your-cluster-arn" --region "us-east-1" --dry-run

# Test Confluent Cloud API access
curl -u "YOUR_CLOUD_KEY:YOUR_CLOUD_SECRET" "https://api.confluent.cloud/org/v2/environments"

# Secure your config files
chmod 600 msk.config ccloud.config
```

### 6. **Execute Migration Sequence**

**🚀 Start Here**: Run the first script to extract all MSK metadata:

```bash
# STEP 1: Extract all MSK metadata (START HERE!)
./scripts/extract_msk_metadata/extract-msk-metadata.sh
```

**Then follow this sequence for complete migration:**

```bash
# STEP 2: Create topics in Confluent Cloud 
./scripts/create_cc_infra/create-cc-topics.sh

# STEP 3: Migrate schemas to Confluent Cloud Schema Registry
./scripts/create_cc_infra/create-cc-schemas.sh

# STEP 4: Create service accounts in Confluent Cloud
./scripts/create_cc_infra/create-cc-service-accounts.sh

# STEP 5: Apply RBAC role bindings to Confluent Cloud
./scripts/create_cc_infra/create-cc-rbac.sh

# STEP 6: Generate service account credentials (OPTIONAL)
./scripts/create_cc_infra/create-cc-sa-creds.sh
```

**Note**: Consumer groups are extracted for analysis but not automatically migrated. Use the generated `msk_consumer_groups.json` as reference for manual recreation if needed.

**🎉 Migration Complete!** Your MSK infrastructure is now fully migrated to Confluent Cloud with:
- ✅ Topics created with original configurations
- ✅ Schemas migrated to Schema Registry
- ✅ Consumer groups analyzed (reference data for manual recreation)
- ✅ Service accounts created
- ✅ RBAC role bindings applied
- ✅ Credentials generated and organized (if needed)

## 📁 Project Organization

### Scripts Organization

Scripts are organized by functionality:

```
scripts/
├── extract_msk_metadata/          # MSK data extraction
│   ├── extract-msk-metadata.sh   # Main extraction script
│   └── README.md                  # Documentation
├── create_cc_infra/               # Confluent Cloud infrastructure
│   ├── create-cc-topics.sh       # Topic creation
│   ├── create-cc-schemas.sh      # Schema migration
│   ├── create-cc-rbac.sh         # RBAC creation
│   └── README.md                  # Documentation
└── convert-acl-to-rbac.sh         # ACL to RBAC conversion
```

### Generated Files

All generated files are organized in the `generated_jsons/` folder:

```
generated_jsons/
├── msk_jsons/                     # MSK extracted data
│   ├── msk_acls.json             # Extracted MSK ACLs
│   ├── msk_topics.json           # Extracted MSK topics with configurations
│   ├── msk_consumer_groups.json  # Extracted MSK consumer groups
│   ├── msk_principals.json       # Unique principals extracted from ACLs
│   └── msk_schemas.json          # Extracted schemas (all versions)
├── cc_jsons/                     # Confluent Cloud ready data
│   ├── cc_rbac.json              # Auto-converted Confluent Cloud RBAC rules
│   ├── cc_service_accounts.json  # Service account details
│   └── cc_credentials/           # Generated service account credentials
│       ├── service-account-1/
│       │   ├── credentials.json   # Complete metadata
│       │   ├── api-key.txt       # API key only
│       │   ├── api-secret.txt    # API secret only
│       │   └── kafka.properties  # Ready-to-use config
│       └── ...
└── ccloud.config                # Confluent Cloud configuration
```

## 🔧 Detailed Usage

### MSK Metadata Extraction

**Extract ACLs, Topics, Principals, and Schemas + Auto-Convert to RBAC:**
```bash
./scripts/extract_msk_metadata/extract-msk-metadata.sh
```

This script:
- Reads configuration from `msk.config`
- Extracts all ACLs from MSK cluster
- Extracts all topics with configurations
- Extracts unique principals from ACLs
- Extracts all schemas from AWS Glue Schema Registry (all versions)
- **Automatically converts ACLs to Confluent Cloud RBAC format**
- Outputs to `generated_jsons/` folder

**Advanced Usage:**
```bash
# Interactive mode with flexible configuration
./extract-acls.sh --interactive

# Direct command line
./extract-acls.sh --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123
```

### RBAC Conversion

**Convert MSK ACLs to Confluent Cloud RBAC:**
```bash
./scripts/convert-acl-to-rbac.sh [OPTIONS]

Options:
  -i, --input FILE        Input MSK ACLs JSON file (default: generated_jsons/msk_jsons/msk_acls.json)
  -o, --output FILE       Output CC RBAC JSON file (default: generated_jsons/cc_jsons/cc_rbac.json)
  -e, --environment ENV   Target Confluent Cloud environment ID (required)
  -c, --cluster CLUSTER   Target Confluent Cloud cluster ID (required)
  -h, --help              Show help message

Examples:
  # Basic conversion
  ./scripts/convert-acl-to-rbac.sh -e env-12345 -c lkc-67890
  
  # Custom input/output files
  ./scripts/convert-acl-to-rbac.sh -i my_acls.json -o my_rbac.json -e env-12345 -c lkc-67890
```

### Automated RBAC Application

**Create RBAC rules in Confluent Cloud:**
```bash
./scripts/create_cc_infra/create-cc-rbac.sh [OPTIONS]

Options:
  -f, --file FILE         RBAC JSON file (default: generated_jsons/cc_jsons/cc_rbac.json)
  -c, --config FILE       Confluent Cloud config file (default: generated_jsons/ccloud.config)
  -d, --dry-run           Show commands without executing them
  -v, --verbose           Enable verbose output
  --no-service-accounts   Skip service account creation
  -h, --help              Show help message

Examples:
  # Create with default configuration
  ./scripts/create_cc_infra/create-cc-rbac.sh
  
  # Dry run to preview changes
  ./scripts/create_cc_infra/create-cc-rbac.sh --dry-run
  
  # Verbose output
  ./scripts/create_cc_infra/create-cc-rbac.sh --verbose
```

This script automatically:
- ✅ Reads Confluent Cloud configuration
- ✅ Authenticates using API keys
- ✅ Creates service accounts (if they don't exist)
- ✅ Applies all role bindings
- ✅ Verifies the applied role bindings

### Topic Creation

**Create Topics in Confluent Cloud:**
```bash
./scripts/create_cc_infra/create-cc-topics.sh [OPTIONS]

Options:
  -f, --file FILE         Topics JSON file (default: generated_jsons/msk_topics.json)
  -c, --config FILE       Confluent Cloud config file (default: ccloud.config)
  -d, --dry-run           Show what would be created without executing
  -v, --verbose           Enable verbose output
  -h, --help              Show help message

Examples:
  # Create topics with default configuration
  ./scripts/create_cc_infra/create-cc-topics.sh
  
  # Dry run to preview topic creation
  ./scripts/create_cc_infra/create-cc-topics.sh --dry-run
  
  # Verbose output
  ./scripts/create_cc_infra/create-cc-topics.sh --verbose
```

This script automatically creates topics in Confluent Cloud based on MSK topic configurations.

### Schema Migration

**Migrate Schemas from MSK to Confluent Cloud Schema Registry:**
```bash
./scripts/create_cc_infra/create-cc-schemas.sh [OPTIONS]

Options:
  -s, --schemas FILE      MSK schemas JSON file (default: generated_jsons/msk_jsons/msk_schemas.json)
  -c, --config FILE       Confluent Cloud config file (default: ccloud.config)
  --compatibility MODE   Schema compatibility mode (default: BACKWARD)
                         Options: NONE, BACKWARD, BACKWARD_TRANSITIVE, FORWARD, FORWARD_TRANSITIVE, FULL, FULL_TRANSITIVE
  --dry-run              Show what would be done without making changes
  --force                Overwrite existing schemas (use with caution)
  -v, --verbose          Enable verbose logging
  -h, --help             Show help message

Examples:
  # Basic schema migration
  ./scripts/create_cc_infra/create-cc-schemas.sh
  
  # Dry run to preview schema migration
  ./scripts/create_cc_infra/create-cc-schemas.sh --dry-run
  
  # Migrate with specific compatibility mode
  ./scripts/create_cc_infra/create-cc-schemas.sh --compatibility FULL
  
  # Force migration with verbose output
  ./scripts/create_cc_infra/create-cc-schemas.sh --force --verbose
```

**Schema Migration Process:**
1. **Extract** schemas from AWS Glue Schema Registry (MSK)
2. **Validate** schema data and format compatibility
3. **Check** existing subjects in Confluent Cloud Schema Registry
4. **Convert** AWS Glue compatibility modes to Confluent Cloud format
5. **Register** schemas with proper subject naming (`{registry_name}-{schema_name}`)
6. **Verify** successful registration and report results

**Key Features:**
- ✅ **Multi-format Support**: AVRO, JSON, and Protobuf schemas
- ✅ **Compatibility Mapping**: Automatic conversion from AWS Glue to Confluent Cloud compatibility modes
- ✅ **Subject Naming**: Intelligent naming using registry and schema names
- ✅ **Duplicate Detection**: Checks for existing subjects to prevent conflicts
- ✅ **Performance Optimized**: Fast API calls with proper timeouts
- ✅ **Comprehensive Reporting**: Detailed migration statistics and results
- ✅ **Error Recovery**: Graceful handling of API failures and network issues

**Schema Registry Configuration:**
Ensure your `ccloud.config` includes Schema Registry credentials:
```properties
# Schema Registry Configuration
schema.registry.url=https://psrc-xxxxx.region.gcp.confluent.cloud
schema.registry.basic.auth.user.info=SR_API_KEY:SR_API_SECRET
```

This script automatically migrates schemas from AWS Glue Schema Registry to Confluent Cloud Schema Registry with proper compatibility settings.

### Service Account Credentials Creation

**Create Service Account Credentials in Confluent Cloud:**
```bash
./scripts/create_cc_infra/create-cc-sa-creds.sh [OPTIONS]

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
  # Create credentials for all service accounts
  ./scripts/create_cc_infra/create-cc-sa-creds.sh
  
  # Dry run to preview credential creation
  ./scripts/create_cc_infra/create-cc-sa-creds.sh --dry-run
  
  # Force recreation with verbose output
  ./scripts/create_cc_infra/create-cc-sa-creds.sh --force --verbose
```

**Credential Creation Process:**
1. **Read** service accounts from RBAC conversion file
2. **Check** for existing service accounts in Confluent Cloud
3. **Create** service accounts if they don't exist
4. **Generate** Kafka API keys for each service account
5. **Organize** credentials in structured folder hierarchy
6. **Create** multiple file formats for easy integration

**Key Features:**
- ✅ **Structured Organization**: Each service account gets its own folder
- ✅ **Multiple Formats**: JSON metadata, text files, and Kafka properties
- ✅ **Security First**: Auto-generates .gitignore to prevent credential leaks
- ✅ **Comprehensive Reporting**: Summary of all created credentials
- ✅ **Existing Account Handling**: Detects and handles existing service accounts
- ✅ **Ready-to-Use**: Generates kafka.properties files for immediate use
- ✅ **Safe Testing**: Dry-run mode for preview before execution

**Output Structure:**
```
generated_jsons/cc_jsons/cc_credentials/
├── service-account-1/
│   ├── credentials.json          # Complete metadata and credentials
│   ├── api-key.txt              # API key only (for scripts)
│   ├── api-secret.txt           # API secret only (for scripts)
│   └── kafka.properties         # Ready-to-use Kafka client config
├── service-account-2/
│   └── ... (same structure)
├── credentials-summary.json     # Summary report of all credentials
└── .gitignore                   # Prevents accidental git commits
```

This script automatically creates and organizes all necessary credentials for your migrated service accounts.

## 🔑 API Keys and Permissions

**⚠️ IMPORTANT**: This utility requires **two different types of API keys** with specific permissions. See [API_KEYS_AND_PERMISSIONS.md](API_KEYS_AND_PERMISSIONS.md) for detailed requirements.

### Quick Summary

| Operation | API Key Type | Required Role | Configuration Property |
|-----------|-------------|---------------|----------------------|
| **RBAC Operations** | Cloud API Keys | OrganizationAdmin or EnvironmentAdmin | `confluent_cloud_key` / `confluent_cloud_secret` |
| **Topic Operations** | Kafka API Keys | DeveloperRead or Operator | `sasl.username` / `sasl.password` |

### Common Issues

- **400 Bad Request for Role Bindings**: Cloud API keys lack OrganizationAdmin/EnvironmentAdmin permissions for **creation** operations (basic keys can list but not create)
- **401 Unauthorized**: Wrong API key type being used for the operation  
- **Cannot List Topics**: Kafka API keys lack read permissions
- **Role Binding Listing Fails**: Missing organization ID in configuration (required for CRN pattern)

📖 **For complete setup instructions, troubleshooting, and examples, see [API_KEYS_AND_PERMISSIONS.md](API_KEYS_AND_PERMISSIONS.md)**

## 🔐 Authentication Methods

### MSK Authentication

| Authentication Method | Security Protocol | Port | Bootstrap Server Type |
|-----------------------|-------------------|------|----------------------|
| **SSL** (Default)     | `SSL`            | 9094 | TLS                  |
| **IAM** (Recommended) | `SASL_SSL`       | 9098 | SASL_IAM            |
| **SCRAM**             | `SASL_SSL`       | 9096 | SASL_SCRAM          |
| **Plaintext**         | `PLAINTEXT`      | 9092 | Plaintext           |

#### SSL (Default)
```bash
./extract-acls.sh --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123
```

#### IAM Authentication (Recommended for MSK)
```bash
./extract-acls.sh \
  --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123 \
  --security-protocol SASL_SSL \
  --sasl-mechanism AWS_MSK_IAM
```

#### SCRAM Authentication
```bash
./extract-acls.sh \
  --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123 \
  --security-protocol SASL_SSL \
  --sasl-mechanism SCRAM-SHA-256 \
  --sasl-username myuser \
  --sasl-password mypassword
```

### Confluent Cloud Authentication

The utility supports API Key authentication for Confluent Cloud:

```properties
# In generated_jsons/ccloud.config
confluent.cloud.environment=env-12345
confluent.cloud.cluster=lkc-67890
confluent.cloud.region=us-east-1

# Authentication
sasl.username=YOUR_API_KEY
sasl.password=YOUR_API_SECRET
bootstrap.servers=pkc-xxxxx.region.aws.confluent.cloud:9092
security.protocol=SASL_SSL
sasl.mechanisms=PLAIN

# Optional: Schema Registry
# schema.registry.url=https://psrc-xxxxx.region.aws.confluent.cloud
# schema.registry.basic.auth.user.info=SR_API_KEY:SR_API_SECRET
```

## 📝 Configuration Files

### MSK Configuration (msk.config)

**Sample for IAM Authentication:**
```properties
# MSK cluster configuration
cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-msk-cluster/abc-123-def-456
aws.region=us-east-1

# IAM Authentication (Recommended)
security.protocol=SASL_SSL
sasl.mechanism=AWS_MSK_IAM
sasl.jaas.config=software.amazon.msk.auth.iam.IAMLoginModule required;
sasl.client.callback.handler.class=software.amazon.msk.auth.iam.IAMClientCallbackHandler

# Client settings
client.id=msk-acl-extractor
request.timeout.ms=30000
admin.request.timeout.ms=60000
```

**Sample for SSL Authentication:**
```properties
# MSK cluster configuration
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

### Confluent Cloud Configuration (generated_jsons/ccloud.config)

```properties
# Confluent Cloud Configuration
confluent.cloud.environment=env-12345
confluent.cloud.cluster=lkc-67890
confluent.cloud.region=us-east-1

# Kafka API Keys (for topic operations)
sasl.username=YOUR_KAFKA_API_KEY
sasl.password=YOUR_KAFKA_API_SECRET
bootstrap.servers=pkc-xxxxx.region.aws.confluent.cloud:9092
security.protocol=SASL_SSL
sasl.mechanisms=PLAIN

# Cloud API Keys (for RBAC operations)
confluent_cloud_key=YOUR_CLOUD_API_KEY
confluent_cloud_secret=YOUR_CLOUD_API_SECRET

# Schema Registry (for schema migration)
schema.registry.url=https://psrc-xxxxx.region.gcp.confluent.cloud
schema.registry.basic.auth.user.info=SR_API_KEY:SR_API_SECRET
```

**📖 See [API_KEYS_AND_PERMISSIONS.md](API_KEYS_AND_PERMISSIONS.md) for detailed information about:**
- How to create the required API keys
- What permissions each type of API key needs
- Troubleshooting permission issues
- Best practices for API key management

## 📊 Output Formats

### MSK ACLs Output (generated_jsons/msk_jsons/msk_acls.json)
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
  "acl_count": 1,
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

### MSK Topics Output (generated_jsons/msk_topics.json)
```json
{
  "topics": [
    {
      "name": "my-topic",
      "partitions": 3,
      "replication_factor": 2,
      "configs": {
        "retention.ms": "86400000",
        "compression.type": "producer"
      }
    }
  ],
  "topic_count": 1,
  "exported_at": "2024-01-01T12:00:00"
}
```

### MSK Schemas Output (generated_jsons/msk_schemas.json)
```json
{
  "schemas": [
    {
      "schema_id": "arn:aws:glue:us-east-1:123456789012:schema/registry1/schema1",
      "schema_name": "UserEvent",
      "registry_name": "registry1",
      "data_format": "AVRO",
      "compatibility": "BACKWARD",
      "version_number": 1,
      "version_id": "abc-123-def-456",
      "schema_definition": "{\"type\":\"record\",\"name\":\"UserEvent\"...}",
      "status": "AVAILABLE",
      "created_time": "2024-01-01T12:00:00",
      "updated_time": "2024-01-01T12:00:00"
    }
  ],
  "schema_count": 5,
  "exported_at": "2024-01-01T12:00:00"
}
```

### Confluent Cloud RBAC Output (generated_jsons/cc_jsons/cc_rbac.json)
```json
{
  "role_bindings": [
    {
      "principal": "alice",
      "role": "DeveloperRead",
      "resource_type": "Topic",
      "resource_name": "my-topic",
      "pattern_type": "LITERAL",
      "environment": "env-12345",
      "cluster_id": "lkc-67890"
    }
  ],
  "service_accounts": [
    {
      "name": "alice-service-account",
      "description": "Service account for alice (converted from MSK ACL)",
      "original_principal": "User:alice"
    }
  ],
  "conversion_metadata": {
    "source_cluster": "my-cluster",
    "source_region": "us-east-1",
    "original_acl_count": 1,
    "converted_role_bindings_count": 1,
    "converted_at": "2024-01-01T12:00:00",
    "conversion_notes": [
      "Conversion from MSK ACLs to Confluent Cloud RBAC completed",
      "DENY permissions were skipped as Confluent Cloud uses ALLOW-based RBAC",
      "Service accounts need to be created in Confluent Cloud before applying role bindings"
    ]
  }
}
```

## 🔧 Advanced Features

### Schema Registry Support

The utility automatically extracts **all versions** of schemas from AWS Glue Schema Registry:

- ✅ Discovers all registries
- ✅ Extracts all schemas from each registry  
- ✅ Gets all versions of each schema (not just latest)
- ✅ Includes schema definitions, compatibility settings, and metadata
- ✅ Graceful error handling for missing or inaccessible registries

### RBAC Conversion Details

The conversion process includes intelligent mapping:

| MSK Operation | Confluent Cloud Role | Resource Type |
|---------------|---------------------|---------------|
| READ          | DeveloperRead       | Topic, Group  |
| WRITE         | DeveloperWrite      | Topic         |
| CREATE        | DeveloperManage     | Topic         |
| DELETE        | ResourceOwner       | Topic         |
| ALTER         | ResourceOwner       | Topic         |
| DESCRIBE      | DeveloperRead       | Topic         |
| ALTER_CONFIGS | ResourceOwner       | Topic         |
| All Cluster   | ClusterAdmin        | Cluster       |

**Key Conversion Features:**
- ✅ Automatically skips DENY permissions (Confluent Cloud uses ALLOW-based RBAC)
- ✅ Creates service account metadata for each unique principal
- ✅ Sanitizes service account names for Confluent Cloud compatibility
- ✅ Includes detailed conversion notes and metadata
- ✅ Tracks conversion statistics

### Service Account Management

The automated RBAC application includes:

- ✅ **Auto-discovery**: Extracts unique principals from RBAC data
- ✅ **Duplicate Prevention**: Checks for existing service accounts
- ✅ **Name Sanitization**: Ensures CC-compatible naming
- ✅ **Batch Creation**: Creates all required service accounts
- ✅ **Error Handling**: Graceful handling of creation failures

## 🛠 Building and Running

### Available Scripts

- `build.sh` - Build the Java application using Maven
- `scripts/extract_msk_metadata/extract-msk-metadata.sh` - Extract MSK metadata (ACLs, topics, schemas, principals)
- `scripts/convert-acl-to-rbac.sh` - Convert ACLs to Confluent Cloud RBAC format
- `scripts/apply-rbac-to-cc-auto.sh` - Automatically apply RBAC to Confluent Cloud
- `scripts/create_cc_infra/create-cc-rbac.sh` - RBAC creation in Confluent Cloud

### Manual Build
```bash
mvn clean package
java -jar release/msk-acl-extractor.jar --help
java -jar release/acl-to-rbac-converter.jar --help
```

## 🔍 Troubleshooting

### Common Issues

1. **JAVA_HOME Not Set Error**
   ```
   The JAVA_HOME environment variable is not defined correctly
   ```
   **Solution:** Build scripts automatically detect and set JAVA_HOME
   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto
   echo $JAVA_HOME && java -version
   ```

2. **SSL Keystore Loading Error**
   ```
   KafkaException: Failed to load SSL keystore of type JKS
   ```
   **Solution:** Application automatically handles invalid truststore paths

3. **Wrong Bootstrap Server Port Error**
   ```
   Unexpected handshake request with client mechanism AWS_MSK_IAM
   ```
   **Solution:** Application automatically selects correct ports based on auth method

4. **AWS Credentials Error**
   ```bash
   aws configure
   aws sts get-caller-identity
   ```

5. **Confluent CLI Authentication Error**
   ```bash
   confluent login
   # Or use automated script with API keys
   ./scripts/apply-rbac-to-cc-auto.sh
   ```

6. **Schema Registry Access Denied**
   - Ensure AWS credentials have Glue Schema Registry permissions
   - Check if registries exist in the specified region
   - Application gracefully handles missing registries

### Required AWS Permissions

**For MSK:**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "kafka:DescribeCluster",
        "kafka:GetBootstrapBrokers",
        "kafka-cluster:Connect",
        "kafka-cluster:DescribeCluster"
      ],
      "Resource": "arn:aws:kafka:*:*:cluster/*"
    }
  ]
}
```

**For Schema Registry:**
```json
{
  "Effect": "Allow",
  "Action": [
    "glue:ListRegistries",
    "glue:ListSchemas",
    "glue:GetSchema",
    "glue:ListSchemaVersions",
    "glue:GetSchemaVersion"
  ],
  "Resource": "*"
}
```

### Logs

Check detailed logs in:
- `logs/msk-acl-extractor.log`

## 🚀 Migration Best Practices

1. **Test First**: Always use `--dry-run` to preview changes
2. **Incremental Migration**: Start with a subset of ACLs/topics
3. **Verify Permissions**: Test access after applying role bindings
4. **Monitor Logs**: Check both MSK and Confluent Cloud logs
5. **Backup ACLs**: Keep a copy of original MSK ACLs
6. **Service Account Keys**: Generate and store API keys for new service accounts

## 📄 License

This project is licensed under the Apache License 2.0.
