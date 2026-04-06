package com.amazonaws.policyconverters.lakeformation.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.jqwik.api.*;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for StructuredErrorLogger.
 * Uses jqwik to verify error log structure across randomized inputs.
 */
class StructuredErrorLoggerPropertyTest {

    // -----------------------------------------------------------------------
    // Feature: ranger-lakeformation-sync, Property 20: Error log structure
    // **Validates: Requirements 8.5**
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    void errorLogContainsComponentNameSeverityAndContextFields(
            @ForAll("componentNames") String componentName,
            @ForAll("messages") String message,
            @ForAll("optionalStrings") String policyId,
            @ForAll("optionalStrings") String resourcePath,
            @ForAll("optionalStrings") String principal
    ) {
        // Get the logback Logger for this component and attach a ListAppender
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(componentName);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);

        try {
            StructuredErrorLogger errorLogger = StructuredErrorLogger.forComponent(componentName);
            errorLogger.error(message, policyId, resourcePath, principal);

            // Exactly one log entry should be produced
            assertEquals(1, appender.list.size(), "Should produce exactly one log entry");

            ILoggingEvent event = appender.list.get(0);

            // Severity must be ERROR
            assertEquals(ch.qos.logback.classic.Level.ERROR, event.getLevel(),
                    "Log entry must have ERROR severity");

            // Logger name must match component name
            assertEquals(componentName, event.getLoggerName(),
                    "Logger name must match component name");

            // Timestamp must be present (non-zero)
            assertTrue(event.getTimeStamp() > 0, "Log entry must have a timestamp");

            String formatted = event.getFormattedMessage();

            // Component name must appear in the formatted message
            assertTrue(formatted.contains("[" + componentName + "]"),
                    "Formatted message must contain component name: " + componentName);

            // Message must appear
            assertTrue(formatted.contains(message),
                    "Formatted message must contain the error message");

            // Non-null context fields must appear
            if (policyId != null) {
                assertTrue(formatted.contains("policyId=" + policyId),
                        "Formatted message must contain policyId when provided");
            }
            if (resourcePath != null) {
                assertTrue(formatted.contains("resource=" + resourcePath),
                        "Formatted message must contain resource when provided");
            }
            if (principal != null) {
                assertTrue(formatted.contains("principal=" + principal),
                        "Formatted message must contain principal when provided");
            }
        } finally {
            logbackLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Property(tries = 100)
    void warnLogContainsComponentNameAndContextFields(
            @ForAll("componentNames") String componentName,
            @ForAll("messages") String message,
            @ForAll("optionalStrings") String policyId,
            @ForAll("optionalStrings") String resourcePath,
            @ForAll("optionalStrings") String principal
    ) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(componentName);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);

        try {
            StructuredErrorLogger errorLogger = StructuredErrorLogger.forComponent(componentName);
            errorLogger.warn(message, policyId, resourcePath, principal);

            assertEquals(1, appender.list.size());

            ILoggingEvent event = appender.list.get(0);
            assertEquals(ch.qos.logback.classic.Level.WARN, event.getLevel());
            assertEquals(componentName, event.getLoggerName());
            assertTrue(event.getTimeStamp() > 0);

            String formatted = event.getFormattedMessage();
            assertTrue(formatted.contains("[" + componentName + "]"));
            assertTrue(formatted.contains(message));

            if (policyId != null) {
                assertTrue(formatted.contains("policyId=" + policyId));
            }
            if (resourcePath != null) {
                assertTrue(formatted.contains("resource=" + resourcePath));
            }
            if (principal != null) {
                assertTrue(formatted.contains("principal=" + principal));
            }
        } finally {
            logbackLogger.detachAppender(appender);
            appender.stop();
        }
    }

    // --- Generators ---

    @Provide
    Arbitrary<String> componentNames() {
        return Arbitraries.of(
                "PolicyConverter", "SyncService", "LakeFormationClient",
                "CatalogResolver", "PrincipalMapper", "GapReporter",
                "ConfigLoader", "BulkExtractor"
        );
    }

    @Provide
    Arbitrary<String> messages() {
        return Arbitraries.of(
                "Conversion failed", "Retry exhausted", "Connection timeout",
                "Invalid policy structure", "Permission denied",
                "Catalog resolution error", "Unmapped principal encountered"
        );
    }

    @Provide
    Arbitrary<String> optionalStrings() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of("42", "99", "policy-123"),
                Arbitraries.of("db1/table1", "analytics/events", "hr/employees/col1"),
                Arbitraries.of("arn:aws:iam::123:user/alice", "arn:aws:iam::456:role/Admin")
        );
    }
}
