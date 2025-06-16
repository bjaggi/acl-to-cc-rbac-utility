package com.confluent.utility;

/**
 * Utility class providing consistent visual status icons for logging
 */
public class StatusIcons {
    
    // Success indicators
    public static final String SUCCESS = "✅";
    public static final String COMPLETED = "🎉";
    public static final String CHECK = "✓";
    
    // Error indicators  
    public static final String ERROR = "❌";
    public static final String FAILED = "✗";
    public static final String CROSS = "❌";
    
    // Warning indicators
    public static final String WARNING = "⚠️";
    public static final String CAUTION = "⚠️";
    public static final String ALERT = "🔔";
    
    // Info indicators
    public static final String INFO = "ℹ️";
    public static final String ARROW = "➤";
    public static final String BULLET = "•";
    public static final String DEBUG = "🔍";
    
    // Process indicators
    public static final String PROCESSING = "⏳";
    public static final String LOADING = "🔄";
    public static final String GEAR = "⚙️";
    
    // Status indicators
    public static final String CREATED = "🆕";
    public static final String SKIPPED = "⏭️";
    public static final String EXISTS = "📋";
    public static final String DRY_RUN = "🔍";
    
    // Resource indicators
    public static final String TOPIC = "📝";
    public static final String SERVICE_ACCOUNT = "👤";
    public static final String ROLE_BINDING = "🔐";
    public static final String CONFIG = "⚙️";
    
    // Summary indicators
    public static final String SUMMARY = "📊";
    public static final String STATS = "📈";
    public static final String REPORT = "📋";
    
    /**
     * Get status icon based on operation result
     */
    public static String getStatusIcon(String status) {
        switch (status.toLowerCase()) {
            case "success":
            case "created":
            case "completed":
                return SUCCESS;
            case "error":
            case "failed":
                return ERROR;
            case "warning":
            case "warn":
            case "skipped":
                return WARNING;
            case "info":
            case "processing":
                return INFO;
            case "dry-run":
            case "dry_run":
                return DRY_RUN;
            case "exists":
            case "existing":
                return EXISTS;
            default:
                return BULLET;
        }
    }
    
    /**
     * Format a status message with appropriate icon
     */
    public static String formatStatus(String status, String message) {
        return String.format("%s %s", getStatusIcon(status), message);
    }
    
    /**
     * Format a summary line with count and icon
     */
    public static String formatSummaryLine(String operation, int count, String status) {
        return String.format("%s %s: %d", getStatusIcon(status), operation, count);
    }
} 