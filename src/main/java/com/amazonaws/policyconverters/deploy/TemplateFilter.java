package com.amazonaws.policyconverters.deploy;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors {@code maven-resources-plugin} property filtering for testability.
 * <p>
 * Replaces {@code ${key}} tokens in a template string with values from a
 * properties map. Tokens whose keys are not present in the map are left as-is,
 * matching Maven's default filtering behaviour.
 */
public final class TemplateFilter {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private TemplateFilter() {
        // utility class
    }

    /**
     * Filters a template string by replacing {@code ${key}} tokens with the
     * corresponding values from the supplied properties map.
     *
     * @param template   the template string containing {@code ${key}} tokens
     * @param properties key-value pairs used for substitution
     * @return the filtered string with resolved tokens replaced and
     *         unresolved tokens left intact
     */
    public static String filter(String template, Map<String, String> properties) {
        if (template == null) {
            return null;
        }
        if (properties == null || properties.isEmpty()) {
            return template;
        }

        Matcher matcher = TOKEN_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = properties.get(key);
            if (value != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            } else {
                // leave unresolved token as-is
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
