package com.company.jenkins

/**
 * Shared validation helpers for Trivy-based Jenkins Shared Library steps.
 */
class TrivyValidation implements Serializable {
    private static final Set<String> ALLOWED_SEVERITIES = [
        'UNKNOWN',
        'LOW',
        'MEDIUM',
        'HIGH',
        'CRITICAL'
    ] as Set

    static String severities(String value, String label = 'Trivy severities') {
        if (!value) {
            throw new IllegalArgumentException("${label} cannot be empty")
        }

        List<String> values = value
            .split(',')
            .collect { item -> item.trim().toUpperCase() }

        if (values.isEmpty() || values.any { item -> !item || !ALLOWED_SEVERITIES.contains(item) }) {
            throw new IllegalArgumentException(
                "Invalid ${label}: ${value}. Allowed values: ${ALLOWED_SEVERITIES.join(',')}"
            )
        }

        return values.unique().join(',')
    }

    static String timeout(String value, String label = 'Trivy timeout') {
        if (!value || !(value ==~ /^[0-9]+[smh]$/)) {
            throw new IllegalArgumentException("Invalid ${label}: ${value}")
        }

        return value
    }

    static String cachePath(String path, String label = 'Trivy cache directory') {
        if (!path) {
            throw new IllegalArgumentException("${label} cannot be empty")
        }

        if (path.contains('..') || path.startsWith('-')) {
            throw new IllegalArgumentException("Invalid ${label}: ${path}")
        }

        if (!(path ==~ /^[A-Za-z0-9._\/-]+$/)) {
            throw new IllegalArgumentException("Invalid characters in ${label}: ${path}")
        }

        return path
    }

    static List<String> filePatterns(List values, String label = 'Trivy file pattern') {
        List<String> patterns = values.collect { value ->
            filePattern(value.toString(), label)
        }

        if (patterns.size() != patterns.unique().size()) {
            throw new IllegalArgumentException("Duplicate ${label} values are not allowed: ${patterns}")
        }

        return patterns
    }

    static String filePattern(String value, String label = 'Trivy file pattern') {
        if (!value) {
            throw new IllegalArgumentException("${label} cannot be empty")
        }

        if (value.contains('..') || value.startsWith('-')) {
            throw new IllegalArgumentException("Invalid ${label}: ${value}")
        }

        if (!(value ==~ /^[A-Za-z0-9._:*?\\\/-]+$/)) {
            throw new IllegalArgumentException("Invalid characters in ${label}: ${value}")
        }

        return value
    }
}
