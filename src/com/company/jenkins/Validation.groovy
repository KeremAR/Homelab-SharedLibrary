package com.company.jenkins

/**
 * Shared validation helpers for Jenkins Shared Library steps.
 *
 * These helpers protect reusable pipeline functions from accidentally turning
 * project-provided config values into unsafe shell input. A Shared Library may
 * be used by many repositories, so it should reject unexpected paths and script
 * names before they reach sh(), dir(), or tool commands.
 *
 * VALIDATION BEHAVIOR:
 * - Allows only repository-relative paths
 * - Rejects absolute paths
 * - Rejects parent directory traversal
 * - Rejects values that start with '-' so paths cannot become CLI options
 * - Restricts path and npm script characters to a small safe set
 * - Detects duplicate list entries before they overwrite Jenkins parallel branch keys
 *
 * This is deliberately kept in src/ because it is a helper class used by
 * multiple vars/ global pipeline functions.
 */
class Validation implements Serializable {
    private static final String RELATIVE_PATH_PATTERN = /^[A-Za-z0-9._\/-]+$/
    private static final String SAFE_GLOB_PATTERN = /^[A-Za-z0-9._*?\/-]+$/
    private static final String NPM_SCRIPT_PATTERN = /^[A-Za-z0-9:_-]+$/

    static String relativePath(String path, String label = 'path') {
        if (!path) {
            throw new IllegalArgumentException("${label} cannot be empty")
        }

        if (path.startsWith('/') || path.contains('..')) {
            throw new IllegalArgumentException("Only repository-relative paths are allowed for ${label}: ${path}")
        }

        if (path.startsWith('-')) {
            throw new IllegalArgumentException("${label} cannot start with '-': ${path}")
        }

        if (!(path ==~ RELATIVE_PATH_PATTERN)) {
            throw new IllegalArgumentException("Invalid characters in ${label}: ${path}")
        }

        return path
    }

    static String npmScriptName(String name) {
        if (!name) {
            throw new IllegalArgumentException('npm script name cannot be empty')
        }

        if (name.startsWith('-')) {
            throw new IllegalArgumentException("npm script name cannot start with '-': ${name}")
        }

        if (!(name ==~ NPM_SCRIPT_PATTERN)) {
            throw new IllegalArgumentException("Invalid npm script name: ${name}")
        }

        return name
    }

    static List<String> uniqueRelativePaths(List values, String label = 'path') {
        List<String> paths = values.collect { value ->
            relativePath(value.toString(), label)
        }

        if (paths.size() != paths.unique().size()) {
            throw new IllegalArgumentException("Duplicate ${label} values are not allowed: ${paths}")
        }

        return paths
    }

    static String safeGlob(String value, String label = 'glob') {
        if (!value) {
            throw new IllegalArgumentException("${label} cannot be empty")
        }

        if (value.startsWith('/') || value.contains('..') || value.startsWith('-')) {
            throw new IllegalArgumentException("Only repository-relative glob patterns are allowed for ${label}: ${value}")
        }

        if (!(value ==~ SAFE_GLOB_PATTERN)) {
            throw new IllegalArgumentException("Invalid characters in ${label}: ${value}")
        }

        return value
    }

    static List<String> uniqueSafeGlobs(List values, String label = 'glob') {
        List<String> globs = values.collect { value ->
            safeGlob(value.toString(), label)
        }

        if (globs.size() != globs.unique().size()) {
            throw new IllegalArgumentException("Duplicate ${label} values are not allowed: ${globs}")
        }

        return globs
    }

    static String shellQuote(String value) {
        return "'${value.toString().replace("'", "'\"'\"'")}'"
    }
}
