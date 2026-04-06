package com.amazonaws.policyconverters.server;

// Feature: conversion-server, Property 5: Error log routing to stderr

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.contrib.jackson.JacksonJsonFormatter;
import ch.qos.logback.contrib.json.classic.JsonLayout;
import ch.qos.logback.core.OutputStreamAppender;
import net.jqwik.api.*;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test verifying that ERROR-level log messages are routed to
 * both stdout and stderr, while non-ERROR messages appear only on stdout.
 * <p>
 * Uses programmatically configured OutputStreamAppenders with JsonLayout:
 * - STDOUT appender: captures all levels
 * - STDERR appender: has a ThresholdFilter at ERROR level
 * <p>
 * **Validates: Requirements 4.2**
 */
class ErrorLogRoutingPropertyTest {

    @Property(tries = 100)
    void errorMessagesRouteToStderr_nonErrorMessagesDoNot(
            @ForAll("logMessages") String message,
            @ForAll("logLevels") Level level
    ) throws Exception {
        ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();

        Logger testLogger = (Logger) LoggerFactory.getLogger(
                "test.stderr.routing." + System.nanoTime());
        testLogger.setLevel(Level.TRACE);
        testLogger.setAdditive(false);

        // STDOUT appender: captures all levels (no filter)
        OutputStreamAppender<ILoggingEvent> stdoutAppender = createAppender(
                testLogger, stdoutStream, null);

        // STDERR appender: ThresholdFilter at ERROR level
        OutputStreamAppender<ILoggingEvent> stderrAppender = createAppender(
                testLogger, stderrStream, Level.ERROR);

        testLogger.addAppender(stdoutAppender);
        testLogger.addAppender(stderrAppender);

        try {
            // Log the message at the given level
            logAtLevel(testLogger, level, message);

            stdoutAppender.stop();
            stderrAppender.stop();

            String stdoutOutput = stdoutStream.toString(StandardCharsets.UTF_8.name()).trim();
            String stderrOutput = stderrStream.toString(StandardCharsets.UTF_8.name()).trim();

            // stdout should always contain the message regardless of level
            assertFalse(stdoutOutput.isEmpty(),
                    "stdout should contain the log message for level " + level);
            assertTrue(stdoutOutput.contains(message),
                    "stdout should contain the message text for level " + level);

            if (level.toInt() >= Level.ERROR_INT) {
                // ERROR messages should appear on stderr
                assertFalse(stderrOutput.isEmpty(),
                        "stderr should contain ERROR-level messages");
                assertTrue(stderrOutput.contains(message),
                        "stderr should contain the ERROR message text");
            } else {
                // Non-ERROR messages should NOT appear on stderr
                assertTrue(stderrOutput.isEmpty(),
                        "stderr should be empty for " + level + " level messages, but got: "
                                + stderrOutput);
            }
        } finally {
            testLogger.detachAppender(stdoutAppender);
            testLogger.detachAppender(stderrAppender);
            stdoutAppender.stop();
            stderrAppender.stop();
        }
    }

    // --- Helpers ---

    private OutputStreamAppender<ILoggingEvent> createAppender(
            Logger logger,
            ByteArrayOutputStream outputStream,
            Level thresholdLevel
    ) {
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();

        JsonLayout layout = new JsonLayout();
        layout.setJsonFormatter(new JacksonJsonFormatter());
        layout.setTimestampFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        layout.setAppendLineSeparator(true);
        layout.setContext(logger.getLoggerContext());
        layout.start();

        if (thresholdLevel != null) {
            ThresholdFilter filter = new ThresholdFilter();
            filter.setLevel(thresholdLevel.toString());
            filter.setContext(logger.getLoggerContext());
            filter.start();
            appender.addFilter(filter);
        }

        appender.setLayout(layout);
        appender.setOutputStream(outputStream);
        appender.setContext(logger.getLoggerContext());
        appender.start();

        return appender;
    }

    private void logAtLevel(Logger logger, Level level, String message) {
        switch (level.toInt()) {
            case Level.TRACE_INT: logger.trace(message); break;
            case Level.DEBUG_INT: logger.debug(message); break;
            case Level.INFO_INT:  logger.info(message);  break;
            case Level.WARN_INT:  logger.warn(message);  break;
            case Level.ERROR_INT: logger.error(message);  break;
            default: logger.info(message);
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
