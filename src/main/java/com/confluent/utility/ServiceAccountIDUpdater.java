package com.confluent.utility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility to update service account IDs in generated JSON files
 * when the ID is manually retrieved from Confluent Cloud
 */
public class ServiceAccountIDUpdater {
    
    private static final Logger logger = LoggerFactory.getLogger(ServiceAccountIDUpdater.class);
    private final ObjectMapper objectMapper;
    
    // File paths
    private static final String SERVICE_ACCOUNTS_FILE = "generated_jsons/cc_jsons/cc_service_accounts.json";
    private static final String RBAC_FILE = "generated_jsons/cc_jsons/cc_rbac.json";
    
    // Status icons
    private static final String SUCCESS = "✅";
    private static final String ERROR = "❌";
    private static final String INFO = "ℹ️";
    private static final String WARNING = "⚠️";
    private static final String PROCESSING = "⏳";
    
    public ServiceAccountIDUpdater() {
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Update service account ID in both files
     */
    public boolean updateServiceAccountID(String serviceAccountName, String serviceAccountID) {
        logger.info("{} Updating service account ID for '{}' to '{}'", PROCESSING, serviceAccountName, serviceAccountID);
        
        boolean serviceAccountsUpdated = false;
        boolean rbacUpdated = false;
        
        try {
            // Update service accounts file
            serviceAccountsUpdated = updateServiceAccountsFile(serviceAccountName, serviceAccountID);
            
            // Update RBAC file
            rbacUpdated = updateRBACFile(serviceAccountName, serviceAccountID);
            
            if (serviceAccountsUpdated && rbacUpdated) {
                logger.info("{} Successfully updated service account ID in both files", SUCCESS);
                return true;
            } else {
                logger.warn("{} Partial update completed. Service accounts: {}, RBAC: {}", 
                           WARNING, serviceAccountsUpdated, rbacUpdated);
                return false;
            }
            
        } catch (Exception e) {
            logger.error("{} Failed to update service account ID: {}", ERROR, e.getMessage());
            return false;
        }
    }
    
    /**
     * Update service accounts file
     */
    private boolean updateServiceAccountsFile(String serviceAccountName, String serviceAccountID) throws IOException {
        File file = new File(SERVICE_ACCOUNTS_FILE);
        if (!file.exists()) {
            logger.warn("{} Service accounts file not found: {}", WARNING, SERVICE_ACCOUNTS_FILE);
            return false;
        }
        
        logger.info("{} Updating service accounts file: {}", INFO, SERVICE_ACCOUNTS_FILE);
        
        // Read current file
        JsonNode root = objectMapper.readTree(file);
        ObjectNode rootObj = (ObjectNode) root;
        
        // Find and update the service account
        ArrayNode serviceAccounts = (ArrayNode) root.path("service_accounts");
        boolean found = false;
        
        for (JsonNode serviceAccount : serviceAccounts) {
            ObjectNode saObj = (ObjectNode) serviceAccount;
            String name = saObj.path("name").asText();
            
            if (serviceAccountName.equals(name)) {
                logger.info("{} Found service account '{}' in file", SUCCESS, name);
                
                // Update the fields
                saObj.put("id", serviceAccountID);
                saObj.put("account_id", serviceAccountID);
                saObj.put("resource_id", serviceAccountID);
                saObj.put("message", "Already exists (ID manually updated via Java utility)");
                
                found = true;
                break;
            }
        }
        
        if (!found) {
            logger.warn("{} Service account '{}' not found in service accounts file", WARNING, serviceAccountName);
            return false;
        }
        
        // Write updated file
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, rootObj);
        logger.info("{} Service accounts file updated successfully", SUCCESS);
        
        return true;
    }
    
