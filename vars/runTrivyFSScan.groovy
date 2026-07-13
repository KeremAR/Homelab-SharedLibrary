#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Run Trivy filesystem vulnerability scanning.
 *
 * This scans dependency manifests such as requirements-test.txt, package.json,
 * package-lock.json, and other supported lock files without building Docker
 * images. It uses an isolated copy of the persistent Trivy cache so multiple
 * Trivy scans can run in parallel without sharing writable DB files.
 *
 * @param config Map containing:
 *   - target: Repository-relative path to scan (default: '.')
 *   - severities: CSV severity list (default: 'HIGH,CRITICAL')
 *   - failOnVulnerabilities: Fail build when findings exist (default: true)
 *   - timeout: Trivy timeout (default: '15m')
 *   - skipDirs: Repository-relative directories to skip
 *   - cacheDir: Persistent Trivy cache path (default: '/home/jenkins/.cache/trivy')
 *   - container: Jenkins Kubernetes container name (default: 'trivy')
 */
def call(Map config = [:]) {
    String target = Validation.relativePath((config.target ?: '.').toString(), 'Trivy filesystem target')
    String severities = trivyCsv((config.severities ?: 'HIGH,CRITICAL').toString(), 'Trivy severities')
    boolean failBuild = config.get('failOnVulnerabilities', true)
    String timeout = trivyTimeout((config.timeout ?: '15m').toString(), 'Trivy timeout')
    List skipDirs = Validation.uniqueRelativePaths(
        config.skipDirs ?: ['node_modules', '.venvs', 'venv', '.git', '__pycache__', 'coverage-reports'],
        'Trivy skip directory'
    )
    String cacheDir = trivyCachePath((config.cacheDir ?: '/home/jenkins/.cache/trivy').toString(), 'Trivy cache directory')
    String containerName = config.container ?: 'trivy'
    int exitCode = failBuild ? 1 : 0
    String skipDirFlags = skipDirs.collect { dir -> "--skip-dirs ${Validation.shellQuote(dir)}" }.join(' ')

    container(containerName) {
        if (!fileExists(target)) {
            error "Trivy filesystem target does not exist: ${target}"
        }

        String isolatedCacheDir = ".trivy-cache-fs-${UUID.randomUUID().toString()}"

        withEnv([
            "TRIVY_SOURCE_CACHE=${cacheDir}",
            "TRIVY_ISOLATED_CACHE=${env.WORKSPACE}/${isolatedCacheDir}",
            "TRIVY_TARGET=${target}"
        ]) {
            try {
                sh(
                    label: "Trivy FS scan: ${target}",
                    script: """
                        set -eu
                        mkdir -p "\$TRIVY_ISOLATED_CACHE"
                        if [ -d "\$TRIVY_SOURCE_CACHE" ]; then
                            cp -a "\$TRIVY_SOURCE_CACHE"/. "\$TRIVY_ISOLATED_CACHE"/
                        fi

                        cd "\$WORKSPACE"
                        trivy fs \\
                            --skip-db-update \\
                            --cache-dir "\$TRIVY_ISOLATED_CACHE" \\
                            ${skipDirFlags} \\
                            --exit-code ${exitCode} \\
                            --severity ${Validation.shellQuote(severities)} \\
                            --scanners vuln \\
                            --timeout ${Validation.shellQuote(timeout)} \\
                            "\$TRIVY_TARGET"
                    """
                )
            } finally {
                sh(
                    label: "Clean Trivy FS cache: ${target}",
                    script: 'set -eu\nrm -rf "$TRIVY_ISOLATED_CACHE"'
                )
            }
        }
    }
}

private String trivyCsv(String value, String label) {
    if (!value || !(value ==~ /^[A-Z,]+$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}

private String trivyTimeout(String value, String label) {
    if (!value || !(value ==~ /^[0-9]+[smh]$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}

private String trivyCachePath(String path, String label) {
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
