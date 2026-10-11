package com.ffp.feature_flag_platform.evaluation.engine;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Minimal SemVer 2.0 parser used by SEMVER_* operators. Lenient about missing minor/patch
 * ("2" == "2.0.0"), strict about everything else; build metadata is ignored for ordering.
 */
record SemanticVersion(int major, int minor, int patch, String preRelease) implements Comparable<SemanticVersion> {

    private static final Pattern PATTERN = Pattern.compile(
            "^v?(0|[1-9]\\d*)(?:\\.(0|[1-9]\\d*))?(?:\\.(0|[1-9]\\d*))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

    static Optional<SemanticVersion> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        var m = PATTERN.matcher(input.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SemanticVersion(
                    Integer.parseInt(m.group(1)),
                    m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                    m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
                    m.group(4)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(SemanticVersion o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) c = Integer.compare(minor, o.minor);
        if (c == 0) c = Integer.compare(patch, o.patch);
        if (c != 0) return c;
        // A version without pre-release has higher precedence (1.0.0 > 1.0.0-beta).
        if (preRelease == null || o.preRelease == null) {
            return preRelease == null ? (o.preRelease == null ? 0 : 1) : -1;
        }
        return comparePreRelease(preRelease, o.preRelease);
    }

    private static int comparePreRelease(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            boolean na = pa[i].chars().allMatch(Character::isDigit);
            boolean nb = pb[i].chars().allMatch(Character::isDigit);
            int c;
            if (na && nb) c = Long.compare(Long.parseLong(pa[i]), Long.parseLong(pb[i]));
            else if (na) c = -1;
            else if (nb) c = 1;
            else c = pa[i].compareTo(pb[i]);
            if (c != 0) return c;
        }
        return Integer.compare(pa.length, pb.length);
    }
}