    /**
     * Update RBAC file
     */
    private boolean updateRBACFile(String serviceAccountName, String serviceAccountID) throws IOException {
        File file = new File(RBAC_FILE);
        if (!file.exists()) {
            logger.warn("{} RBAC file not found: {}", WARNING, RBAC_FILE);
            return false;
        }
        
        logger.info("{} Updating RBAC file: {}", INFO, RBAC_FILE);
        
        // Read current file
        JsonNode root = objectMapper.readTree(file);
        ObjectNode rootObj = (ObjectNode) root;
        
        int updatedCount = 0;
        
        // Update role bindings
        ArrayNode roleBindings = (ArrayNode) root.path("role_bindings");
        for (JsonNode roleBinding : roleBindings) {
            ObjectNode rbObj = (ObjectNode) roleBinding;
            String principal = rbObj.path("principal").asText();
            
            if (serviceAccountName.equals(principal)) {
                String currentResourceId = rbObj.path("resource_id").asText();
                if ("EXISTING_ID_UNKNOWN".equals(currentResourceId) || currentResourceId.isEmpty()) {
                    rbObj.put("resource_id", serviceAccountID);
                    updatedCount++;
                    logger.info("{} Updated role binding for principal '{}'", SUCCESS, principal);
                }
            }
        }
        
        // Update service accounts section
        ArrayNode serviceAccounts = (ArrayNode) root.path("service_accounts");
        for (JsonNode serviceAccount : serviceAccounts) {
            ObjectNode saObj = (ObjectNode) serviceAccount;
            String name = saObj.path("name").asText();
            
            if (serviceAccountName.equals(name)) {
                String currentResourceId = saObj.path("resource_id").asText();
                if ("EXISTING_ID_UNKNOWN".equals(currentResourceId) || currentResourceId.isEmpty()) {
                    saObj.put("resource_id", serviceAccountID);
                    updatedCount++;
                    logger.info("{} Updated service account entry for '{}'", SUCCESS, name);
                }
            }
        }
        
        // Update metadata
        ObjectNode metadata = (ObjectNode) root.path("rbac_update_metadata");
        if (metadata != null && !metadata.isMissingNode()) {
            // Update timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
            metadata.put("updated_at", timestamp);
            metadata.put("updated_by", "ServiceAccountIDUpdater.java");
            
            // Update counts
            int validIdCount = metadata.path("valid_id_count").asInt(0);
            int existingUnknownCount = metadata.path("existing_unknown_id_count").asInt(0);
            
            if (updatedCount > 0) {
                metadata.put("valid_id_count", validIdCount + 1);
                metadata.put("existing_unknown_id_count", Math.max(0, existingUnknownCount - 1));
            }
            
            logger.info("{} Updated metadata: valid_id_count={}, existing_unknown_id_count={}", 
                       INFO, validIdCount + 1, Math.max(0, existingUnknownCount - 1));
        }
        
        if (updatedCount == 0) {
            logger.warn("{} No entries found to update in RBAC file for '{}'", WARNING, serviceAccountName);
            return false;
        }
        
        // Write updated file
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, rootObj);
        logger.info("{} RBAC file updated successfully ({} entries updated)", SUCCESS, updatedCount);
        
        return true;
    }
    
    /**
     * List service accounts with unknown IDs
     */
    public void listUnknownServiceAccounts() {
        try {
            File file = new File(SERVICE_ACCOUNTS_FILE);
            if (!file.exists()) {
                logger.warn("{} Service accounts file not found: {}", WARNING, SERVICE_ACCOUNTS_FILE);
                return;
            }
            
            JsonNode root = objectMapper.readTree(file);
            ArrayNode serviceAccounts = (ArrayNode) root.path("service_accounts");
            
            List<String> unknownAccounts = new ArrayList<>();
            
            for (JsonNode serviceAccount : serviceAccounts) {
                String name = serviceAccount.path("name").asText();
                JsonNode idNode = serviceAccount.path("id");
                String status = serviceAccount.path("status").asText();
                
                if (idNode.isNull() || idNode.asText().isEmpty() || "existing".equals(status)) {
                    unknownAccounts.add(name);
                }
            }
            
            if (unknownAccounts.isEmpty()) {
                logger.info("{} All service accounts have valid IDs", SUCCESS);
            } else {
                logger.info("{} Service accounts with unknown IDs:", INFO);
                for (String account : unknownAccounts) {
                    logger.info("  • {}", account);
                }
            }
            
        } catch (Exception e) {
            logger.error("{} Failed to list unknown service accounts: {}", ERROR, e.getMessage());
        }
    }
    
    /**
     * Main method for command line usage
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            printHelp();
            return;
        }
        
        ServiceAccountIDUpdater updater = new ServiceAccountIDUpdater();
        
        String command = args[0].toLowerCase();
        
        switch (command) {
            case "update":
                if (args.length != 3) {
                    System.err.println("Error: update command requires service account name and ID");
                    printHelp();
                    System.exit(1);
                }
                
                String serviceAccountName = args[1];
                String serviceAccountID = args[2];
                
                if (!serviceAccountID.startsWith("sa-")) {
                    System.err.println("Warning: Service account ID should start with 'sa-' (e.g., sa-abc123)");
                }
                
                boolean success = updater.updateServiceAccountID(serviceAccountName, serviceAccountID);
                System.exit(success ? 0 : 1);
                break;
                
            case "list":
                updater.listUnknownServiceAccounts();
                break;
                
            default:
                System.err.println("Error: Unknown command: " + command);
                printHelp();
                System.exit(1);
        }
    }
    
    /**
     * Print help information
     */
    private static void printHelp() {
        System.out.println("Service Account ID Updater");
        System.out.println("==========================");
        System.out.println();
        System.out.println("Usage: java ServiceAccountIDUpdater <command> [arguments]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  update <name> <id>    Update service account ID in both JSON files");
        System.out.println("  list                  List service accounts with unknown IDs");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java ServiceAccountIDUpdater update jaggi-msk-role sa-abc123");
        System.out.println("  java ServiceAccountIDUpdater list");
        System.out.println();
        System.out.println("Files updated:");
        System.out.println("  • " + SERVICE_ACCOUNTS_FILE);
        System.out.println("  • " + RBAC_FILE);
    }
} 