package com.amazonaws.policyconverters.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SyncCycleResultTest {

    @Test
    void successFactoryMethod() {
        SyncCycleResult result = SyncCycleResult.success(1500, 10, 3, 2, 1);

        assertTrue(result.isSuccess());
        assertEquals(1500, result.getDurationMs());
        assertEquals(10, result.getPoliciesProcessed());
        assertEquals(3, result.getGrantsApplied());
        assertEquals(2, result.getRevocationsApplied());
        assertEquals(1, result.getPoliciesSkipped());
        assertNull(result.getErrorClass());
        assertNull(result.getErrorMessage());
        assertNull(result.getError());
    }

    @Test
    void failureFactoryMethod() {
        RuntimeException cause = new RuntimeException("connection refused");
        SyncCycleResult result = SyncCycleResult.failure(500, cause);

        assertFalse(result.isSuccess());
        assertEquals(500, result.getDurationMs());
        assertEquals(0, result.getPoliciesProcessed());
        assertEquals(0, result.getGrantsApplied());
        assertEquals(0, result.getRevocationsApplied());
        assertEquals(0, result.getPoliciesSkipped());
        assertEquals("java.lang.RuntimeException", result.getErrorClass());
        assertEquals("connection refused", result.getErrorMessage());
        assertSame(cause, result.getError());
    }

    @Test
    void equalsAndHashCode_successResults() {
        SyncCycleResult a = SyncCycleResult.success(100, 5, 2, 1, 0);
        SyncCycleResult b = SyncCycleResult.success(100, 5, 2, 1, 0);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equalsAndHashCode_failureResults() {
        SyncCycleResult a = SyncCycleResult.failure(200, new RuntimeException("err"));
        SyncCycleResult b = SyncCycleResult.failure(200, new RuntimeException("err"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqual_differentValues() {
        SyncCycleResult success = SyncCycleResult.success(100, 5, 2, 1, 0);
        SyncCycleResult failure = SyncCycleResult.failure(100, new RuntimeException("err"));
        assertNotEquals(success, failure);
    }

    @Test
    void notEqual_differentCounts() {
        SyncCycleResult a = SyncCycleResult.success(100, 5, 2, 1, 0);
        SyncCycleResult b = SyncCycleResult.success(100, 10, 2, 1, 0);
        assertNotEquals(a, b);
    }

    @Test
    void toStringSuccess() {
        SyncCycleResult result = SyncCycleResult.success(1500, 10, 3, 2, 1);
        String str = result.toString();
        assertTrue(str.contains("success=true"));
        assertTrue(str.contains("durationMs=1500"));
        assertTrue(str.contains("policiesProcessed=10"));
        assertTrue(str.contains("grantsApplied=3"));
        assertTrue(str.contains("revocationsApplied=2"));
        assertTrue(str.contains("policiesSkipped=1"));
    }

    @Test
    void toStringFailure() {
        SyncCycleResult result = SyncCycleResult.failure(500, new RuntimeException("boom"));
        String str = result.toString();
        assertTrue(str.contains("success=false"));
        assertTrue(str.contains("durationMs=500"));
        assertTrue(str.contains("errorClass='java.lang.RuntimeException'"));
        assertTrue(str.contains("errorMessage='boom'"));
    }

    @Test
    void equalsSameInstance() {
        SyncCycleResult result = SyncCycleResult.success(100, 1, 1, 1, 0);
        assertEquals(result, result);
    }

    @Test
    void notEqualToNull() {
        SyncCycleResult result = SyncCycleResult.success(100, 1, 1, 1, 0);
        assertNotEquals(null, result);
    }

    @Test
    void failureWithNullMessage() {
        SyncCycleResult result = SyncCycleResult.failure(100, new RuntimeException());
        assertFalse(result.isSuccess());
        assertEquals("java.lang.RuntimeException", result.getErrorClass());
        assertNull(result.getErrorMessage());
    }
}
