package com.confluent.migration.confluent.creator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateAclsOptions;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * ACL Creator for migrating ACLs from MSK to Confluent Cloud
 * Converts IAM roles/users to Confluent Cloud service account IDs
 */
public class ACLCreator {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Configuration
    private String aclsFile;
    private String configFile;
    private String serviceAccountsFile;
    private String outputFile;
    private boolean dryRun = false;
    private boolean verbose = false;
    private boolean force = false;
    private boolean skipExisting = true;
    
    // Runtime data
    private Properties kafkaConfig;
    private AdminClient adminClient;
    private Map<String, String> principalToServiceAccountMapping;
    
    // Counters
    private int totalAcls = 0;
    private int migratedAcls = 0;
    private int skippedAcls = 0;
    private int failedAcls = 0;
    
    // Migration results
    private List<ObjectNode> migrationResults = new ArrayList<>();
    
    public static void main(String[] args) {
        ACLCreator creator = new ACLCreator();
        
        try {
            creator.parseArguments(args);
            creator.loadConfiguration();
            creator.loadServiceAccountMappings();
            creator.migrateAcls();
            creator.generateReport();
            creator.printSummary();
            
        } catch (Exception e) {
            System.err.println("ACL migration failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            creator.cleanup();
        }
    }
    
    private void parseArguments(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--acls-file":
                    aclsFile = args[++i];
                    break;
                case "--config-file":
                    configFile = args[++i];
                    break;
                case "--service-accounts-file":
                    serviceAccountsFile = args[++i];
                    break;
                case "--output-file":
                    outputFile = args[++i];
                    break;
                case "--dry-run":
                    dryRun = true;
                    break;
                case "--verbose":
                    verbose = true;
                    break;
                case "--force":
                    force = true;
                    skipExisting = false;
                    break;
                case "--skip-existing":
                    skipExisting = true;
                    break;
                default:
                    System.out.println("Unknown argument: " + args[i]);
            }
        }
        
        // Set defaults if not provided
        if (aclsFile == null) aclsFile = "generated_jsons/msk_jsons/msk_acls.json";
        if (configFile == null) configFile = "ccloud.config";
        if (serviceAccountsFile == null) serviceAccountsFile = "generated_jsons/cc_jsons/cc_service_accounts.json";
        if (outputFile == null) outputFile = "generated_jsons/cc_jsons/cc_acls_migrated.json";
        
