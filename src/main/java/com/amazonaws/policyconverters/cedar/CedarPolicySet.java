package com.amazonaws.policyconverters.cedar;

import com.cedarpolicy.model.exception.InternalException;
import com.cedarpolicy.model.policy.Policy;
import com.cedarpolicy.model.policy.PolicySet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps the cedar-java SDK {@link PolicySet} with convenience methods
 * for the sync pipeline.
 */
public class CedarPolicySet {

    private static final Pattern PRINCIPAL_PATTERN = Pattern.compile(
            "principal\\s*==\\s*(\\S+::\"[^\"]+\")");
    private static final Pattern EFFECT_PATTERN = Pattern.compile(
            "^\\s*(permit|forbid)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern ANNOTATION_SOURCE_POLICY_PATTERN = Pattern.compile(
            "@source\\(\"([^\"]+)\"\\)");

    private final PolicySet internalPolicySet;

    public CedarPolicySet(PolicySet policySet) {
        this.internalPolicySet = policySet;
    }

    /**
     * Parse Cedar text into a CedarPolicySet.
     *
     * @param cedarText the Cedar policy text
     * @return a new CedarPolicySet
     * @throws InternalException if parsing fails
     */
    public static CedarPolicySet fromCedarString(String cedarText) throws InternalException {
        PolicySet policySet = PolicySet.parsePolicies(cedarText);
        return new CedarPolicySet(policySet);
    }

    /**
     * Get all unique principal identifiers referenced in the policy set.
     */
    public Set<String> getPrincipals() {
        Set<String> principals = new LinkedHashSet<>();
        if (internalPolicySet.policies == null) {
            return principals;
        }
        for (Policy policy : internalPolicySet.policies) {
            String source = policy.getSource();
            if (source != null) {
                Matcher matcher = PRINCIPAL_PATTERN.matcher(source);
                if (matcher.find()) {
                    principals.add(matcher.group(1));
                }
            }
        }
        return principals;
    }

    /**
     * Format the policy set to a Cedar syntax string by concatenating
     * individual policy sources.
     */
    public String toCedarString() {
        if (internalPolicySet.policies == null || internalPolicySet.policies.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Policy policy : internalPolicySet.policies) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(policy.getSource());
        }
        return sb.toString();
    }

    /**
     * Get the number of permit statements in the policy set.
     */
    public int getPermitCount() {
        return countByEffect("permit");
    }

    /**
     * Get the number of forbid statements in the policy set.
     */
    public int getForbidCount() {
        return countByEffect("forbid");
    }

    /**
     * Extract source policy IDs from @source annotations on policies.
     */
    public List<String> getSourcePolicyIds() {
        List<String> ids = new ArrayList<>();
        if (internalPolicySet.policies == null) {
            return ids;
        }
        for (Policy policy : internalPolicySet.policies) {
            String source = policy.getSource();
            if (source != null) {
                Matcher matcher = ANNOTATION_SOURCE_POLICY_PATTERN.matcher(source);
                if (matcher.find()) {
                    ids.add(matcher.group(1));
                }
            }
        }
        return ids;
    }

    /**
     * Access the underlying cedar-java PolicySet.
     */
    public PolicySet getInternalPolicySet() {
        return internalPolicySet;
    }

    private int countByEffect(String effect) {
        if (internalPolicySet.policies == null) {
            return 0;
        }
        int count = 0;
        for (Policy policy : internalPolicySet.policies) {
            String source = policy.getSource();
            if (source != null) {
                Matcher matcher = EFFECT_PATTERN.matcher(source);
                if (matcher.find() && matcher.group(1).equals(effect)) {
                    count++;
                }
            }
        }
        return count;
    }
}
