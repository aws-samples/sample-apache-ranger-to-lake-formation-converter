package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.ranger.EmrfsServiceAdapter;
import com.amazonaws.policyconverters.ranger.HiveServiceAdapter;
import com.amazonaws.policyconverters.ranger.PrestoServiceAdapter;
import com.amazonaws.policyconverters.ranger.RangerServiceAdapter;
import com.amazonaws.policyconverters.ranger.TrinoServiceAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BaseRangerService subclass contracts.
 * Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5
 *
 * <p>For each concrete subclass (LakeFormation, Hive, Presto, Trino), verifies:
 * <ul>
 *   <li>getServiceType() returns the correct value</li>
 *   <li>getServiceInstanceName() returns the constructor-provided name</li>
 *   <li>createAdapter(awsContext) returns the correct adapter type</li>
 *   <li>getServiceDefinitionResourcePath() returns the correct path</li>
 *   <li>getLastKnownGoodPolicies() returns empty list initially</li>
 * </ul>
 */
class BaseRangerServiceTest {

    private AwsContext awsContext;

    @BeforeEach
    void setUp() {
        awsContext = new AwsContext("us-east-1", "123456789012", "123456789012");
    }

    @Nested
    class LakeFormationRangerServiceTests {

        private LakeFormationRangerService service;

        @BeforeEach
        void setUp() {
            service = new LakeFormationRangerService("lf_prod");
        }

        @Test
        void getServiceTypeReturnsLakeformation() {
            assertEquals("lakeformation", service.getServiceType());
        }

        @Test
        void getServiceInstanceNameReturnsConstructorValue() {
            assertEquals("lf_prod", service.getServiceInstanceName());
        }

        @Test
        void createAdapterReturnsRangerServiceAdapter() {
            SourcePolicyAdapter adapter = service.createAdapter(awsContext);
            assertInstanceOf(RangerServiceAdapter.class, adapter);
        }

        @Test
        void getServiceDefinitionResourcePathReturnsLakeformationJson() {
            assertEquals("/ranger-servicedef-lakeformation.json", service.getServiceDefinitionResourcePath());
        }

        @Test
        void getLastKnownGoodPoliciesReturnsEmptyListInitially() {
            assertEquals(Collections.emptyList(), service.getLastKnownGoodPolicies());
        }
    }

    @Nested
    class HiveRangerServiceTests {

        private HiveRangerService service;

        @BeforeEach
        void setUp() {
            service = new HiveRangerService("hive_prod");
        }

        @Test
        void getServiceTypeReturnsHive() {
            assertEquals("hive", service.getServiceType());
        }

        @Test
        void getServiceInstanceNameReturnsConstructorValue() {
            assertEquals("hive_prod", service.getServiceInstanceName());
        }

        @Test
        void createAdapterReturnsHiveServiceAdapter() {
            SourcePolicyAdapter adapter = service.createAdapter(awsContext);
            assertInstanceOf(HiveServiceAdapter.class, adapter);
        }

        @Test
        void getServiceDefinitionResourcePathReturnsHiveJson() {
            assertEquals("/ranger-servicedef-hive.json", service.getServiceDefinitionResourcePath());
        }

        @Test
        void getLastKnownGoodPoliciesReturnsEmptyListInitially() {
            assertEquals(Collections.emptyList(), service.getLastKnownGoodPolicies());
        }
    }

    @Nested
    class PrestoRangerServiceTests {

        private PrestoRangerService service;

        @BeforeEach
        void setUp() {
            service = new PrestoRangerService("presto_prod", "awsdatacatalog");
        }

        @Test
        void getServiceTypeReturnsPresto() {
            assertEquals("presto", service.getServiceType());
        }

        @Test
        void getServiceInstanceNameReturnsConstructorValue() {
            assertEquals("presto_prod", service.getServiceInstanceName());
        }

        @Test
        void createAdapterReturnsPrestoServiceAdapter() {
            SourcePolicyAdapter adapter = service.createAdapter(awsContext);
            assertInstanceOf(PrestoServiceAdapter.class, adapter);
        }

        @Test
        void getServiceDefinitionResourcePathReturnsPrestoJson() {
            assertEquals("/ranger-servicedef-presto.json", service.getServiceDefinitionResourcePath());
        }

        @Test
        void getLastKnownGoodPoliciesReturnsEmptyListInitially() {
            assertEquals(Collections.emptyList(), service.getLastKnownGoodPolicies());
        }

        @Test
        void getGdcCatalogNameReturnsConfiguredValue() {
            assertEquals("awsdatacatalog", service.getGdcCatalogName());
        }
    }

    @Nested
    class TrinoRangerServiceTests {

        private TrinoRangerService service;

        @BeforeEach
        void setUp() {
            service = new TrinoRangerService("trino_prod", "glue_catalog");
        }

        @Test
        void getServiceTypeReturnsTrino() {
            assertEquals("trino", service.getServiceType());
        }

        @Test
        void getServiceInstanceNameReturnsConstructorValue() {
            assertEquals("trino_prod", service.getServiceInstanceName());
        }

        @Test
        void createAdapterReturnsTrinoServiceAdapter() {
            SourcePolicyAdapter adapter = service.createAdapter(awsContext);
            assertInstanceOf(TrinoServiceAdapter.class, adapter);
        }

        @Test
        void getServiceDefinitionResourcePathReturnsTrinoJson() {
            assertEquals("/ranger-servicedef-trino.json", service.getServiceDefinitionResourcePath());
        }

        @Test
        void getLastKnownGoodPoliciesReturnsEmptyListInitially() {
            assertEquals(Collections.emptyList(), service.getLastKnownGoodPolicies());
        }

        @Test
        void getGdcCatalogNameReturnsConfiguredValue() {
            assertEquals("glue_catalog", service.getGdcCatalogName());
        }
    }

    @Nested
    class EmrfsRangerServiceTests {

        private EmrfsRangerService service;

        @BeforeEach
        void setUp() {
            service = new EmrfsRangerService("emrfs_prod");
        }

        @Test
        void getServiceTypeReturnsAmazonEmrEmrfs() {
            assertEquals("amazon-emr-emrfs", service.getServiceType());
        }

        @Test
        void createAdapterReturnsNonNull() {
            SourcePolicyAdapter adapter = service.createAdapter(awsContext);
            assertNotNull(adapter);
        }

        @Test
        void createAdapterReturnsEmrfsServiceAdapter() {
            SourcePolicyAdapter adapter = service.createAdapter(awsContext);
            assertInstanceOf(EmrfsServiceAdapter.class, adapter);
        }

        @Test
        void getServiceDefinitionResourcePathReturnsEmrfsJson() {
            assertEquals("/ranger-servicedef-amazon-emr-emrfs.json", service.getServiceDefinitionResourcePath());
        }
    }
}
