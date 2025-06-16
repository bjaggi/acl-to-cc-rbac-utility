package com.confluent.utility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * ACL to RBAC Converter Utility
 * 
 * This utility reads MSK ACL data from JSON file and converts it to Confluent Cloud RBAC format.
 */
public class ACLToRBACConverter {
    
    private static final Logger logger = LoggerFactory.getLogger(ACLToRBACConverter.class);
    
    // Confluent Cloud predefined roles mapping
    private static final Map<String, String> OPERATION_TO_ROLE_MAPPING = new HashMap<>();
    static {
        // Producer operations
        OPERATION_TO_ROLE_MAPPING.put("WRITE", "DeveloperWrite");
        OPERATION_TO_ROLE_MAPPING.put("CREATE", "DeveloperWrite");
        OPERATION_TO_ROLE_MAPPING.put("DESCRIBE", "DeveloperRead");
        
        // Consumer operations
        OPERATION_TO_ROLE_MAPPING.put("READ", "DeveloperRead");
        
        // Admin operations
        OPERATION_TO_ROLE_MAPPING.put("ALTER", "ResourceOwner");
        OPERATION_TO_ROLE_MAPPING.put("DELETE", "ResourceOwner");
        OPERATION_TO_ROLE_MAPPING.put("DESCRIBE_CONFIGS", "ResourceOwner");
        OPERATION_TO_ROLE_MAPPING.put("ALTER_CONFIGS", "ResourceOwner");
        
        // Cluster operations
        OPERATION_TO_ROLE_MAPPING.put("CLUSTER_ACTION", "ClusterAdmin");
        OPERATION_TO_ROLE_MAPPING.put("IDEMPOTENT_WRITE", "DeveloperWrite");
    }
    
    public static class MSKACLData {
        public List<ACLBinding> acls;
        public List<TopicInfo> topics;
        public ClusterMetadata cluster_metadata;
        public int count;           // Backward compatibility
        public int acl_count;       // New field
        public int topic_count;     // New field
        public String exported_at;
    }
    
    public static class ClusterMetadata {
        public String cluster_name;
        public int number_of_broker_nodes;
        public String cluster_arn;
        public String kafka_version;
        public String state;
        public String region;
        public String instance_type;
    }
    
    public static class ConfluentCloudRoleBinding {
        public String principal;
        public String role;
        public String resource_type;
        public String resource_name;
        public String pattern_type;
        public String environment;
        public String cluster_id;
        
        // Default constructor for Jackson deserialization
        public ConfluentCloudRoleBinding() {
        }
        
        public ConfluentCloudRoleBinding(String principal, String role, String resourceType, 
                                       String resourceName, String patternType, String environment, String clusterId) {
            this.principal = principal;
            this.role = role;
            this.resource_type = resourceType;
            this.resource_name = resourceName;
            this.pattern_type = patternType;
            this.environment = environment;
            this.cluster_id = clusterId;
        }
    }
    
    public static class ConfluentCloudRBACOutput {
        public List<ConfluentCloudRoleBinding> role_bindings;
        public List<ServiceAccountInfo> service_accounts;
        public ConversionMetadata conversion_metadata;
        
        public ConfluentCloudRBACOutput() {
            this.role_bindings = new ArrayList<>();
            this.service_accounts = new ArrayList<>();
            this.conversion_metadata = new ConversionMetadata();
        }
    }
    
    public static class ServiceAccountInfo {
        public String name;
        public String description;
        public String original_principal;
        
        // Default constructor for Jackson deserialization
        public ServiceAccountInfo() {
        }
        
        public ServiceAccountInfo(String name, String description, String originalPrincipal) {
            this.name = name;
            this.description = description;
            this.original_principal = originalPrincipal;
        }
    }
    
    public static class ConversionMetadata {
        public String source_cluster;
        public String source_region;
        public int original_acl_count;
        public int converted_role_bindings_count;
        public String converted_at;
        public List<String> conversion_notes;
        
        public ConversionMetadata() {
            this.conversion_notes = new ArrayList<>();
            this.converted_at = LocalDateTime.now().toString();
        }
    }
    
