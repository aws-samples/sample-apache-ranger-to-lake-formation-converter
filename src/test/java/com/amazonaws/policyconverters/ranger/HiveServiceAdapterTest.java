package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.lakeformation.AwsContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveServiceAdapterTest {

    private final HiveServiceAdapter adapter =
            new HiveServiceAdapter(new AwsContext("us-east-1", "123456789012", "123456789012"));

    @Test
    void indexAndLockAreDeclaredKnownUnmappable() {
        assertTrue(adapter.isKnownUnmappableAccessType("index"));
        assertTrue(adapter.isKnownUnmappableAccessType("lock"));
    }

    @Test
    void knownUnmappableMatchIsCaseAndWhitespaceInsensitive() {
        // Ranger policy JSON is not normalized; the converter passes access.getType() raw.
        assertTrue(adapter.isKnownUnmappableAccessType("  INDEX  "));
    }

    @Test
    void mappableAccessTypeIsNotKnownUnmappable() {
        assertFalse(adapter.isKnownUnmappableAccessType("select"));
    }

    @Test
    void unrecognizedAccessTypeIsNotKnownUnmappable() {
        // This is the critical case: an access type we have never seen must NOT be
        // classified as known-unmappable, or we lose our adapter-coverage signal.
        assertFalse(adapter.isKnownUnmappableAccessType("repladmin"));
    }

    @Test
    void nullAccessTypeIsNotKnownUnmappable() {
        assertFalse(adapter.isKnownUnmappableAccessType(null));
    }
}
