# MSK Metadata Extraction Scripts

This folder contains scripts for extracting metadata from Amazon MSK clusters.

## Scripts

### `extract-msk-metadata.sh`
Comprehensive script that extracts all metadata from an MSK cluster and automatically converts ACLs to RBAC:

- **ACLs** → `generated_jsons/msk_acls.json`
- **Topics** → `generated_jsons/msk_topics.json` 
- **Principals** → `generated_jsons/msk_principals.json`
- **Schemas** → `generated_jsons/msk_schemas.json`
- **Cluster metadata**
- **Auto-converts ACLs to RBAC** → `generated_jsons/cc_jsons/cc_rbac.json`

**Usage:**
```bash
# Extract using default msk.config
./scripts/extract_msk_metadata/extract-msk-metadata.sh

# Extract with verbose output
./scripts/extract_msk_metadata/extract-msk-metadata.sh --verbose
```

**Prerequisites:**
- Java 11 or higher
- AWS credentials configured
- `msk.config` file with cluster details
- Network access to MSK cluster

**Configuration:**
The script reads from `msk.config` in the project root:
```properties
cluster.arn=arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123
region=us-east-1
security.protocol=SASL_SSL
sasl.mechanism=AWS_MSK_IAM
```

## Output Files

All extracted data is saved to the `generated_jsons/` directory:

- `msk_acls.json` - All ACLs with cluster metadata
- `msk_topics.json` - All topics with configurations
- `msk_principals.json` - Unique principals extracted from ACLs
- `msk_schemas.json` - Schemas from Glue Schema Registry
- `cc_rbac.json` - Auto-converted Confluent Cloud RBAC role bindings

## Next Steps

After extraction and auto-conversion, proceed with infrastructure creation:

1. **Create topics in CC:** `./scripts/create_cc_infra/create-cc-topics.sh`
2. **Create RBAC in CC:** `./scripts/create_cc_infra/create-cc-rbac.sh`

**Note:** ACL to RBAC conversion is now automatic! If conversion fails, you can run it manually:
```bash
./scripts/convert-acl-to-rbac.sh -e env-12345 -c lkc-67890
``` 