    private final ObjectMapper objectMapper;
    
    public ACLToRBACConverter() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }
    
    /**
     * Read MSK ACL data from JSON file
     */
    public MSKACLData readMSKACLData(String inputFile) throws IOException {
        logger.info("Reading MSK ACL data from file: {}", inputFile);
        
        File file = new File(inputFile);
        if (!file.exists()) {
            throw new IOException("Input file does not exist: " + inputFile);
        }
        
        try {
            return objectMapper.readValue(file, MSKACLData.class);
        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            if (e.getMessage().contains("Unexpected character ('#'")) {
                throw new IOException("JSON file contains comments starting with '#'. " +
                    "JSON files cannot contain comments. Please remove all comments from the file: " + inputFile, e);
            } else {
                throw new IOException("Invalid JSON format in file: " + inputFile + ". " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * Convert MSK ACLs to Confluent Cloud RBAC format
     */
    public ConfluentCloudRBACOutput convertToConfluentCloudRBAC(MSKACLData mskData, 
                                                                String targetEnvironment, 
                                                                String targetClusterId) {
        int aclCount = mskData.acl_count > 0 ? mskData.acl_count : mskData.count;
        logger.info("Converting {} ACLs to Confluent Cloud RBAC format", aclCount);
        
        ConfluentCloudRBACOutput output = new ConfluentCloudRBACOutput();
        
        // Set conversion metadata
        if (mskData.cluster_metadata != null) {
            output.conversion_metadata.source_cluster = mskData.cluster_metadata.cluster_name;
            output.conversion_metadata.source_region = mskData.cluster_metadata.region;
        }
        output.conversion_metadata.original_acl_count = aclCount;
        
        // Track role bindings to avoid duplicates
        Set<String> processedBindings = new HashSet<>();
        Set<String> processedPrincipals = new HashSet<>();
        
        for (ACLBinding acl : mskData.acls) {
            try {
                List<ConfluentCloudRoleBinding> roleBindings = convertACLToRoleBindings(
                    acl, targetEnvironment, targetClusterId);
                
                for (ConfluentCloudRoleBinding binding : roleBindings) {
                    String bindingKey = String.format("%s:%s:%s:%s:%s", 
                        binding.principal, binding.role, binding.resource_type, 
                        binding.resource_name, binding.pattern_type);
                    
                    if (!processedBindings.contains(bindingKey)) {
                        output.role_bindings.add(binding);
                        processedBindings.add(bindingKey);
                        
                        // Track unique principals for service account creation
                        if (!processedPrincipals.contains(binding.principal)) {
                            String serviceAccountName = sanitizeServiceAccountName(binding.principal);
                            String description = String.format("Service account for %s (migrated from MSK)", 
                                acl.getPrincipal());
                            
                            output.service_accounts.add(new ServiceAccountInfo(
                                serviceAccountName, description, acl.getPrincipal()));
                            processedPrincipals.add(binding.principal);
                        }
                    }
                }
                
            } catch (Exception e) {
                logger.warn("Failed to convert ACL: {} - {}", acl, e.getMessage());
                output.conversion_metadata.conversion_notes.add(
                    "Failed to convert ACL: " + acl.toString() + " - " + e.getMessage());
            }
        }
        
        output.conversion_metadata.converted_role_bindings_count = output.role_bindings.size();
        
        // Add conversion notes
        addConversionNotes(output.conversion_metadata, mskData);
        
        logger.info("Converted {} ACLs to {} role bindings", 
                   aclCount, output.role_bindings.size());
        
        return output;
    }
    
    /**
     * Convert a single ACL to one or more Confluent Cloud role bindings
     */
    private List<ConfluentCloudRoleBinding> convertACLToRoleBindings(ACLBinding acl, 
                                                                    String environment, 
                                                                    String clusterId) {
        List<ConfluentCloudRoleBinding> bindings = new ArrayList<>();
        
        // Skip DENY permissions as Confluent Cloud RBAC uses ALLOW-based model
        if ("DENY".equals(acl.getPermissionType())) {
            logger.warn("Skipping DENY ACL as Confluent Cloud RBAC uses ALLOW-based permissions: {}", acl);
            return bindings;
        }
        
        // Skip Group:* patterns as they are not valid in Confluent Cloud RBAC
        if ("GROUP".equals(acl.getResourceType()) && "*".equals(acl.getResourceName()) && "LITERAL".equals(acl.getPatternType())) {
            logger.warn("Skipping Group:* ACL as wildcard group names are not supported in Confluent Cloud RBAC: {}", acl);
            return bindings;
        }
        
        // Extract principal - remove "User:" prefix if present
        String principal = acl.getPrincipal();
        if (principal.startsWith("User:")) {
            principal = principal.substring(5);
        }
        
        // Map operation to Confluent Cloud role
        String role = mapOperationToRole(acl.getOperation(), acl.getResourceType());
        
        // Convert resource type to Confluent Cloud format
        String resourceType = convertResourceType(acl.getResourceType());
        
        // Convert pattern type
        String patternType = convertPatternType(acl.getPatternType());
        
        // Create role binding
        ConfluentCloudRoleBinding binding = new ConfluentCloudRoleBinding(
            principal, role, resourceType, acl.getResourceName(), 
            patternType, environment, clusterId);
        
        bindings.add(binding);
        
        return bindings;
    }
    
    /**
     * Map MSK operation to Confluent Cloud role
     */
    private String mapOperationToRole(String operation, String resourceType) {
        // Special handling for specific resource types
        if ("GROUP".equals(resourceType)) {
            if ("READ".equals(operation)) {
                return "DeveloperRead";
            }
        }
        
        if ("TOPIC".equals(resourceType)) {
            switch (operation) {
                case "READ":
                    return "DeveloperRead";
                case "WRITE":
                    return "DeveloperWrite";
                case "CREATE":
                    return "DeveloperManage";
                case "DELETE":
                    return "ResourceOwner";
                case "ALTER":
                    return "ResourceOwner";
                case "DESCRIBE":
                    return "DeveloperRead";
                case "DESCRIBE_CONFIGS":
                    return "DeveloperRead";
                case "ALTER_CONFIGS":
                    return "ResourceOwner";
                default:
                    return "DeveloperRead"; // Default fallback
            }
        }
        
        if ("CLUSTER".equals(resourceType)) {
            return "ClusterAdmin";
        }
        
        // Use mapping table for general operations
        return OPERATION_TO_ROLE_MAPPING.getOrDefault(operation, "DeveloperRead");
    }
    
    /**
     * Convert MSK resource type to Confluent Cloud format
     */
    private String convertResourceType(String resourceType) {
        switch (resourceType.toLowerCase()) {
            case "topic":
                return "Topic";
            case "group":
                return "Group";
            case "cluster":
                return "Cluster";
            case "transactional_id":
                return "TransactionalId";
            case "delegation_token":
                return "DelegationToken";
            default:
                return resourceType; // Keep original if unknown
        }
    }
    
    /**
     * Convert MSK pattern type to Confluent Cloud format
     */
    private String convertPatternType(String patternType) {
        switch (patternType.toLowerCase()) {
            case "literal":
                return "LITERAL";
            case "prefixed":
                return "PREFIXED";
            default:
                return "LITERAL"; // Default to literal
        }
    }
    
    /**
     * Sanitize service account name for Confluent Cloud
     */
    private String sanitizeServiceAccountName(String principal) {
        // Remove special characters and spaces, convert to lowercase
        return principal.replaceAll("[^a-zA-Z0-9-]", "-")
                       .toLowerCase()
                       .replaceAll("-+", "-")
                       .replaceAll("^-|-$", "");
    }
    
    /**
     * Add helpful conversion notes
     */
    private void addConversionNotes(ConversionMetadata metadata, MSKACLData mskData) {
        metadata.conversion_notes.add("Conversion from MSK ACLs to Confluent Cloud RBAC completed");
        metadata.conversion_notes.add("DENY permissions were skipped as Confluent Cloud uses ALLOW-based RBAC");
        metadata.conversion_notes.add("Group:* ACLs were skipped as wildcard group names are not supported in Confluent Cloud RBAC");
        metadata.conversion_notes.add("Service accounts need to be created in Confluent Cloud before applying role bindings");
        metadata.conversion_notes.add("Role assignments may need manual review for complex permission patterns");
        metadata.conversion_notes.add("Ensure target environment and cluster ID are correctly specified");
        metadata.conversion_notes.add("Some MSK-specific operations may not have direct Confluent Cloud equivalents");
    }
    
    /**
     * Write Confluent Cloud RBAC data to JSON file
     */
    public void writeConfluentCloudRBAC(ConfluentCloudRBACOutput rbacData, String outputFile) throws IOException {
        logger.info("Writing Confluent Cloud RBAC data to file: {}", outputFile);
        
        // Create output directory if needed
        java.io.File outputFileObj = new java.io.File(outputFile);
        java.io.File parentDir = outputFileObj.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
            logger.info("Created output directory: {}", parentDir.getPath());
        }
        
        try (FileWriter writer = new FileWriter(outputFile)) {
            objectMapper.writeValue(writer, rbacData);
        }
        
        logger.info("Successfully wrote {} role bindings to {}", 
                   rbacData.role_bindings.size(), outputFile);
    }
    
    /**
     * Main method for command-line usage
     */
    public static void main(String[] args) {
        // Check for help flag
        if (args.length > 0 && (args[0].equals("-h") || args[0].equals("--help"))) {
            System.out.println("MSK ACL to Confluent Cloud RBAC Converter");
            System.out.println("Usage: java ACLToRBACConverter [output_file] [environment] [cluster_id]");
            System.out.println("  output_file - Output file for Confluent Cloud RBAC (default: generated_jsons/cc_jsons/cc_rbac.json)");
            System.out.println("  environment - Target Confluent Cloud environment (default: env-xxxxx)");
            System.out.println("  cluster_id  - Target Confluent Cloud cluster ID (default: lkc-xxxxx)");
            System.out.println("");
            System.out.println("Note: Input file is always read from generated_jsons/msk_acls.json");
            System.out.println("");
            System.out.println("Examples:");
            System.out.println("  java ACLToRBACConverter");
            System.out.println("  java ACLToRBACConverter my_rbac.json env-abc123 lkc-xyz789");
            System.exit(0);
        }
        
        // Always read from generated_jsons/msk_acls.json
        String inputFile = "generated_jsons/msk_acls.json";
        String outputFile = args.length > 0 ? args[0] : "generated_jsons/cc_jsons/cc_rbac.json";
        String environment = args.length > 1 ? args[1] : "env-xxxxx";
        String clusterId = args.length > 2 ? args[2] : "lkc-xxxxx";
        
        ACLToRBACConverter converter = new ACLToRBACConverter();
        
        try {
            // Read MSK ACL data
            MSKACLData mskData = converter.readMSKACLData(inputFile);
            
            // Convert to Confluent Cloud RBAC
            ConfluentCloudRBACOutput rbacOutput = converter.convertToConfluentCloudRBAC(
                mskData, environment, clusterId);
            
            // Write output
            converter.writeConfluentCloudRBAC(rbacOutput, outputFile);
            
            // Print summary
            System.out.println("Conversion completed successfully!");
            System.out.println("Input file: " + inputFile);
            System.out.println("Output file: " + outputFile);
            System.out.println("Original ACLs: " + mskData.count);
            System.out.println("Converted role bindings: " + rbacOutput.role_bindings.size());
            System.out.println("Target environment: " + environment);
            System.out.println("Target cluster: " + clusterId);
            
            if (!rbacOutput.conversion_metadata.conversion_notes.isEmpty()) {
                System.out.println("\nConversion notes:");
                for (String note : rbacOutput.conversion_metadata.conversion_notes) {
                    System.out.println("  - " + note);
                }
            }
            
        } catch (Exception e) {
            logger.error("Conversion failed: {}", e.getMessage(), e);
            System.err.println("Conversion failed: " + e.getMessage());
            System.exit(1);
        }
    }
} 