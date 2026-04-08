package com.amazonaws.policyconverters.deploy;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility class that mirrors the Groovy principal-mapping parsing logic
 * used by the gmavenplus-plugin during Maven resource filtering.
 * <p>
 * This Java implementation allows the parsing logic to be unit-tested
 * and property-tested without invoking Maven.
 */
public final class DeployTemplateUtils {

    private static final String EMPTY_YAML_MAP = "    {}";

    private DeployTemplateUtils() {
        // utility class
    }

    /**
     * Converts a comma-separated string of {@code name=arn} pairs into
     * indented YAML map entries.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code null} or blank → {@code "    {}"}</li>
     *   <li>{@code "alice=arn:aws:iam::123:user/Alice"} →
     *       {@code "    \"alice\": \"arn:aws:iam::123:user/Alice\""}</li>
     *   <li>Multiple pairs are separated by newlines, each with 4-space indent</li>
     * </ul>
     *
     * @param csv comma-separated {@code name=arn} pairs
     * @return indented YAML fragment
     * @throws IllegalArgumentException if any pair is missing the {@code =} separator
     */
    public static String parseMappingsToYaml(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return EMPTY_YAML_MAP;
        }

        return Stream.of(csv.split(","))
                .map(pair -> {
                    String trimmed = pair.trim();
                    int eqIndex = trimmed.indexOf('=');
                    if (eqIndex < 0) {
                        throw new IllegalArgumentException(
                                "Invalid mapping pair (missing '=' separator): \"" + trimmed + "\"");
                    }
                    String name = trimmed.substring(0, eqIndex).trim();
                    String arn = trimmed.substring(eqIndex + 1).trim();
                    return "    \"" + name + "\": \"" + arn + "\"";
                })
                .collect(Collectors.joining("\n"));
    }
}
