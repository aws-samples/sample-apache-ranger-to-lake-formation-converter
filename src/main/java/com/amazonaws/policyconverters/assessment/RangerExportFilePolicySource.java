package com.amazonaws.policyconverters.assessment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RangerExportFilePolicySource implements PolicySource {

    private static final Logger LOG = LoggerFactory.getLogger(RangerExportFilePolicySource.class);

    private static final Set<String> KNOWN_SERVICE_TYPES = Set.of(
            "lakeformation", "hive", "presto", "trino", "amazon-emr-emrfs");

    private final Path exportFile;

    public RangerExportFilePolicySource(Path exportFile) {
        this.exportFile = exportFile;
    }

    @Override
    public String sourceLabel() {
        return "file:" + exportFile.getFileName().toString();
    }

    @Override
    public List<ServicePolicyBatch> load() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        RangerExportModel model;
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(exportFile), StandardCharsets.UTF_8)) {
            model = mapper.readValue(reader, RangerExportModel.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Ranger export file: " + exportFile, e);
        }

        if (model.policies == null || model.policies.isEmpty()) {
            LOG.warn("Ranger export file '{}' contains no policies", exportFile.getFileName());
            return List.of();
        }

        Map<String, List<RangerPolicy>> allByService = new LinkedHashMap<>();
        Map<String, String> serviceTypeByName = new LinkedHashMap<>();

        for (RangerPolicy policy : model.policies) {
            if (policy.getService() == null || policy.getServiceType() == null) {
                LOG.warn("Skipping policy id={} name='{}': null service or serviceType",
                        policy.getId(), policy.getName());
                continue;
            }
            String svcName = policy.getService();
            allByService.computeIfAbsent(svcName, k -> new ArrayList<>()).add(policy);
            serviceTypeByName.putIfAbsent(svcName, policy.getServiceType());
        }

        List<ServicePolicyBatch> batches = new ArrayList<>();
        for (Map.Entry<String, List<RangerPolicy>> entry : allByService.entrySet()) {
            String svcName = entry.getKey();
            String svcType = serviceTypeByName.get(svcName);
            List<RangerPolicy> all = entry.getValue();
            int rawCount = all.size();

            if (!KNOWN_SERVICE_TYPES.contains(svcType)) {
                LOG.warn("Skipping service '{}' (serviceType='{}'): unsupported service type",
                        svcName, svcType);
                batches.add(ServicePolicyBatch.skipped(svcName, svcType, rawCount,
                        "unsupported service type"));
            } else {
                List<RangerPolicy> enabled = new ArrayList<>(all);
                enabled.removeIf(p -> p.getIsEnabled() != null && !p.getIsEnabled());
                batches.add(ServicePolicyBatch.assessed(svcName, svcType, enabled, rawCount));
            }
        }
        return batches;
    }
}
