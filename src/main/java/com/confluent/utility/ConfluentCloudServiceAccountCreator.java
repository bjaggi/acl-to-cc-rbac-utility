package com.confluent.utility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.confluent.utility.StatusIcons.*;

/**
 * Confluent Cloud Service Account Creator
 * Creates service accounts in Confluent Cloud based on principals from MSK
 */
public class ConfluentCloudServiceAccountCreator {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfluentCloudServiceAccountCreator.class);
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String apiSecret;
    private final boolean dryRun;
    private final boolean verbose;
    
    public ConfluentCloudServiceAccountCreator(ConfluentCloudRBACApplicator.ConfluentCloudConfig config, boolean dryRun, boolean verbose) {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        
        // Always use Confluent Cloud API for service account operations
        this.baseUrl = "https://api.confluent.cloud";
        this.apiKey = config.apiKey;
        this.apiSecret = config.apiSecret;
        this.dryRun = dryRun;
        this.verbose = verbose;
    }
    
    /**
     * Create service accounts from principals file
     */
    public void createServiceAccountsFromFile(String principalsFilePath) throws IOException {
        logger.info("{} Reading principals from: {}", PROCESSING, principalsFilePath);
        
        // Read principals data
        MSKPrincipalExtractor.MSKPrincipalsData principalsData = objectMapper.readValue(
            new File(principalsFilePath), MSKPrincipalExtractor.MSKPrincipalsData.class);
        
        logger.info("{} Found {} principals to process", INFO, principalsData.principals.size());
        
        // Track results
        List<ServiceAccountResult> results = new ArrayList<>();
        
        // Process each principal
        for (MSKPrincipalExtractor.PrincipalInfo principal : principalsData.principals) {
            if (shouldCreateServiceAccount(principal)) {
                ServiceAccountResult result = createServiceAccount(principal);
                results.add(result);
            } else {
                logger.info("{} Skipping principal: {} (type: {})", SKIPPED, principal.principal_name, principal.principal_type);
            }
        }
        
        // Create output file with service account details
        createServiceAccountsOutputFile(results, principalsData);
        
        // Print summary
        printSummary(results);
    }
    
    /**
     * Check if we should create a service account for this principal
     */
    private boolean shouldCreateServiceAccount(MSKPrincipalExtractor.PrincipalInfo principal) {
        // Only create service accounts for User principals (not system accounts)
        if (!"User".equals(principal.principal_type)) {
            return false;
        }
        
        // Skip system/internal principals
        String name = principal.principal_name.toLowerCase();
        if (name.startsWith("kafka-") || name.startsWith("__") || name.contains("system")) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Create a service account for a principal
     */
    private ServiceAccountResult createServiceAccount(MSKPrincipalExtractor.PrincipalInfo principal) {
        String serviceAccountName = principal.principal_name;
        String description = String.format("Service account created from MSK principal: %s (ACLs: %d)", 
                                          principal.principal, principal.acl_count);
        
        logger.info("{} Processing service account: {}", PROCESSING, serviceAccountName);
        
        if (dryRun) {
            logger.info("{} Would create service account '{}' with description: {}", 
                       DRY_RUN, serviceAccountName, description);
            return new ServiceAccountResult(serviceAccountName, "dry-run-id", true, "Dry run - not created", 
                                          principal.principal, principal.acl_count, principal.permissions);
        }
        
        try {
            // First, always check if service account already exists using comprehensive search
            ServiceAccountDetails existingAccount = findExistingServiceAccount(serviceAccountName);
            if (existingAccount != null) {
                logger.info("{} Service account '{}' already exists with ID: {}", EXISTS, serviceAccountName, existingAccount.id);
                return new ServiceAccountResult(serviceAccountName, existingAccount.id, true, "Already exists", 
                                              principal.principal, principal.acl_count, principal.permissions, existingAccount);
            }
            
            // If not found, try to create new service account
            ServiceAccountDetails newAccount = createNewServiceAccount(serviceAccountName, description);
            logger.info("{} Created service account '{}' with ID: {}", SUCCESS, serviceAccountName, newAccount.id);
            return new ServiceAccountResult(serviceAccountName, newAccount.id, true, "Created successfully", 
                                          principal.principal, principal.acl_count, principal.permissions, newAccount);
            
        } catch (Exception e) {
            String errorMessage = e.getMessage();
            
            // If creation failed with 409 (already exists), do a more thorough search
            if (errorMessage != null && (errorMessage.contains("409") || errorMessage.toLowerCase().contains("already in use"))) {
                logger.warn("{} Service account '{}' creation failed with 409 error, performing exhaustive search...", 
                           WARNING, serviceAccountName);
                
                try {
                    // Force a comprehensive search with all strategies
                    ServiceAccountDetails foundAccount = performExhaustiveSearch(serviceAccountName);
                    
                    if (foundAccount != null) {
                        logger.info("{} Found service account via exhaustive search: '{}' with ID: {}", 
                                   SUCCESS, foundAccount.display_name, foundAccount.id);
                        return new ServiceAccountResult(serviceAccountName, foundAccount.id, true, 
                                                      "Already exists (found via exhaustive search after 409)", 
                                                      principal.principal, principal.acl_count, principal.permissions, foundAccount);
                    } else {
                        // This should be extremely rare now with comprehensive search
                        logger.error("{} Service account '{}' confirmed to exist (409 error) but could not be found via any API method!", 
                                   ERROR, serviceAccountName);
                        logger.error("{} This may indicate API permissions issues or service account in different environment", ERROR);
                        
                        return new ServiceAccountResult(serviceAccountName, "SEARCH_FAILED_AFTER_409", false, 
                                                      "Already exists (409 confirmed) but exhaustive API search failed - check permissions and environment", 
                                                      principal.principal, principal.acl_count, principal.permissions);
                    }
                } catch (Exception searchException) {
                    logger.error("{} Exhaustive search failed after 409 error: {}", ERROR, searchException.getMessage());
                    return new ServiceAccountResult(serviceAccountName, "SEARCH_ERROR_AFTER_409", false, 
                                                  "Already exists (409 confirmed) but search failed: " + searchException.getMessage(), 
                                                  principal.principal, principal.acl_count, principal.permissions);
                }
            } else {
                // Other types of errors
                logger.error("{} Failed to create service account '{}': {}", ERROR, serviceAccountName, errorMessage);
                if (verbose) {
                    logger.error("Full error:", e);
                }
                return new ServiceAccountResult(serviceAccountName, null, false, errorMessage, 
                                              principal.principal, principal.acl_count, principal.permissions);
            }
        }
    }
    
    /**
     * Find existing service account by name using comprehensive API search
     */
    private ServiceAccountDetails findExistingServiceAccount(String name) throws IOException {
        logger.debug("{} Searching for existing service account: '{}'", DEBUG, name);
        
        // Try multiple search strategies to ensure we find the service account
        ServiceAccountDetails result = null;
        
        // Strategy 1: Standard API call
        result = searchServiceAccountsStandard(name);
        if (result != null) {
            logger.info("{} Found service account via standard search: '{}' (ID: {})", SUCCESS, result.display_name, result.id);
            return result;
        }
        
        // Strategy 2: Paginated search with different page sizes
        int[] pageSizes = {10, 25, 50, 100, 200};
        for (int pageSize : pageSizes) {
            result = searchServiceAccountsPaginated(name, pageSize);
            if (result != null) {
                logger.info("{} Found service account via paginated search (page_size={}): '{}' (ID: {})", 
                           SUCCESS, pageSize, result.display_name, result.id);
                return result;
            }
        }
        
        // Strategy 3: Case-insensitive search
        result = searchServiceAccountsCaseInsensitive(name);
        if (result != null) {
            logger.info("{} Found service account via case-insensitive search: '{}' (ID: {})", 
                       SUCCESS, result.display_name, result.id);
            return result;
        }
        
        logger.debug("{} Service account '{}' not found via API search", DEBUG, name);
        return null;
    }
    
    /**
     * Standard service account search using display_name parameter
     */
    private ServiceAccountDetails searchServiceAccountsStandard(String name) throws IOException {
        // Use the display_name parameter for direct filtering
        String url = baseUrl + "/iam/v2/service-accounts?display_name=" + java.net.URLEncoder.encode(name, "UTF-8");
        
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(apiKey, apiSecret))
                .header("Content-Type", "application/json")
                .get()
                .build();
        
        if (verbose) {
            logger.debug("GET {} (using display_name parameter)", url);
        }
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.warn("{} Standard search failed: {} {}", WARNING, response.code(), response.message());
                return null;
            }
            
            String responseBody = response.body().string();
            if (verbose) {
                logger.debug("Standard search response: {}", responseBody);
            }
            
            return parseServiceAccountResponse(responseBody, name, true); // exact match
        }
    }
    
    /**
     * Paginated service account search using display_name parameter
     */
    private ServiceAccountDetails searchServiceAccountsPaginated(String name, int pageSize) {
        try {
            // Use display_name parameter with page_size for direct filtering
            String url = baseUrl + "/iam/v2/service-accounts?display_name=" + java.net.URLEncoder.encode(name, "UTF-8") + "&page_size=" + pageSize;
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", Credentials.basic(apiKey, apiSecret))
                    .header("Content-Type", "application/json")
                    .get()
                    .build();
            
            if (verbose) {
                logger.debug("GET {} (paginated with display_name, page_size={})", url, pageSize);
            }
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.debug("{} Paginated search failed (page_size={}): {}", DEBUG, pageSize, response.code());
                    return null;
                }
                
                String responseBody = response.body().string();
                if (verbose) {
                    logger.debug("Paginated search response (page_size={}): {}", pageSize, responseBody);
                }
                
                return parseServiceAccountResponse(responseBody, name, true); // exact match
            }
        } catch (Exception e) {
            logger.debug("{} Paginated search error (page_size={}): {}", DEBUG, pageSize, e.getMessage());
            return null;
        }
    }
    
    /**
     * Case-insensitive service account search
     */
    private ServiceAccountDetails searchServiceAccountsCaseInsensitive(String name) {
        try {
            String url = baseUrl + "/iam/v2/service-accounts?page_size=100";
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", Credentials.basic(apiKey, apiSecret))
                    .header("Content-Type", "application/json")
                    .get()
                    .build();
            
            if (verbose) {
                logger.debug("GET {} (case-insensitive search)", url);
            }
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.debug("{} Case-insensitive search failed: {}", DEBUG, response.code());
                    return null;
                }
                
                String responseBody = response.body().string();
                if (verbose) {
                    logger.debug("Case-insensitive search response: {}", responseBody);
                }
                
                return parseServiceAccountResponse(responseBody, name, false); // case-insensitive match
            }
        } catch (Exception e) {
            logger.debug("{} Case-insensitive search error: {}", DEBUG, e.getMessage());
            return null;
        }
    }
    
    /**
     * Parse service account response and find matching account
     */
    private ServiceAccountDetails parseServiceAccountResponse(String responseBody, String targetName, boolean exactMatch) throws IOException {
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        List<Map<String, Object>> serviceAccounts = (List<Map<String, Object>>) responseMap.get("data");
        
        if (serviceAccounts != null && !serviceAccounts.isEmpty()) {
            logger.debug("{} Parsing {} service accounts from API response", DEBUG, serviceAccounts.size());
            
            for (Map<String, Object> sa : serviceAccounts) {
                String displayName = (String) sa.get("display_name");
                String id = (String) sa.get("id");
                
                if (displayName != null) {
                    boolean matches = exactMatch ? 
                        targetName.equals(displayName) : 
                        targetName.equalsIgnoreCase(displayName);
                    
                    if (matches) {
                        logger.debug("{} Found matching service account: '{}' (ID: {})", SUCCESS, displayName, id);
                        return new ServiceAccountDetails(
                            id,
                            displayName,
                            (String) sa.get("description"),
                            (String) sa.get("created_at"),
                            (String) sa.get("updated_at")
                        );
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * List all service accounts for debugging purposes when we can't find a specific one
     */
    private ServiceAccountDetails listAllServiceAccountsForDebugging(String targetName) {
        try {
            logger.info("{} Listing all service accounts for debugging (looking for '{}')", DEBUG, targetName);
            
            String url = baseUrl + "/iam/v2/service-accounts";
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", Credentials.basic(apiKey, apiSecret))
                    .header("Content-Type", "application/json")
                    .get()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("{} Could not list service accounts for debugging: {}", WARNING, response.code());
                    return null;
                }
                
                String responseBody = response.body().string();
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
                List<Map<String, Object>> serviceAccounts = (List<Map<String, Object>>) responseMap.get("data");
                
                if (serviceAccounts != null && !serviceAccounts.isEmpty()) {
                    logger.info("{} Found {} service accounts:", DEBUG, serviceAccounts.size());
                    ServiceAccountDetails potentialMatch = null;
                    
                    for (Map<String, Object> sa : serviceAccounts) {
                        String displayName = (String) sa.get("display_name");
                        String id = (String) sa.get("id");
                        String description = (String) sa.get("description");
                        
                        // Show full details for debugging
                        logger.info("  {} Name: '{}' | ID: {} | Description: {}", 
                                   DEBUG, displayName, id, description != null ? description : "N/A");
                        
                        // Check for exact match (case insensitive)
                        if (displayName != null && displayName.equalsIgnoreCase(targetName)) {
                            logger.info("    {} EXACT MATCH FOUND!", SUCCESS);
                            potentialMatch = new ServiceAccountDetails(id, displayName, description, 
                                                                     (String) sa.get("created_at"), 
                                                                     (String) sa.get("updated_at"));
                        }
                        // Check for partial matches
                        else if (displayName != null && 
                                (displayName.toLowerCase().contains(targetName.toLowerCase()) ||
                                 targetName.toLowerCase().contains(displayName.toLowerCase()))) {
                            logger.info("    {} PARTIAL MATCH found!", WARNING);
                            if (potentialMatch == null) {
                                potentialMatch = new ServiceAccountDetails(id, displayName, description, 
                                                                         (String) sa.get("created_at"), 
                                                                         (String) sa.get("updated_at"));
                            }
                        }
                    }
                    
                    // If we found a potential match, try to use it
                    if (potentialMatch != null) {
                        logger.info("{} Using potential match: '{}' (ID: {})", SUCCESS, potentialMatch.display_name, potentialMatch.id);
                        return potentialMatch;
                    }
                } else {
                    logger.info("{} No service accounts found", DEBUG);
                }
            }
        } catch (Exception e) {
            logger.warn("{} Failed to list service accounts for debugging: {}", WARNING, e.getMessage());
        }
        return null;
    }
    
    /**
     * Try to find service account by name using direct search with pagination
     */
    private ServiceAccountDetails findServiceAccountByNameDirect(String name) {
        try {
            logger.info("{} Attempting direct service account lookup using display_name parameter for: '{}'", DEBUG, name);
            
            // Use display_name parameter for direct filtering
            String url = baseUrl + "/iam/v2/service-accounts?display_name=" + java.net.URLEncoder.encode(name, "UTF-8");
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", Credentials.basic(apiKey, apiSecret))
                    .header("Content-Type", "application/json")
                    .get()
                    .build();
            
            if (verbose) {
                logger.debug("GET {} (using display_name parameter)", url);
            }
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("{} Direct lookup failed: {} {}", WARNING, response.code(), response.message());
                    return null;
                }
                
                String responseBody = response.body().string();
                if (verbose) {
                    logger.debug("Direct lookup response: {}", responseBody);
                }
                
                // Parse response to find matching service account
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
                List<Map<String, Object>> serviceAccounts = (List<Map<String, Object>>) responseMap.get("data");
                
                if (serviceAccounts != null) {
                    logger.info("{} Direct lookup found {} service accounts", DEBUG, serviceAccounts.size());
                    for (Map<String, Object> sa : serviceAccounts) {
                        String displayName = (String) sa.get("display_name");
                        String id = (String) sa.get("id");
                        
                        if (verbose) {
                            logger.debug("  {} Checking: '{}' (ID: {})", DEBUG, displayName, id);
                        }
                        
                        if (name.equals(displayName)) {
                            logger.info("{} Direct lookup found exact match: '{}' (ID: {})", SUCCESS, displayName, id);
                            return new ServiceAccountDetails(
                                id,
                                displayName,
                                (String) sa.get("description"),
                                (String) sa.get("created_at"),
                                (String) sa.get("updated_at")
                            );
                        }
                    }
                }
                
                logger.warn("{} Direct lookup completed but no exact match found for '{}'", WARNING, name);
                return null;
                
            }
        } catch (Exception e) {
            logger.warn("{} Direct service account lookup failed for '{}': {}", WARNING, name, e.getMessage());
            if (verbose) {
                logger.debug("Direct lookup error:", e);
            }
            return null;
        }
    }
    
    /**
     * Enhanced search for existing service account using multiple strategies
     */
    private ServiceAccountDetails findServiceAccountEnhanced(String name) {
        logger.info("{} Enhanced search for service account: '{}'", DEBUG, name);
        
        // Strategy 1: Try different page sizes
        int[] pageSizes = {10, 50, 100, 200};
        for (int pageSize : pageSizes) {
            ServiceAccountDetails result = searchWithPageSize(name, pageSize);
            if (result != null) {
                logger.info("{} Found service account using page size {}: '{}' (ID: {})", 
                           SUCCESS, pageSize, result.display_name, result.id);
                return result;
            }
        }
        
        // Strategy 2: Try with different API endpoints or parameters
        ServiceAccountDetails result = searchWithAlternativeMethod(name);
        if (result != null) {
            logger.info("{} Found service account using alternative method: '{}' (ID: {})", 
                       SUCCESS, result.display_name, result.id);
            return result;
        }
        
        logger.warn("{} Enhanced search completed but service account '{}' not found via API", WARNING, name);
        return null;
    }
    
    /**
     * Search with specific page size using display_name parameter
     */
    private ServiceAccountDetails searchWithPageSize(String name, int pageSize) {
        try {
            // Use display_name parameter with page_size for direct filtering
            String url = baseUrl + "/iam/v2/service-accounts?display_name=" + java.net.URLEncoder.encode(name, "UTF-8") + "&page_size=" + pageSize;
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", Credentials.basic(apiKey, apiSecret))
                    .header("Content-Type", "application/json")
                    .get()
                    .build();
            
            if (verbose) {
                logger.debug("GET {} (display_name with page_size={})", url, pageSize);
            }
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.debug("{} Search with page size {} failed: {}", DEBUG, pageSize, response.code());
                    return null;
                }
                
                String responseBody = response.body().string();
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
                List<Map<String, Object>> serviceAccounts = (List<Map<String, Object>>) responseMap.get("data");
                
                if (serviceAccounts != null) {
                    logger.debug("{} Page size {} returned {} service accounts", DEBUG, pageSize, serviceAccounts.size());
                    
                    for (Map<String, Object> sa : serviceAccounts) {
                        String displayName = (String) sa.get("display_name");
                        String id = (String) sa.get("id");
                        
                        // Try exact match (case sensitive)
                        if (name.equals(displayName)) {
                            return new ServiceAccountDetails(
                                id, displayName, (String) sa.get("description"),
                                (String) sa.get("created_at"), (String) sa.get("updated_at")
                            );
                        }
                        
                        // Try case insensitive match
                        if (name.equalsIgnoreCase(displayName)) {
                            logger.info("{} Found case-insensitive match: '{}' vs '{}'", DEBUG, name, displayName);
                            return new ServiceAccountDetails(
                                id, displayName, (String) sa.get("description"),
                                (String) sa.get("created_at"), (String) sa.get("updated_at")
                            );
                        }
                    }
                }
                
                return null;
            }
        } catch (Exception e) {
            logger.debug("{} Search with page size {} failed: {}", DEBUG, pageSize, e.getMessage());
            return null;
        }
    }
    
    /**
     * Alternative search method using display_name parameter
     */
    private ServiceAccountDetails searchWithAlternativeMethod(String name) {
        try {
            // Use display_name parameter without pagination
            String url = baseUrl + "/iam/v2/service-accounts?display_name=" + java.net.URLEncoder.encode(name, "UTF-8");
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", Credentials.basic(apiKey, apiSecret))
                    .header("Content-Type", "application/json")
                    .get()
                    .build();
            
            if (verbose) {
                logger.debug("GET {} (display_name without pagination)", url);
            }
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }
                
                String responseBody = response.body().string();
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
                List<Map<String, Object>> serviceAccounts = (List<Map<String, Object>>) responseMap.get("data");
                
                if (serviceAccounts != null) {
                    logger.debug("{} Alternative method returned {} service accounts", DEBUG, serviceAccounts.size());
                    
                    for (Map<String, Object> sa : serviceAccounts) {
                        String displayName = (String) sa.get("display_name");
                        String id = (String) sa.get("id");
                        
                        if (name.equals(displayName) || name.equalsIgnoreCase(displayName)) {
                            return new ServiceAccountDetails(
                                id, displayName, (String) sa.get("description"),
                                (String) sa.get("created_at"), (String) sa.get("updated_at")
                            );
                        }
                    }
                }
                
                return null;
            }
        } catch (Exception e) {
            logger.debug("{} Alternative search method failed: {}", DEBUG, e.getMessage());
            return null;
        }
    }
    
    /**
     * Create new service account
     */
    private ServiceAccountDetails createNewServiceAccount(String name, String description) throws IOException {
        String url = baseUrl + "/iam/v2/service-accounts";
        
        // Create request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("display_name", name);
        requestBody.put("description", description);
        
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(apiKey, apiSecret))
                .header("Content-Type", "application/json")
                .post(body)
                .build();
        
        if (verbose) {
            logger.debug("POST {} with body: {}", url, jsonBody);
        }
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            
            if (verbose) {
                logger.debug("Response: {} {}", response.code(), responseBody);
            }
            
            if (!response.isSuccessful()) {
                throw new IOException("Failed to create service account: " + response.code() + " " + responseBody);
            }
            
            // Parse response to get service account details
            Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
            return new ServiceAccountDetails(
                (String) responseMap.get("id"),
                (String) responseMap.get("display_name"),
                (String) responseMap.get("description"),
                (String) responseMap.get("created_at"),
                (String) responseMap.get("updated_at")
            );
        }
    }
    
    /**
     * Create output file with service account details
     */
    private void createServiceAccountsOutputFile(List<ServiceAccountResult> results, MSKPrincipalExtractor.MSKPrincipalsData principalsData) {
        try {
            ServiceAccountsOutput output = new ServiceAccountsOutput();
            output.metadata = new ServiceAccountsMetadata();
            output.metadata.created_at = LocalDateTime.now().toString();
            output.metadata.source_file = "msk_principals.json";
            output.metadata.total_principals_processed = results.size();
            output.metadata.source_cluster = principalsData.cluster_metadata;
            
            // Convert results to output format
            for (ServiceAccountResult result : results) {
                ServiceAccountOutputInfo info = new ServiceAccountOutputInfo();
                info.name = result.name;
                info.id = result.id;
                info.account_id = result.id; // Account ID is the same as the service account ID
                
                // Handle special ID values for existing service accounts
                if (result.id != null) {
                    if (result.id.equals("dry-run-id")) {
                        info.resource_id = "dry-run-resource-id";
                        info.status = "dry-run";
                    } else if (result.id.equals("EXISTING_BUT_ID_UNKNOWN")) {
                        info.resource_id = null; // No ID available
                        info.status = "existing";
                        info.id = null; // Don't show the placeholder ID in output
                        info.account_id = null;
                    } else if (result.id.equals("EXISTING_BUT_LOOKUP_FAILED")) {
                        info.resource_id = null; // No ID available
                        info.status = "existing";
                        info.id = null; // Don't show the placeholder ID in output
                        info.account_id = null;
                    } else {
                        // Normal service account ID (e.g., sa-abc123)
                        info.resource_id = result.id;
                        info.status = result.success ? 
                            (result.message.contains("Already exists") ? "existing" : "created") : "failed";
                    }
                } else {
                    info.resource_id = null;
                    info.status = result.success ? "created" : "failed";
                }
                
                info.message = result.message;
                info.original_principal = result.originalPrincipal;
                info.acl_count = result.aclCount;
                info.permissions = result.permissions;
                
                if (result.accountDetails != null) {
                    info.details = result.accountDetails;
                }
                
                output.service_accounts.add(info);
                
                // Update metadata counters
                switch (info.status) {
                    case "created":
                        output.metadata.created_count++;
                        break;
                    case "existing":
                        output.metadata.existing_count++;
                        break;
                    case "failed":
                        output.metadata.failed_count++;
                        break;
                    case "dry-run":
                        // Don't count dry-run in any category
                        break;
                }
            }
            
            // Write to file
            String outputPath = "generated_jsons/cc_jsons/cc_service_accounts.json";
            File outputFile = new File(outputPath);
            outputFile.getParentFile().mkdirs();
            
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, output);
            logger.info("{} Service accounts details written to: {}", SUCCESS, outputPath);
            
        } catch (IOException e) {
            logger.error("{} Failed to create service accounts output file: {}", ERROR, e.getMessage());
        }
    }
    
    /**
     * Print creation summary
     */
    private void printSummary(List<ServiceAccountResult> results) {
        logger.info("");
        logger.info("{} Service Account Creation Summary", SUMMARY);
        logger.info("=================================");
        
        int successful = 0;
        int failed = 0;
        int existing = 0;
        
        for (ServiceAccountResult result : results) {
            String displayId = result.id;
            String statusIcon;
            
            if (result.success) {
                if (result.message.contains("Already exists")) {
                    existing++;
                    statusIcon = EXISTS;
                    // Handle special ID cases for display
                    if ("EXISTING_BUT_ID_UNKNOWN".equals(result.id)) {
                        displayId = "ID_UNKNOWN";
                    } else if ("EXISTING_BUT_LOOKUP_FAILED".equals(result.id)) {
                        displayId = "LOOKUP_FAILED";
                    }
                } else {
                    successful++;
                    statusIcon = SUCCESS;
                }
            } else {
                failed++;
                statusIcon = ERROR;
            }
            
            logger.info("{} {}: {} ({})", statusIcon, result.name, 
                       displayId != null ? displayId : "N/A", result.message);
        }
        
        logger.info("");
        logger.info("{} Total processed: {}", STATS, results.size());
        logger.info("{} Successfully created: {}", SUCCESS, successful);
        logger.info("{} Already existing: {}", EXISTS, existing);
        logger.info("{} Failed: {}", ERROR, failed);
        
        if (dryRun) {
            logger.info("");
            logger.info("{} This was a dry run - no service accounts were actually created", DRY_RUN);
        } else {
            logger.info("");
            logger.info("{} Service account details saved to: generated_jsons/cc_jsons/cc_service_accounts.json", REPORT);
        }
    }
    
    /**
     * Service Account Details
     */
    public static class ServiceAccountDetails {
        public String id;
        public String display_name;
        public String description;
        public String created_at;
        public String updated_at;
        
        public ServiceAccountDetails() {}
        
        public ServiceAccountDetails(String id, String displayName, String description, String createdAt, String updatedAt) {
            this.id = id;
            this.display_name = displayName;
            this.description = description;
            this.created_at = createdAt;
            this.updated_at = updatedAt;
        }
    }
    
    /**
     * Service Account Result
     */
    public static class ServiceAccountResult {
        public final String name;
        public final String id;
        public final boolean success;
        public final String message;
        public final String originalPrincipal;
        public final int aclCount;
        public final List<String> permissions;
        public final ServiceAccountDetails accountDetails;
        
        public ServiceAccountResult(String name, String id, boolean success, String message, 
                                  String originalPrincipal, int aclCount, List<String> permissions) {
            this(name, id, success, message, originalPrincipal, aclCount, permissions, null);
        }
        
        public ServiceAccountResult(String name, String id, boolean success, String message, 
                                  String originalPrincipal, int aclCount, List<String> permissions,
                                  ServiceAccountDetails accountDetails) {
            this.name = name;
            this.id = id;
            this.success = success;
            this.message = message;
            this.originalPrincipal = originalPrincipal;
            this.aclCount = aclCount;
            this.permissions = permissions;
            this.accountDetails = accountDetails;
        }
    }
    
    /**
     * Output file structure
     */
    public static class ServiceAccountsOutput {
        public ServiceAccountsMetadata metadata = new ServiceAccountsMetadata();
        public List<ServiceAccountOutputInfo> service_accounts = new ArrayList<>();
    }
    
    public static class ServiceAccountsMetadata {
        public String created_at;
        public String source_file;
        public int total_principals_processed;
        public int created_count = 0;
        public int existing_count = 0;
        public int failed_count = 0;
        public MSKPrincipalExtractor.MSKClusterMetadata source_cluster;
    }
    
    public static class ServiceAccountOutputInfo {
        public String name;
        public String id;
        public String account_id;    // Account ID (same as id for service accounts)
        public String resource_id;   // Resource ID (service account ID, e.g., sa-abc123)
        public String status; // "created", "existing", "failed"
        public String message;
        public String original_principal;
        public int acl_count;
        public List<String> permissions;
        public ServiceAccountDetails details;
    }
    
    /**
     * Main method for command-line usage
     */
    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            printHelp();
            System.exit(0);
        }
        
        // Parse flags first
        boolean dryRun = Arrays.asList(args).contains("--dry-run");
        boolean verbose = Arrays.asList(args).contains("--verbose");
        
        // Parse positional arguments (skip flags)
        List<String> positionalArgs = new ArrayList<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                positionalArgs.add(arg);
            }
        }
        
        String principalsFile = positionalArgs.size() > 0 ? positionalArgs.get(0) : "generated_jsons/msk_jsons/msk_principals.json";
        String configFile = positionalArgs.size() > 1 ? positionalArgs.get(1) : "ccloud.config";
        
        try {
            // Load Confluent Cloud configuration
            ConfluentCloudRBACApplicator.ConfluentCloudConfig config = 
                ConfluentCloudRBACApplicator.readConfig(configFile);
            
            // Create service account creator
            ConfluentCloudServiceAccountCreator creator = new ConfluentCloudServiceAccountCreator(config, dryRun, verbose);
            
            // Create service accounts
            creator.createServiceAccountsFromFile(principalsFile);
            
            System.out.println("🎉 Service account creation completed!");
            
        } catch (Exception e) {
            logger.error("Service account creation failed: {}", e.getMessage(), e);
            System.err.println("❌ Service account creation failed: " + e.getMessage());
            System.exit(1);
        }
    }
    
    private static void printHelp() {
        System.out.println("Confluent Cloud Service Account Creator");
        System.out.println("Usage: java ConfluentCloudServiceAccountCreator [principals_file] [config_file] [options]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  principals_file  MSK principals JSON file (default: generated_jsons/msk_jsons/msk_principals.json)");
        System.out.println("  config_file      Confluent Cloud config file (default: ccloud.config)");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dry-run        Show what would be created without actually creating");
        System.out.println("  --verbose        Enable verbose logging");
        System.out.println("  --help, -h       Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java ConfluentCloudServiceAccountCreator");
        System.out.println("  java ConfluentCloudServiceAccountCreator --dry-run");
        System.out.println("  java ConfluentCloudServiceAccountCreator my_principals.json my_config.properties --verbose");
        System.out.println();
        System.out.println("The tool will:");
        System.out.println("1. Read principals from the JSON file");
        System.out.println("2. Filter out system/internal principals");
        System.out.println("3. Create service accounts in Confluent Cloud");
        System.out.println("4. Skip existing service accounts");
        System.out.println("5. Create cc_service_accounts.json with all details");
        System.out.println("6. Report creation results with visual status icons");
    }
    
    /**
     * Perform exhaustive search using all available methods
     */
    private ServiceAccountDetails performExhaustiveSearch(String serviceAccountName) {
        logger.info("{} Performing exhaustive search for service account: '{}'", PROCESSING, serviceAccountName);
        
        try {
            // Try the enhanced search first
            ServiceAccountDetails result = findServiceAccountEnhanced(serviceAccountName);
            if (result != null) {
                return result;
            }
            
            // Try the debugging search (lists all accounts)
            result = listAllServiceAccountsForDebugging(serviceAccountName);
            if (result != null) {
                return result;
            }
            
            // Try direct lookup
            result = findServiceAccountByNameDirect(serviceAccountName);
            if (result != null) {
                return result;
            }
            
            logger.warn("{} Exhaustive search completed but service account '{}' not found", WARNING, serviceAccountName);
            return null;
            
        } catch (Exception e) {
            logger.error("{} Exhaustive search failed: {}", ERROR, e.getMessage());
            return null;
        }
    }
}
