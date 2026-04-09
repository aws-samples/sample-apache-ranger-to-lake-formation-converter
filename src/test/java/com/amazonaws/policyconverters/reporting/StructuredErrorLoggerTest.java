package com.amazonaws.policyconverters.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StructuredErrorLogger}.
 * Validates that error log entries include component name and context fields
 * (policy ID, resource path, principal) in a consistent format.
 */
class StructuredErrorLoggerTest {

    private Logger mockLogger;
    private StructuredErrorLogger errorLogger;

    @BeforeEach
    void setUp() {
        mockLogger = mock(Logger.class);
        errorLogger = StructuredErrorLogger.forComponent("PolicyConverter", mockLogger);
    }

    @Test
    void errorWithFullContext_includesAllFields() {
        errorLogger.error("Conversion failed", "42", "db1/table1",
                "arn:aws:iam::123:role/Analyst");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger).error(captor.capture());

        String msg = captor.getValue();
        assertTrue(msg.contains("[PolicyConverter]"), "Should contain component name");
        assertTrue(msg.contains("Conversion failed"), "Should contain message");
        assertTrue(msg.contains("policyId=42"), "Should contain policy ID");
        assertTrue(msg.contains("resource=db1/table1"), "Should contain resource path");
        assertTrue(msg.contains("principal=arn:aws:iam::123:role/Analyst"), "Should contain principal");
    }

    @Test
    void errorWithException_delegatesToLoggerWithThrowable() {
        RuntimeException cause = new RuntimeException("boom");
        errorLogger.error("Operation failed", "99", "db2/t2", "arn:aws:iam::456:user/Bob", cause);

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger).error(msgCaptor.capture(), eq(cause));

        String msg = msgCaptor.getValue();
        assertTrue(msg.contains("[PolicyConverter]"));
        assertTrue(msg.contains("policyId=99"));
    }

    @Test
    void errorWithPolicyIdOnly_omitsNullFields() {
        errorLogger.error("Retry exhausted", "55", (Throwable) null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger).error(captor.capture());

        String msg = captor.getValue();
        assertTrue(msg.contains("[PolicyConverter]"));
        assertTrue(msg.contains("policyId=55"));
        assertFalse(msg.contains("resource="), "Should not contain resource when null");
        assertFalse(msg.contains("principal="), "Should not contain principal when null");
    }


    @Test
    void errorWithNoContext_includesOnlyComponentAndMessage() {
        errorLogger.error("General failure", (Throwable) null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger).error(captor.capture());

        String msg = captor.getValue();
        assertTrue(msg.contains("[PolicyConverter]"));
        assertTrue(msg.contains("General failure"));
        assertFalse(msg.contains("[policyId="), "Should not contain context bracket when all null");
    }

    @Test
    void warnWithFullContext_logsAtWarnLevel() {
        errorLogger.warn("Unmapped principal", "10", "db/tbl", "unknownUser");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger).warn(captor.capture());

        String msg = captor.getValue();
        assertTrue(msg.contains("[PolicyConverter]"));
        assertTrue(msg.contains("Unmapped principal"));
        assertTrue(msg.contains("policyId=10"));
        assertTrue(msg.contains("resource=db/tbl"));
        assertTrue(msg.contains("principal=unknownUser"));
    }

    @Test
    void getComponentName_returnsConfiguredName() {
        assertEquals("PolicyConverter", errorLogger.getComponentName());
    }

    @Test
    void forComponent_withNameOnly_createsLoggerFromName() {
        StructuredErrorLogger logger = StructuredErrorLogger.forComponent("SyncService");
        assertEquals("SyncService", logger.getComponentName());
    }

    @Test
    void formatMessage_withPartialContext_includesOnlyPresentFields() {
        // Only resource path, no policyId or principal
        String msg = errorLogger.formatMessage("Catalog error", null, "db1/*", null);
        assertTrue(msg.contains("[PolicyConverter]"));
        assertTrue(msg.contains("resource=db1/*"));
        assertFalse(msg.contains("policyId="));
        assertFalse(msg.contains("principal="));
    }

    @Test
    void formatMessage_withOnlyPrincipal_includesOnlyPrincipal() {
        String msg = errorLogger.formatMessage("Auth error", null, null, "arn:aws:iam::123:user/X");
        assertTrue(msg.contains("[PolicyConverter]"));
        assertTrue(msg.contains("principal=arn:aws:iam::123:user/X"));
        assertFalse(msg.contains("policyId="));
        assertFalse(msg.contains("resource="));
    }

    @Test
    void differentComponents_produceDifferentPrefixes() {
        StructuredErrorLogger syncLogger = StructuredErrorLogger.forComponent("SyncService", mockLogger);
        StructuredErrorLogger clientLogger = StructuredErrorLogger.forComponent("LakeFormationClient", mockLogger);

        String syncMsg = syncLogger.formatMessage("test", "1", null, null);
        String clientMsg = clientLogger.formatMessage("test", "1", null, null);

        assertTrue(syncMsg.contains("[SyncService]"));
        assertTrue(clientMsg.contains("[LakeFormationClient]"));
    }
}
