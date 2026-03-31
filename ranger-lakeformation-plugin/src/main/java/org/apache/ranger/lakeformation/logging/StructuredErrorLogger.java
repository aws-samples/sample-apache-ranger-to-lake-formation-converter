package org.apache.ranger.lakeformation.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured error logging utility that ensures consistent error log format
 * across all components. Every error log entry includes the component name
 * and sufficient context (policy ID, resource path, principal) to diagnose
 * the issue. Timestamp and severity level are provided by the Logback
 * configuration pattern.
 * <p>
 * Usage:
 * <pre>
 *   StructuredErrorLogger logger = StructuredErrorLogger.forComponent("PolicyConverter");
 *   logger.error("Conversion failed", "42", "db1/table1", "arn:aws:iam::123:role/Analyst", exception);
 * </pre>
 */
public class StructuredErrorLogger {

    private final Logger logger;
    private final String componentName;

    private StructuredErrorLogger(String componentName, Logger logger) {
        this.componentName = componentName;
        this.logger = logger;
    }

    /**
     * Create a StructuredErrorLogger for the given component name.
     * The underlying SLF4J logger is obtained from the component name.
     *
     * @param componentName the logical component name (e.g. "PolicyConverter", "SyncService")
     * @return a new StructuredErrorLogger instance
     */
    public static StructuredErrorLogger forComponent(String componentName) {
        return new StructuredErrorLogger(componentName, LoggerFactory.getLogger(componentName));
    }

    /**
     * Create a StructuredErrorLogger for the given component name using a
     * provided SLF4J Logger. Useful for testing.
     *
     * @param componentName the logical component name
     * @param logger        the SLF4J logger to delegate to
     * @return a new StructuredErrorLogger instance
     */
    public static StructuredErrorLogger forComponent(String componentName, Logger logger) {
        return new StructuredErrorLogger(componentName, logger);
    }

    /**
     * Returns the component name associated with this logger.
     */
    public String getComponentName() {
        return componentName;
    }

    /**
     * Log an error with full context: policy ID, resource path, and principal.
     *
     * @param message      the error message
     * @param policyId     the Ranger policy ID (may be null)
     * @param resourcePath the resource path (may be null)
     * @param principal    the principal ARN or name (may be null)
     * @param cause        the exception that caused the error (may be null)
     */
    public void error(String message, String policyId, String resourcePath, String principal, Throwable cause) {
        String formatted = formatMessage(message, policyId, resourcePath, principal);
        if (cause != null) {
            logger.error(formatted, cause);
        } else {
            logger.error(formatted);
        }
    }

    /**
     * Log an error with full context but no exception.
     *
     * @param message      the error message
     * @param policyId     the Ranger policy ID (may be null)
     * @param resourcePath the resource path (may be null)
     * @param principal    the principal ARN or name (may be null)
     */
    public void error(String message, String policyId, String resourcePath, String principal) {
        error(message, policyId, resourcePath, principal, null);
    }

    /**
     * Log an error with only a policy ID for context.
     *
     * @param message  the error message
     * @param policyId the Ranger policy ID (may be null)
     * @param cause    the exception that caused the error (may be null)
     */
    public void error(String message, String policyId, Throwable cause) {
        error(message, policyId, null, null, cause);
    }

    /**
     * Log an error with only a message (no additional context fields).
     *
     * @param message the error message
     * @param cause   the exception that caused the error (may be null)
     */
    public void error(String message, Throwable cause) {
        error(message, null, null, null, cause);
    }

    /**
     * Log a warning with full context: policy ID, resource path, and principal.
     *
     * @param message      the warning message
     * @param policyId     the Ranger policy ID (may be null)
     * @param resourcePath the resource path (may be null)
     * @param principal    the principal ARN or name (may be null)
     */
    public void warn(String message, String policyId, String resourcePath, String principal) {
        logger.warn(formatMessage(message, policyId, resourcePath, principal));
    }

    /**
     * Format a structured log message with component name and context fields.
     * Only non-null context fields are included.
     * <p>
     * Format: {@code [component] message [policyId=X, resource=Y, principal=Z]}
     */
    String formatMessage(String message, String policyId, String resourcePath, String principal) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(componentName).append("] ");
        sb.append(message != null ? message : "");

        boolean hasContext = policyId != null || resourcePath != null || principal != null;
        if (hasContext) {
            sb.append(" [");
            boolean first = true;
            if (policyId != null) {
                sb.append("policyId=").append(policyId);
                first = false;
            }
            if (resourcePath != null) {
                if (!first) sb.append(", ");
                sb.append("resource=").append(resourcePath);
                first = false;
            }
            if (principal != null) {
                if (!first) sb.append(", ");
                sb.append("principal=").append(principal);
            }
            sb.append("]");
        }

        return sb.toString();
    }
}
