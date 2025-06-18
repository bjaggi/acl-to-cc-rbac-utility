# Confluent Cloud Infrastructure Creation Scripts

This folder contains scripts for creating and configuring infrastructure in Confluent Cloud.

## Scripts

### `create-cc-topics.sh`
Creates topics in Confluent Cloud based on MSK topic configurations.

**Usage:**
```bash
# Create topics using default files
./scripts/create_cc_infra/create-cc-topics.sh

# Dry run to see what would be created
./scripts/create_cc_infra/create-cc-topics.sh --dry-run

# Create with verbose output
./scripts/create_cc_infra/create-cc-topics.sh --verbose
```

**Features:**
- Reads MSK topics from `generated_jsons/msk_topics.json`
- Converts MSK configurations to Confluent Cloud format
- Skips internal topics automatically
- Handles existing topic detection
- Supports dry-run mode

### `create-cc-schemas.sh`
Migrates schemas from MSK (AWS Glue Schema Registry) to Confluent Cloud Schema Registry.

**Usage:**
```bash
# Migrate schemas using default files
./scripts/create_cc_infra/create-cc-schemas.sh

# Dry run to see what would be migrated
./scripts/create_cc_infra/create-cc-schemas.sh --dry-run

# Migrate with specific compatibility mode
./scripts/create_cc_infra/create-cc-schemas.sh --compatibility FULL

# Force migration with verbose output
./scripts/create_cc_infra/create-cc-schemas.sh --force --verbose
```

**Features:**
- Reads MSK schemas from `generated_jsons/msk_jsons/msk_schemas.json`
- Supports AVRO, JSON, and Protobuf schema formats
- Converts AWS Glue compatibility modes to Confluent Cloud format
- Uses intelligent subject naming: `{registry_name}-{schema_name}`
- Checks for existing subjects to prevent conflicts
- Performance optimized with proper API timeouts
- Comprehensive migration reporting and statistics
- Supports dry-run mode for safe testing

### `create-cc-service-accounts.sh`
Creates service accounts in Confluent Cloud based on principals extracted from MSK ACLs.

**Usage:**
```bash
# Create service accounts using default files
./scripts/create_cc_infra/create-cc-service-accounts.sh

# Dry run to see what would be created
./scripts/create_cc_infra/create-cc-service-accounts.sh --dry-run

# Create with verbose output
./scripts/create_cc_infra/create-cc-service-accounts.sh --verbose
```

**Features:**
- Reads principals from `generated_jsons/msk_jsons/msk_principals.json`
- Filters out system/internal principals
- Creates service accounts using Confluent Cloud API
- Skips existing service accounts
- Supports dry-run mode

### `create-cc-rbac.sh`
Creates RBAC role bindings in Confluent Cloud using the Java API.

**Usage:**
```bash
# Create RBAC using default files
./scripts/create_cc_infra/create-cc-rbac.sh

# Dry run to see what would be created
./scripts/create_cc_infra/create-cc-rbac.sh --dry-run

# Create with verbose output and skip service account creation
./scripts/create_cc_infra/create-cc-rbac.sh --verbose --skip-service-accounts
```

**Features:**
- Reads RBAC bindings from `generated_jsons/cc_jsons/cc_rbac.json`
- Creates service accounts if they don't exist
- Creates role bindings using Confluent Cloud Java API
- Verifies created role bindings
- Supports dry-run mode

### `create-cc-sa-creds.sh`
Creates service accounts in Confluent Cloud and generates API keys with organized credential storage.

**Usage:**
```bash
# Create credentials for all service accounts in RBAC file
./scripts/create_cc_infra/create-cc-sa-creds.sh

# Dry run to see what would be created
./scripts/create_cc_infra/create-cc-sa-creds.sh --dry-run

# Force recreation of all credentials
./scripts/create_cc_infra/create-cc-sa-creds.sh --force --verbose

# Use custom RBAC file and output directory
./scripts/create_cc_infra/create-cc-sa-creds.sh --rbac my_rbac.json --output /custom/path
```

**Features:**
- Reads service accounts from `generated_jsons/cc_jsons/cc_rbac.json`
- Creates service accounts if they don't exist
- Generates Kafka API keys for each service account
- Organizes credentials in structured folder hierarchy
- Creates multiple file formats (JSON, text files, Kafka properties)
- Generates comprehensive summary report
- Handles existing service accounts gracefully
- Supports dry-run mode for safe testing

**Output Structure:**
```
generated_jsons/cc_jsons/cc_credentials/
├── service-account-1/
│   ├── credentials.json          # Complete metadata
│   ├── api-key.txt              # Just the API key
│   ├── api-secret.txt           # Just the API secret
│   └── kafka.properties         # Ready-to-use Kafka config
├── service-account-2/
│   ├── credentials.json
│   ├── api-key.txt
│   ├── api-secret.txt
│   └── kafka.properties
├── credentials-summary.json     # Summary of all created credentials
└── .gitignore                   # Prevents accidental git commits
```

## Prerequisites

Both scripts require:
- Java 11 or higher
- `ccloud.config` file with Confluent Cloud credentials
- Access to target Confluent Cloud environment and cluster
- For schema migration: Schema Registry API credentials

## Configuration

### `ccloud.config` (Project Root)
```properties
bootstrap.servers=pkc-xxxxx.region.aws.confluent.cloud:9092
sasl.username=YOUR_API_KEY
sasl.password=YOUR_API_SECRET
confluent.cloud.environment=env-xxxxx
confluent.cloud.cluster=lkc-xxxxx
cloud.rest.url=https://pkc-xxxxx.region.aws.confluent.cloud:443

# Schema Registry (for schema migration)
schema.registry.url=https://psrc-xxxxx.region.gcp.confluent.cloud
schema.registry.basic.auth.user.info=SR_API_KEY:SR_API_SECRET
```

## Input Files

These scripts read from files generated by the extraction and conversion process:

- `generated_jsons/msk_topics.json` - Topics extracted from MSK
- `generated_jsons/msk_jsons/msk_schemas.json` - Schemas extracted from AWS Glue Schema Registry
- `generated_jsons/cc_jsons/cc_rbac.json` - RBAC bindings converted from MSK ACLs

## Workflow

**Recommended Execution Sequence:**

1. **Extract MSK data & auto-convert to RBAC:** `./scripts/extract_msk_metadata/extract-msk-metadata.sh`
2. **Create topics:** `./scripts/create_cc_infra/create-cc-topics.sh`
3. **Migrate schemas:** `./scripts/create_cc_infra/create-cc-schemas.sh`
4. **Create consumer groups (OPTIONAL):** Consumer groups are extracted for reference but not automatically migrated
5. **Create service accounts:** `./scripts/create_cc_infra/create-cc-service-accounts.sh`
6. **Create RBAC:** `./scripts/create_cc_infra/create-cc-rbac.sh`
7. **Generate credentials (OPTIONAL):** `./scripts/create_cc_infra/create-cc-sa-creds.sh`

**Important Notes:**
- Consumer groups from MSK are extracted to `msk_consumer_groups.json` for reference
- RBAC must be created **after** service accounts exist
- Credential generation is optional but recommended for testing

**Note:** ACL to RBAC conversion is now automatic during extraction!

## Unified JAR Usage

Both scripts use the unified JAR file:
```bash
# Create topics
java -jar target/msk-to-confluent-cloud.jar create-topics

# Create RBAC
java -jar target/msk-to-confluent-cloud.jar apply
``` 