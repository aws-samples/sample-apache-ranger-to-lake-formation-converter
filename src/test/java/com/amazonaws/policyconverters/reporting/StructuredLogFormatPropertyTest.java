package com.amazonaws.policyconverters.reporting;

// Feature: conversion-server, Property 4: Structured JSON log format

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.contrib.jackson.JacksonJsonFormatter;
import ch.qos.logback.contrib.json.classic.JsonLayout;
import ch.qos.logback.core.OutputStreamAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test verifying that the Logback JsonLayout produces valid
 * structured JSON containing the required fields: timestamp, level, logger,
 * message, and thread. Also verifies that timestamp is in ISO-8601 format.
 * <p>
 * Uses a programmatically configured OutputStreamAppender with JsonLayout
 * and JacksonJsonFormatter to bypass logback-test.xml.
 * <p>
 * **Validates: Requirements 4.1**
 */
class StructuredLogFormatPropertyTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ISO-8601 pattern: yyyy-MM-dd'T'HH:mm:ss.SSSZ (e.g. 2025-01-15T10:30:00.123+0000)
    private static final String ISO_8601_PATTERN =
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}";

    @Property(tries = 100)
    void logOutputIsValidJsonWithRequiredFields(
            @ForAll("logMessages") String message,
            @ForAll("logLevels") Level level
    ) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Logger testLogger = (Logger) LoggerFactory.getLogger("test.json.logger." + System.nanoTime());
        testLogger.setLevel(Level.TRACE);
        testLogger.setAdditive(false);

        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        JsonLayout layout = new JsonLayout();
        layout.setJsonFormatter(new JacksonJsonFormatter());
        layout.setTimestampFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        layout.setAppendLineSeparator(true);
        layout.setContext(testLogger.getLoggerContext());
        layout.start();

        appender.setLayout(layout);
        appender.setOutputStream(baos);
        appender.setContext(testLogger.getLoggerContext());
        appender.start();
        testLogger.addAppender(appender);

        try {
            // Log the message at the given level
            switch (level.toInt()) {
                case Level.TRACE_INT: testLogger.trace(message); break;
                case Level.DEBUG_INT: testLogger.debug(message); break;
                case Level.INFO_INT:  testLogger.info(message);  break;
                case Level.WARN_INT:  testLogger.warn(message);  break;
                case Level.ERROR_INT: testLogger.error(message); break;
                default: testLogger.info(message);
            }

            appender.stop();

            String output = baos.toString(StandardCharsets.UTF_8.name()).trim();
            assertFalse(output.isEmpty(), "Log output should not be empty");

            // Parse as JSON
            @SuppressWarnings("unchecked")
            Map<String, Object> json = OBJECT_MAPPER.readValue(output, Map.class);

            // Verify required fields are present
            assertTrue(json.containsKey("timestamp"),
                    "JSON log must contain 'timestamp' field, got: " + json.keySet());
            assertTrue(json.containsKey("level"),
                    "JSON log must contain 'level' field, got: " + json.keySet());
            assertTrue(json.containsKey("logger"),
                    "JSON log must contain 'logger' field, got: " + json.keySet());
            assertTrue(json.containsKey("message"),
                    "JSON log must contain 'message' field, got: " + json.keySet());
            assertTrue(json.containsKey("thread"),
                    "JSON log must contain 'thread' field, got: " + json.keySet());

            // Verify field values are non-null
            assertNotNull(json.get("timestamp"), "timestamp must not be null");
            assertNotNull(json.get("level"), "level must not be null");
            assertNotNull(json.get("logger"), "logger must not be null");
            assertNotNull(json.get("thread"), "thread must not be null");

            // Verify level matches what we logged
            assertEquals(level.toString(), json.get("level"),
                    "Level in JSON should match the logged level");

            // Verify timestamp is ISO-8601 format
            String timestamp = json.get("timestamp").toString();
            assertTrue(timestamp.matches(ISO_8601_PATTERN),
                    "Timestamp should match ISO-8601 pattern (yyyy-MM-dd'T'HH:mm:ss.SSSZ), got: " + timestamp);

            // Verify message content
            assertEquals(message, json.get("message"),
                    "Message in JSON should match the logged message");
        } finally {
            testLogger.detachAppender(appender);
            appender.stop();
        }
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<String> logMessages() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(200)
                .alpha()
                .numeric()
                .withChars(' ', '-', '_', '.', ':', '/', '!', '?');
    }

    @Provide
    Arbitrary<Level> logLevels() {
        return Arbitraries.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR);
    }
}
