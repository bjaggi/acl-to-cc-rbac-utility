package com.confluent.utility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * MSK Principal Extractor
 * Extracts unique principals from MSK ACLs and exports them to a separate JSON file
 */
public class MSKPrincipalExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger(MSKPrincipalExtractor.class);
    private final ObjectMapper objectMapper;
    
    public MSKPrincipalExtractor() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * Extract principals from MSK ACLs file and export to principals file
     */
    public void extractPrincipalsFromFile(String aclsFilePath, String principalsFilePath) throws IOException {
        logger.info("Reading MSK ACLs from: {}", aclsFilePath);
        
        // Read ACLs data
        MSKACLsData aclsData = objectMapper.readValue(new File(aclsFilePath), MSKACLsData.class);
        
        logger.info("Loaded {} ACLs for principal extraction", aclsData.acls.size());
        
        // Extract unique principals
        Set<String> uniquePrincipals = extractUniquePrincipals(aclsData.acls);
        
        // Create principals data structure
        MSKPrincipalsData principalsData = createPrincipalsData(uniquePrincipals, aclsData);
        
        // Write to file
        objectMapper.writerWithDefaultPrettyPrinter()
                   .writeValue(new File(principalsFilePath), principalsData);
        
        logger.info("✅ Exported {} unique principals to: {}", uniquePrincipals.size(), principalsFilePath);
        
        // Print summary
        printSummary(principalsData);
    }
    
    /**
     * Extract unique principals from ACL list
     */
    private Set<String> extractUniquePrincipals(List<ACLEntry> acls) {
        Set<String> principals = new LinkedHashSet<>(); // LinkedHashSet to maintain order
        
        for (ACLEntry acl : acls) {
            if (acl.principal != null && !acl.principal.trim().isEmpty()) {
                principals.add(acl.principal);
            }
        }
        
        logger.debug("Found {} unique principals", principals.size());
        return principals;
    }
    
    /**
     * Create principals data structure
     */
    private MSKPrincipalsData createPrincipalsData(Set<String> uniquePrincipals, MSKACLsData aclsData) {
        MSKPrincipalsData principalsData = new MSKPrincipalsData();
        
        // Convert principals to detailed objects
        for (String principal : uniquePrincipals) {
            PrincipalInfo principalInfo = new PrincipalInfo();
            principalInfo.principal = principal;
            principalInfo.principal_type = extractPrincipalType(principal);
            principalInfo.principal_name = extractPrincipalName(principal);
            principalInfo.acl_count = countACLsForPrincipal(principal, aclsData.acls);
            principalInfo.permissions = extractPermissionsForPrincipal(principal, aclsData.acls);
            
            principalsData.principals.add(principalInfo);
        }
        
        // Set metadata
        principalsData.cluster_metadata = aclsData.cluster_metadata;
        principalsData.principal_count = uniquePrincipals.size();
        principalsData.total_acl_count = aclsData.acl_count;
        principalsData.exported_at = LocalDateTime.now().toString();
        
        return principalsData;
    }
    
    /**
     * Extract principal type (User, ServiceAccount, etc.)
     */
    private String extractPrincipalType(String principal) {
        if (principal.contains(":")) {
            return principal.split(":", 2)[0];
        }
        return "Unknown";
    }
    
    /**
     * Extract principal name (without type prefix)
     */
    private String extractPrincipalName(String principal) {
        if (principal.contains(":")) {
            return principal.split(":", 2)[1];
        }
        return principal;
    }
    
    /**
     * Count ACLs for a specific principal
     */
    private int countACLsForPrincipal(String principal, List<ACLEntry> acls) {
        return (int) acls.stream()
                        .filter(acl -> principal.equals(acl.principal))
                        .count();
    }
    
    /**
     * Extract permissions summary for a principal
     */
    private List<String> extractPermissionsForPrincipal(String principal, List<ACLEntry> acls) {
        Set<String> permissions = new LinkedHashSet<>();
        
        for (ACLEntry acl : acls) {
            if (principal.equals(acl.principal)) {
                String permission = String.format("%s:%s on %s:%s", 
                    acl.permission_type, acl.operation, acl.resource_type, acl.resource_name);
                permissions.add(permission);
            }
        }
        
        return new ArrayList<>(permissions);
    }
    
    /**
     * Print extraction summary
     */
    private void printSummary(MSKPrincipalsData principalsData) {
        logger.info("Principal Extraction Summary:");
        logger.info("============================");
        logger.info("Total Principals: {}", principalsData.principal_count);
        logger.info("Total ACLs: {}", principalsData.total_acl_count);
        
        // Group by principal type
        Map<String, Long> typeCount = principalsData.principals.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                p -> p.principal_type, 
                java.util.stream.Collectors.counting()));
        
        logger.info("Principal Types:");
        typeCount.forEach((type, count) -> 
            logger.info("  {}: {}", type, count));
        
        // Show principals with most ACLs
        logger.info("Principals (sorted by ACL count):");
        principalsData.principals.stream()
            .sorted((a, b) -> Integer.compare(b.acl_count, a.acl_count))
            .forEach(p -> logger.info("  {} ({}): {} ACLs", 
                p.principal_type, p.principal_name, p.acl_count));
    }
    
    /**
     * MSK ACLs Data structure (for reading)
     */
    public static class MSKACLsData {
        public List<ACLEntry> acls;
        public MSKClusterMetadata cluster_metadata;
        public int acl_count;
        public String exported_at;
        
        public MSKACLsData() {
            this.acls = new ArrayList<>();
        }
    }
    
    /**
     * ACL Entry structure
     */
    public static class ACLEntry {
        public String principal;
        public String host;
        public String operation;
        public String permission_type;
        public String resource_type;
        public String resource_name;
        public String pattern_type;
    }
    
    /**
     * MSK Principals Data structure (for writing)
     */
    public static class MSKPrincipalsData {
        public List<PrincipalInfo> principals;
        public MSKClusterMetadata cluster_metadata;
        public int principal_count;
        public int total_acl_count;
        public String exported_at;
        
        public MSKPrincipalsData() {
            this.principals = new ArrayList<>();
        }
    }
    
    /**
     * Principal information structure
     */
    public static class PrincipalInfo {
        public String principal;
        public String principal_type;
        public String principal_name;
        public int acl_count;
        public List<String> permissions;
        
        public PrincipalInfo() {
            this.permissions = new ArrayList<>();
        }
    }
    
    /**
     * MSK Cluster metadata structure
     */
    public static class MSKClusterMetadata {
        public String cluster_name;
        public int number_of_broker_nodes;
        public String cluster_arn;
        public String kafka_version;
        public String state;
        public String region;
        public String instance_type;
    }
    
    /**
     * Main method for command-line usage
     */
    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            printHelp();
            System.exit(0);
        }
        
        String aclsFile = args.length > 0 ? args[0] : "generated_jsons/msk_jsons/msk_acls.json";
        String principalsFile = args.length > 1 ? args[1] : "generated_jsons/msk_jsons/msk_principals.json";
        
        try {
            MSKPrincipalExtractor extractor = new MSKPrincipalExtractor();
            extractor.extractPrincipalsFromFile(aclsFile, principalsFile);
            
            System.out.println("🎉 Principal extraction completed successfully!");
            System.out.println("Output file: " + principalsFile);
            
        } catch (Exception e) {
            logger.error("Principal extraction failed: {}", e.getMessage(), e);
            System.err.println("❌ Principal extraction failed: " + e.getMessage());
            System.exit(1);
        }
    }
    
    private static void printHelp() {
        System.out.println("MSK Principal Extractor");
        System.out.println("Usage: java MSKPrincipalExtractor [acls_file] [principals_file]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  acls_file       MSK ACLs JSON file (default: generated_jsons/msk_jsons/msk_acls.json)");
        System.out.println("  principals_file Output principals JSON file (default: generated_jsons/msk_jsons/msk_principals.json)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java MSKPrincipalExtractor");
        System.out.println("  java MSKPrincipalExtractor my_acls.json my_principals.json");
        System.out.println();
        System.out.println("The tool will:");
        System.out.println("1. Read ACLs from the input file");
        System.out.println("2. Extract unique principals");
        System.out.println("3. Analyze permissions for each principal");
        System.out.println("4. Export detailed principal information to JSON");
    }
} 