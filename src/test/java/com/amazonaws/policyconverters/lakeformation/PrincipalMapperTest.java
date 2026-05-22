package com.amazonaws.policyconverters.lakeformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrincipalMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    // --- fromConfig tests ---

    @Test
    void fromConfigResolvesAllPrincipalTypes() {
        Map<String, String> users = Collections.singletonMap("alice", "arn:aws:iam::123:user/alice");
        Map<String, String> groups = Collections.singletonMap("analysts", "arn:aws:iam::123:role/AnalystRole");
        Map<String, String> roles = Collections.singletonMap("admin", "arn:aws:iam::123:role/AdminRole");
        PrincipalMappingConfig config = new PrincipalMappingConfig(users, groups, roles);

        PrincipalMapper mapper = StaticPrincipalMapper.fromConfig(config, null);

        assertEquals(Optional.of("arn:aws:iam::123:user/alice"), mapper.resolveUser("alice"));
        assertEquals(Optional.of("arn:aws:iam::123:role/AnalystRole"), mapper.resolveGroup("analysts"));
        assertEquals(Optional.of("arn:aws:iam::123:role/AdminRole"), mapper.resolveRole("admin"));
    }

    @Test
    void fromConfigWithNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> StaticPrincipalMapper.fromConfig(null, null));
    }

    @Test
    void fromConfigWithEmptyMappings() {
        PrincipalMappingConfig config = new PrincipalMappingConfig(null, null, null);
        PrincipalMapper mapper = StaticPrincipalMapper.fromConfig(config, null);

        assertEquals(Optional.empty(), mapper.resolveUser("alice"));
        assertEquals(Optional.empty(), mapper.resolveGroup("analysts"));
        assertEquals(Optional.empty(), mapper.resolveRole("admin"));
    }

    // --- Unmapped principal tests ---

    @Test
    void unmappedUserReturnsEmpty() {
        PrincipalMappingConfig config = new PrincipalMappingConfig(
                Collections.singletonMap("alice", "arn:aws:iam::123:user/alice"), null, null);
        PrincipalMapper mapper = StaticPrincipalMapper.fromConfig(config, null);

        assertEquals(Optional.empty(), mapper.resolveUser("unknown_user"));
    }

    @Test
    void unmappedGroupReturnsEmpty() {
        PrincipalMappingConfig config = new PrincipalMappingConfig(null, null, null);
        PrincipalMapper mapper = StaticPrincipalMapper.fromConfig(config, null);

        assertEquals(Optional.empty(), mapper.resolveGroup("unknown_group"));
    }

    @Test
    void unmappedRoleReturnsEmpty() {
        PrincipalMappingConfig config = new PrincipalMappingConfig(null, null, null);
        PrincipalMapper mapper = StaticPrincipalMapper.fromConfig(config, null);

        assertEquals(Optional.empty(), mapper.resolveRole("unknown_role"));
    }

    @Test
    void nullPrincipalNameReturnsEmpty() {
        PrincipalMappingConfig config = new PrincipalMappingConfig(null, null, null);
        PrincipalMapper mapper = StaticPrincipalMapper.fromConfig(config, null);

        assertEquals(Optional.empty(), mapper.resolveUser(null));
        assertEquals(Optional.empty(), mapper.resolveGroup(null));
        assertEquals(Optional.empty(), mapper.resolveRole(null));
    }

    // --- fromFile JSON tests ---

    @Test
    void fromFileLoadsJson() throws IOException {
        Map<String, String> users = new HashMap<>();
        users.put("alice", "arn:aws:iam::123:user/alice");
        users.put("bob", "arn:aws:iam::123:user/bob");
        Map<String, String> groups = Collections.singletonMap("analysts", "arn:aws:iam::123:role/AnalystRole");
        Map<String, String> roles = Collections.singletonMap("admin", "arn:aws:iam::123:role/AdminRole");
        PrincipalMappingConfig config = new PrincipalMappingConfig(users, groups, roles);

        File jsonFile = tempDir.resolve("mappings.json").toFile();
        objectMapper.writeValue(jsonFile, config);

        PrincipalMapper mapper = StaticPrincipalMapper.fromFile(jsonFile.getAbsolutePath());

        assertEquals(Optional.of("arn:aws:iam::123:user/alice"), mapper.resolveUser("alice"));
        assertEquals(Optional.of("arn:aws:iam::123:user/bob"), mapper.resolveUser("bob"));
        assertEquals(Optional.of("arn:aws:iam::123:role/AnalystRole"), mapper.resolveGroup("analysts"));
        assertEquals(Optional.of("arn:aws:iam::123:role/AdminRole"), mapper.resolveRole("admin"));
    }

    @Test
    void fromFileLoadsJsonWithEmptyMappings() throws IOException {
        PrincipalMappingConfig config = new PrincipalMappingConfig(null, null, null);
        File jsonFile = tempDir.resolve("empty.json").toFile();
        objectMapper.writeValue(jsonFile, config);

        PrincipalMapper mapper = StaticPrincipalMapper.fromFile(jsonFile.getAbsolutePath());

        assertEquals(Optional.empty(), mapper.resolveUser("anyone"));
    }

    // --- fromFile properties tests ---

    @Test
    void fromFileLoadsProperties() throws IOException {
        Properties props = new Properties();
        props.setProperty("user.alice", "arn:aws:iam::123:user/alice");
        props.setProperty("user.bob", "arn:aws:iam::123:user/bob");
        props.setProperty("group.analysts", "arn:aws:iam::123:role/AnalystRole");
        props.setProperty("role.admin", "arn:aws:iam::123:role/AdminRole");

        File propsFile = tempDir.resolve("mappings.properties").toFile();
        try (FileOutputStream fos = new FileOutputStream(propsFile)) {
            props.store(fos, null);
        }

        PrincipalMapper mapper = StaticPrincipalMapper.fromFile(propsFile.getAbsolutePath());

        assertEquals(Optional.of("arn:aws:iam::123:user/alice"), mapper.resolveUser("alice"));
        assertEquals(Optional.of("arn:aws:iam::123:user/bob"), mapper.resolveUser("bob"));
        assertEquals(Optional.of("arn:aws:iam::123:role/AnalystRole"), mapper.resolveGroup("analysts"));
        assertEquals(Optional.of("arn:aws:iam::123:role/AdminRole"), mapper.resolveRole("admin"));
    }

    @Test
    void fromFilePropertiesIgnoresUnrecognizedKeys() throws IOException {
        Properties props = new Properties();
        props.setProperty("user.alice", "arn:aws:iam::123:user/alice");
        props.setProperty("unknown.key", "some-value");

        File propsFile = tempDir.resolve("partial.properties").toFile();
        try (FileOutputStream fos = new FileOutputStream(propsFile)) {
            props.store(fos, null);
        }

        PrincipalMapper mapper = StaticPrincipalMapper.fromFile(propsFile.getAbsolutePath());

        assertEquals(Optional.of("arn:aws:iam::123:user/alice"), mapper.resolveUser("alice"));
    }

    @Test
    void fromFileEmptyProperties() throws IOException {
        Properties props = new Properties();
        File propsFile = tempDir.resolve("empty.properties").toFile();
        try (FileOutputStream fos = new FileOutputStream(propsFile)) {
            props.store(fos, null);
        }

        PrincipalMapper mapper = StaticPrincipalMapper.fromFile(propsFile.getAbsolutePath());

        assertEquals(Optional.empty(), mapper.resolveUser("anyone"));
    }

    // --- Unmapped principal metric tests ---

    @Test
    void unmappedUser_emitsUnmappedPrincipalMetric() {
        MetricsEmitter metricsEmitter = mock(MetricsEmitter.class);
        PrincipalMappingConfig config = new PrincipalMappingConfig(Map.of(), Map.of(), Map.of());
        StaticPrincipalMapper mapper = StaticPrincipalMapper.fromConfig(config, metricsEmitter);

        Optional<String> result = mapper.resolveUser("unknown_user");

        assertEquals(Optional.empty(), result);
        verify(metricsEmitter).recordUnmappedPrincipal("user");
    }

    @Test
    void unmappedGroup_emitsUnmappedPrincipalMetric() {
        MetricsEmitter metricsEmitter = mock(MetricsEmitter.class);
        PrincipalMappingConfig config = new PrincipalMappingConfig(Map.of(), Map.of(), Map.of());
        StaticPrincipalMapper mapper = StaticPrincipalMapper.fromConfig(config, metricsEmitter);

        Optional<String> result = mapper.resolveGroup("unknown_group");

        assertEquals(Optional.empty(), result);
        verify(metricsEmitter).recordUnmappedPrincipal("group");
    }

    // --- fromFile error cases ---

    @Test
    void fromFileWithNullPathThrows() {
        assertThrows(IllegalArgumentException.class, () -> StaticPrincipalMapper.fromFile(null));
    }

    @Test
    void fromFileWithEmptyPathThrows() {
        assertThrows(IllegalArgumentException.class, () -> StaticPrincipalMapper.fromFile("  "));
    }

    @Test
    void fromFileWithNonExistentFileThrows() {
        assertThrows(IOException.class, () -> StaticPrincipalMapper.fromFile("/nonexistent/path/mappings.json"));
    }

    @Test
    void fromFileWithUnsupportedExtensionThrows() throws IOException {
        File xmlFile = tempDir.resolve("mappings.xml").toFile();
        xmlFile.createNewFile();

        assertThrows(IOException.class, () -> StaticPrincipalMapper.fromFile(xmlFile.getAbsolutePath()));
    }
}
