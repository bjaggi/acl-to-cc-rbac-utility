# API Keys and Permissions Required for MSK to Confluent Cloud RBAC Utility

This document explains the different types of API keys and permissions required for the MSK to Confluent Cloud RBAC utility to function properly.

## Overview

The utility requires **two different types of API keys** for different operations:

1. **Cloud API Keys** - For RBAC operations (creating service accounts and role bindings)
2. **Kafka API Keys** - For topic operations (checking if topics exist)

## Required API Keys

### 1. Cloud API Keys (for RBAC Operations)

**Purpose**: Used for Identity and Access Management (IAM) operations including:
- Creating service accounts
- Creating role bindings
- Managing RBAC permissions

**Configuration Properties**:
```properties
# Cloud API Keys for RBAC operations
confluent_cloud_key=YOUR_CLOUD_API_KEY
confluent_cloud_secret=YOUR_CLOUD_API_SECRET
```

**Alternative Property Names** (also supported):
```properties
cloud.api.key=YOUR_CLOUD_API_KEY
cloud.api.secret=YOUR_CLOUD_API_SECRET
```

**Required Permissions**: The Cloud API keys need different permission levels depending on the operation:

#### Read Operations (Listing)
- **Basic Cloud API keys** can perform:
  - ✅ List service accounts
  - ✅ List environments  
  - ✅ List clusters
  - ✅ List role bindings (requires organization ID in CRN pattern)

#### Write Operations (Creation)
- **OrganizationAdmin** or **EnvironmentAdmin** roles required for:
  - ❌ Create service accounts
  - ❌ Create role bindings
  - ❌ Modify RBAC permissions

**⚠️ Critical Discovery**: Role binding operations require the **organization ID** in CRN pattern format:
```
crn://confluent.cloud/organization=<ORG_ID>/*
```

**Recommended Roles**:
- ✅ **OrganizationAdmin** (recommended for full access)
- ✅ **EnvironmentAdmin** (for specific environment)
- ✅ **AccountAdmin** + **CloudClusterAdmin** (combined permissions)

**How to Create Cloud API Keys**:
1. Go to Confluent Cloud Console
2. Navigate to "Administration" → "Access" → "Service Accounts"
3. Create a new service account or select existing one
4. Grant the service account appropriate RBAC roles (OrganizationAdmin/EnvironmentAdmin)
5. Generate API keys for the service account

**How to Find Organization ID**:
1. Go to Confluent Cloud Console
2. Navigate to "Administration" → "Billing & payment"
3. The organization ID is displayed in the URL or page details
4. Or use the API: `curl -u "YOUR_CLOUD_KEY:YOUR_CLOUD_SECRET" "https://api.confluent.cloud/org/v2/organizations"`

### 2. Kafka API Keys (for Topic Operations)

**Purpose**: Used for Kafka cluster operations including:
- Listing existing topics
- Verifying topic existence
- Accessing Kafka metadata

**Configuration Properties**:
```properties
# Kafka API Keys for topic operations
sasl.username=YOUR_KAFKA_API_KEY
sasl.password=YOUR_KAFKA_API_SECRET
```

**Required Permissions**: The Kafka API keys must have **read access** to the Kafka cluster and topics:

- ✅ **DeveloperRead** role on the cluster or topics
- ✅ **Operator** role on the cluster
- ✅ **CloudClusterAdmin** role on the cluster

**How to Create Kafka API Keys**:
1. Go to Confluent Cloud Console
2. Navigate to your Kafka cluster
3. Go to "API Keys" tab
4. Create new API key with appropriate scope (cluster-scoped recommended)
5. Ensure the key has read permissions on topics

## Complete Configuration Example

Here's a complete `ccloud.config` file with both types of API keys:

```properties
# Confluent Cloud Configuration
confluent.cloud.environment=env-xxxxx
confluent.cloud.cluster=lkc-xxxxx
confluent.cloud.organization=YOUR_ORG_ID

# Kafka API Keys (for topic operations)
sasl.username=YOUR_KAFKA_API_KEY
sasl.password=YOUR_KAFKA_API_SECRET
bootstrap.servers=YOUR_BOOTSTRAP_SERVERS
sasl.mechanism=PLAIN
security.protocol=SASL_SSL

# Cloud API Keys (for RBAC operations)
confluent_cloud_key=YOUR_CLOUD_API_KEY
confluent_cloud_secret=YOUR_CLOUD_API_SECRET

# REST API endpoint
rest.url=https://YOUR_CLUSTER_REST_ENDPOINT
```