        System.out.println("🔐 MSK to Confluent Cloud ACL Migration (Java)");
        System.out.println("Configuration:");
        System.out.println("  ACLs file: " + aclsFile);
        System.out.println("  Config file: " + configFile);
        System.out.println("  Service accounts file: " + serviceAccountsFile);
        System.out.println("  Output file: " + outputFile);
        System.out.println("  Dry run: " + dryRun);
        System.out.println("  Verbose: " + verbose);
        System.out.println("  Force: " + force);
        System.out.println("  Skip existing: " + skipExisting);
    }
    
    private void loadConfiguration() throws IOException {
        System.out.println("Loading Kafka configuration from: " + configFile);
        
        kafkaConfig = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            kafkaConfig.load(fis);
        }
        
        // Create AdminClient
        adminClient = AdminClient.create(kafkaConfig);
        
        System.out.println("✅ Kafka configuration loaded successfully");
        if (verbose) {
            System.out.println("Bootstrap servers: " + kafkaConfig.getProperty("bootstrap.servers"));
        }
    }
    
    private void loadServiceAccountMappings() throws IOException {
        System.out.println("🔄 Loading service account mappings from: " + serviceAccountsFile);
        
        principalToServiceAccountMapping = new HashMap<>();
        
        JsonNode serviceAccountsJson = objectMapper.readTree(new File(serviceAccountsFile));
        JsonNode serviceAccounts = serviceAccountsJson.get("service_accounts");
        
        if (serviceAccounts != null && serviceAccounts.isArray()) {
            for (JsonNode sa : serviceAccounts) {
                String name = sa.get("name").asText();
                String id = sa.get("id").asText();
                JsonNode originalPrincipal = sa.get("original_principal");
                
                if (originalPrincipal != null && !originalPrincipal.isNull()) {
                    // Map original principal to service account ID
                    String principal = originalPrincipal.asText();
                    principalToServiceAccountMapping.put(principal, id);
                    if (verbose) {
                        System.out.println("Mapped: " + principal + " → " + id);
                    }
                } else {
                    // If no original principal, use service account name
                    String principal = "User:" + name;
                    principalToServiceAccountMapping.put(principal, id);
                    if (verbose) {
                        System.out.println("Mapped: " + principal + " → " + id);
                    }
                }
            }
        }
        
        System.out.println("✅ Loaded " + principalToServiceAccountMapping.size() + " principal mappings");
    }
    
    private void migrateAcls() throws IOException {
        System.out.println("🔐 Starting ACL migration...");
        
        JsonNode aclsJson = objectMapper.readTree(new File(aclsFile));
        JsonNode acls = aclsJson.get("acls");
        
        if (acls == null || !acls.isArray()) {
            throw new IllegalArgumentException("Invalid ACLs file format");
        }
        
        for (JsonNode acl : acls) {
            totalAcls++;
            processAcl(acl);
        }
        
        System.out.println("✅ ACL migration completed");
    }
    
    private void processAcl(JsonNode acl) {
        String principal = acl.get("principal").asText();
        String operation = acl.get("operation").asText();
        String resourceType = acl.get("resource_type").asText();
        String resourceName = acl.get("resource_name").asText();
        String patternType = acl.get("pattern_type").asText();
        String permissionType = acl.get("permission_type").asText();
        String host = acl.get("host").asText();
        
        System.out.println("[" + totalAcls + "] Processing ACL: " + principal + " -> " + operation + " on " + resourceType + ":" + resourceName);
        
        // Skip DENY permissions
        if (!"ALLOW".equals(permissionType)) {
            System.out.println("  ⚠️ Skipping " + permissionType + " permission (only ALLOW supported)");
            skippedAcls++;
            recordResult(principal, null, operation, resourceType, resourceName, "SKIPPED_DENY");
            return;
        }
        
        // Get service account ID for principal
        String serviceAccountId = getServiceAccountId(principal);
        if (serviceAccountId == null) {
            System.out.println("  ❌ No service account mapping found for principal: " + principal);
            failedAcls++;
            recordResult(principal, null, operation, resourceType, resourceName, "NO_MAPPING");
            return;
        }
        
        String ccPrincipal = "User:" + serviceAccountId;
        if (verbose) {
            System.out.println("  Mapped principal: " + principal + " → " + ccPrincipal);
        }
        
        // Create the ACL
        if (createAcl(ccPrincipal, operation, resourceType, resourceName, patternType, host)) {
            migratedAcls++;
            recordResult(principal, ccPrincipal, operation, resourceType, resourceName, "SUCCESS");
        } else {
            failedAcls++;
            recordResult(principal, ccPrincipal, operation, resourceType, resourceName, "FAILED");
        }
    }
    
    private String getServiceAccountId(String principal) {
        // Check direct mapping
        if (principalToServiceAccountMapping.containsKey(principal)) {
            return principalToServiceAccountMapping.get(principal);
        }
        
        // Try without "User:" prefix
        String cleanPrincipal = principal.replaceFirst("^User:", "");
        String userPrincipal = "User:" + cleanPrincipal;
        if (principalToServiceAccountMapping.containsKey(userPrincipal)) {
            return principalToServiceAccountMapping.get(userPrincipal);
        }
        
        return null;
    }
    
    private boolean createAcl(String principal, String operation, String resourceType, 
                             String resourceName, String patternType, String host) {
        System.out.println("⏳ Creating ACL: " + principal + " -> " + operation + " on " + resourceType + ":" + resourceName);
        
        if (dryRun) {
            System.out.println("  🔍 Would create ACL for " + principal);
            return true;
        }
        
        try {
            // Create resource pattern
            ResourcePattern resourcePattern = new ResourcePattern(
                convertResourceType(resourceType),
                resourceName,
                convertPatternType(patternType)
            );
            
            // Create access control entry
            AccessControlEntry ace = new AccessControlEntry(
                principal,
                host.equals("*") ? "*" : host,
                convertOperation(operation),
                AclPermissionType.ALLOW
            );
            
            // Create ACL binding
            AclBinding aclBinding = new AclBinding(resourcePattern, ace);
            
            // Create ACL
            adminClient.createAcls(
                Collections.singletonList(aclBinding),
                new CreateAclsOptions()
            ).all().get();
            
            System.out.println("  ✅ ACL created successfully");
            return true;
            
        } catch (Exception e) {
            System.out.println("  ❌ Failed to create ACL: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return false;
        }
    }
    
    private ResourceType convertResourceType(String resourceType) {
        switch (resourceType.toUpperCase()) {
            case "TOPIC":
                return ResourceType.TOPIC;
            case "GROUP":
                return ResourceType.GROUP;
            case "CLUSTER":
                return ResourceType.CLUSTER;
            case "TRANSACTIONAL_ID":
                return ResourceType.TRANSACTIONAL_ID;
            case "DELEGATION_TOKEN":
                return ResourceType.DELEGATION_TOKEN;
            default:
                throw new IllegalArgumentException("Unknown resource type: " + resourceType);
        }
    }
    
    private AclOperation convertOperation(String operation) {
        switch (operation.toUpperCase()) {
            case "READ":
                return AclOperation.READ;
            case "WRITE":
                return AclOperation.WRITE;
            case "CREATE":
                return AclOperation.CREATE;
            case "DELETE":
                return AclOperation.DELETE;
            case "ALTER":
                return AclOperation.ALTER;
            case "DESCRIBE":
                return AclOperation.DESCRIBE;
            case "CLUSTER_ACTION":
                return AclOperation.CLUSTER_ACTION;
            case "DESCRIBE_CONFIGS":
                return AclOperation.DESCRIBE_CONFIGS;
            case "ALTER_CONFIGS":
                return AclOperation.ALTER_CONFIGS;
            case "IDEMPOTENT_WRITE":
                return AclOperation.IDEMPOTENT_WRITE;
            case "ALL":
                return AclOperation.ALL;
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }
    
    private PatternType convertPatternType(String patternType) {
        switch (patternType.toUpperCase()) {
            case "LITERAL":
                return PatternType.LITERAL;
            case "PREFIXED":
                return PatternType.PREFIXED;
            default:
                return PatternType.LITERAL;
        }
    }
    
    private void recordResult(String originalPrincipal, String ccPrincipal, String operation,
                             String resourceType, String resourceName, String status) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("original_principal", originalPrincipal);
        result.put("cc_principal", ccPrincipal);
        result.put("operation", operation);
        result.put("resource_type", resourceType);
        result.put("resource_name", resourceName);
        result.put("status", status);
        
        migrationResults.add(result);
    }
    
    private void generateReport() throws IOException {
        System.out.println("📄 Creating migration report: " + outputFile);
        
        if (dryRun) {
            System.out.println("🔍 Would create migration report");
            return;
        }
        
        ObjectNode report = objectMapper.createObjectNode();
        
        // Migration metadata
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("timestamp", Instant.now().toString());
        metadata.put("source_file", aclsFile);
        metadata.put("service_accounts_file", serviceAccountsFile);
        metadata.put("dry_run", dryRun);
        report.set("migration_metadata", metadata);
        
        // Migration summary
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("total_acls", totalAcls);
        summary.put("migrated_acls", migratedAcls);
        summary.put("skipped_acls", skippedAcls);
        summary.put("failed_acls", failedAcls);
        report.set("migration_summary", summary);
        
        // Migration results
        ArrayNode results = objectMapper.createArrayNode();
        for (ObjectNode result : migrationResults) {
            results.add(result);
        }
        report.set("migration_results", results);
        
        // Write report to file
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputFile), report);
        
        System.out.println("✅ Migration report saved to: " + outputFile);
    }
    
    private void printSummary() {
        System.out.println("");
        System.out.println("🔐 ACL Migration Summary:");
        System.out.println("==========================");
        System.out.println("✅ Total ACLs processed: " + totalAcls);
        System.out.println("✅ ACLs migrated: " + migratedAcls);
        System.out.println("⚠️ ACLs skipped: " + skippedAcls);
        System.out.println("❌ ACLs failed: " + failedAcls);
        
        if (failedAcls > 0) {
            System.out.println("❌ Some ACLs failed to migrate. Check the migration report for details.");
            System.exit(1);
        } else if (migratedAcls == 0) {
            System.out.println("⚠️ No ACLs were migrated.");
            System.exit(1);
        } else {
            System.out.println("✅ ACL migration completed successfully!");
        }
    }
    
    private void cleanup() {
        if (adminClient != null) {
            adminClient.close();
        }
    }
} 
 
 
 