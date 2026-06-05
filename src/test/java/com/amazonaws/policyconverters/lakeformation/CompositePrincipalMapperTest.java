package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositePrincipalMapperTest {

    @Mock MetricsEmitter metricsEmitter;

    @Test
    void firstDelegateHit_returnsImmediately_doesNotConsultSecond() {
        PrincipalMapper first  = alwaysReturns("arn:first");
        PrincipalMapper second = mock(PrincipalMapper.class);
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(first, second), null);

        assertEquals(Optional.of("arn:first"), composite.resolveUser("alice"));
        verifyNoInteractions(second);
    }

    @Test
    void firstDelegateMiss_fallsThroughToSecond() {
        PrincipalMapper first  = alwaysMisses();
        PrincipalMapper second = alwaysReturns("arn:second");
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(first, second), null);

        assertEquals(Optional.of("arn:second"), composite.resolveUser("alice"));
    }

    @Test
    void allDelegatesMiss_returnsEmpty() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(alwaysMisses(), alwaysMisses()), null);

        assertEquals(Optional.empty(), composite.resolveUser("alice"));
    }

    @Test
    void allMiss_recordsUnmappedMetricExactlyOnce_forUser() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(alwaysMisses(), alwaysMisses()), metricsEmitter);

        composite.resolveUser("alice");

        verify(metricsEmitter, times(1)).recordUnmappedPrincipal("user");
        verifyNoMoreInteractions(metricsEmitter);
    }

    @Test
    void allMiss_recordsUnmappedMetricExactlyOnce_forGroup() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(alwaysMisses(), alwaysMisses()), metricsEmitter);

        composite.resolveGroup("admins");

        verify(metricsEmitter, times(1)).recordUnmappedPrincipal("group");
        verifyNoMoreInteractions(metricsEmitter);
    }

    @Test
    void allMiss_recordsUnmappedMetricExactlyOnce_forRole() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(alwaysMisses(), alwaysMisses()), metricsEmitter);

        composite.resolveRole("etl_role");

        verify(metricsEmitter, times(1)).recordUnmappedPrincipal("role");
        verifyNoMoreInteractions(metricsEmitter);
    }

    @Test
    void firstDelegateHit_noMetricEmitted() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(alwaysReturns("arn:x")), metricsEmitter);

        composite.resolveUser("alice");

        verifyNoInteractions(metricsEmitter);
    }

    @Test
    void firstMissSecondHit_noMetricEmitted() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(
                        List.of(alwaysMisses(), alwaysReturns("arn:x")), metricsEmitter);

        composite.resolveUser("alice");

        verifyNoInteractions(metricsEmitter);
    }

    @Test
    void nullMetricsEmitter_allMiss_doesNotThrow() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(alwaysMisses()), null);

        assertDoesNotThrow(() -> composite.resolveUser("alice"));
        assertEquals(Optional.empty(), composite.resolveUser("alice"));
    }

    @Test
    void resolveGroup_usesGroupDelegation() {
        PrincipalMapper first  = alwaysMisses();
        PrincipalMapper second = alwaysReturnsGroup("arn:group");
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(first, second), null);

        assertEquals(Optional.of("arn:group"), composite.resolveGroup("admins"));
        verify(second).resolveGroup("admins");
    }

    @Test
    void resolveRole_usesRoleDelegation() {
        PrincipalMapper delegate = alwaysReturnsRole("arn:role");
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(delegate), null);

        assertEquals(Optional.of("arn:role"), composite.resolveRole("etl_role"));
        verify(delegate).resolveRole("etl_role");
    }

    private static PrincipalMapper alwaysReturns(String arn) {
        PrincipalMapper m = mock(PrincipalMapper.class);
        lenient().when(m.resolveUser(any())).thenReturn(Optional.of(arn));
        lenient().when(m.resolveGroup(any())).thenReturn(Optional.empty());
        lenient().when(m.resolveRole(any())).thenReturn(Optional.empty());
        return m;
    }

    private static PrincipalMapper alwaysReturnsGroup(String arn) {
        PrincipalMapper m = mock(PrincipalMapper.class);
        lenient().when(m.resolveUser(any())).thenReturn(Optional.empty());
        lenient().when(m.resolveGroup(any())).thenReturn(Optional.of(arn));
        lenient().when(m.resolveRole(any())).thenReturn(Optional.empty());
        return m;
    }

    private static PrincipalMapper alwaysReturnsRole(String arn) {
        PrincipalMapper m = mock(PrincipalMapper.class);
        lenient().when(m.resolveUser(any())).thenReturn(Optional.empty());
        lenient().when(m.resolveGroup(any())).thenReturn(Optional.empty());
        lenient().when(m.resolveRole(any())).thenReturn(Optional.of(arn));
        return m;
    }

    private static PrincipalMapper alwaysMisses() {
        PrincipalMapper m = mock(PrincipalMapper.class);
        lenient().when(m.resolveUser(any())).thenReturn(Optional.empty());
        lenient().when(m.resolveGroup(any())).thenReturn(Optional.empty());
        lenient().when(m.resolveRole(any())).thenReturn(Optional.empty());
        return m;
    }
}