## Permission Requirements by Operation

| Operation | API Key Type | Required Role | Scope |
|-----------|-------------|---------------|-------|
| Create Service Accounts | Cloud API | OrganizationAdmin or AccountAdmin | Organization |
| Create Role Bindings | Cloud API | OrganizationAdmin or EnvironmentAdmin | Organization/Environment |
| List/Check Topics | Kafka API | DeveloperRead or Operator | Cluster/Topic |
| Verify Topic Existence | Kafka API | DeveloperRead or Operator | Cluster/Topic |

## Common Issues and Solutions

### Issue 1: 401 Unauthorized Errors
**Cause**: Wrong API key type being used for the operation
**Solution**: Ensure Cloud API keys are used for RBAC operations and Kafka API keys for topic operations

### Issue 2: 400 Bad Request for Role Bindings
**Cause**: Cloud API keys lack sufficient permissions for **creation** operations
**Solutions**:
- Grant **OrganizationAdmin** role to the service account associated with Cloud API keys
- Or grant **EnvironmentAdmin** role for the specific environment
- Verify the service account has RBAC management permissions
- **Note**: Basic Cloud API keys can **list** role bindings but cannot **create** them

**Important**: Role binding creation requires admin-level permissions, while listing only requires basic read access.

### Issue 3: Cannot List Topics
**Cause**: Kafka API keys lack read permissions
**Solution**: Grant DeveloperRead or Operator role to the Kafka API key's associated principal

### Issue 4: Service Account Creation Fails
**Cause**: Cloud API keys don't have AccountAdmin or OrganizationAdmin permissions
**Solution**: Grant AccountAdmin role to create service accounts

## Verification Steps

### 1. Test Cloud API Keys
```bash
# Test if Cloud API keys can list service accounts
curl -u "YOUR_CLOUD_KEY:YOUR_CLOUD_SECRET" \
  "https://api.confluent.cloud/iam/v2/service-accounts"

# Test if Cloud API keys can list role bindings (requires organization ID)
curl -u "YOUR_CLOUD_KEY:YOUR_CLOUD_SECRET" \
  "https://api.confluent.cloud/iam/v2/role-bindings?crn_pattern=crn://confluent.cloud/organization=YOUR_ORG_ID/*"
```

### 2. Test Kafka API Keys
```bash
# Test if Kafka API keys can access cluster (using the utility)
java -cp target/msk-to-confluent-cloud.jar \
  com.confluent.utility.ConfluentCloudTopicCreator --dry-run --verbose
```

### 3. Test RBAC Permissions
```bash
# Test if Cloud API keys can create role bindings
java -cp target/msk-to-confluent-cloud.jar \
  com.confluent.utility.ConfluentCloudRBACApplicator --dry-run --verbose
```

## Best Practices

1. **Use Service Accounts**: Create dedicated service accounts for the utility rather than using personal user credentials
2. **Principle of Least Privilege**: Grant only the minimum required permissions
3. **Separate Keys**: Use different API keys for different purposes (Cloud vs Kafka operations)
4. **Secure Storage**: Store API keys securely and rotate them regularly
5. **Environment-Specific**: Use EnvironmentAdmin instead of OrganizationAdmin when possible for better security

## Troubleshooting

If you encounter permission errors:

1. **Verify API Key Types**: Ensure you're using the right type of API key for each operation
2. **Check Role Assignments**: Verify the service accounts have the required RBAC roles
3. **Test Individually**: Test Cloud and Kafka API keys separately
4. **Check Cluster Type**: Ensure your cluster supports RBAC (Standard, Enterprise, Dedicated, or Freight)
5. **Review Logs**: Check the utility logs for specific error messages

## Support

For additional help:
- Check Confluent Cloud Console for role assignments
- Review Confluent Cloud documentation on RBAC
- Contact your Confluent Cloud administrator for permission issues 