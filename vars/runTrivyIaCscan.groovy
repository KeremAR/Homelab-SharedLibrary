#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Run Trivy IaC/misconfiguration scanning.
 *
 * Use this in repositories that actually contain Kubernetes manifests, Helm
 * charts, Terraform, or other infrastructure files. Application source repos
 * without manifests usually do not need this stage.
 *
 * @param config Map containing:
 *   - targets: Repository-relative files/directories to scan (default: ['.'])
 *   - severities: CSV severity list (default: 'MEDIUM,HIGH,CRITICAL')
 *   - failOnIssues: Fail build when misconfigurations are found (default: true)
 *   - timeout: Trivy timeout (default: '10m')
 *   - skipDirs: Repository-relative directories to skip
 *   - cacheDir: Persistent Trivy cache path (default: '/home/jenkins/.cache/trivy')
 *   - container: Jenkins Kubernetes container name (default: 'trivy')
 *   - failFast: Whether sibling target scans stop after the first failure (default: true)
 */
def call(Map config = [:]) {
    List targets = Validation.uniqueRelativePaths(config.targets ?: ['.'], 'Trivy IaC target')
    String severities = trivyCsv((config.severities ?: 'MEDIUM,HIGH,CRITICAL').toString(), 'Trivy severities')
    boolean failBuild = config.get('failOnIssues', true)
    String timeout = trivyTimeout((config.timeout ?: '10m').toString(), 'Trivy timeout')
    List skipDirs = Validation.uniqueRelativePaths(
        config.skipDirs ?: ['node_modules', '.venvs', 'venv', '.git', '__pycache__', 'coverage-reports'],
        'Trivy skip directory'
    )
    String cacheDir = trivyCachePath((config.cacheDir ?: '/home/jenkins/.cache/trivy').toString(), 'Trivy cache directory')
    String containerName = config.container ?: 'trivy'
    boolean failFast = config.get('failFast', true)
    int exitCode = failBuild ? 1 : 0
    String skipDirFlags = skipDirs.collect { dir -> "--skip-dirs ${Validation.shellQuote(dir)}" }.join(' ')

    Map branches = [:]
    targets.each { target ->
        branches["Trivy IaC: ${target}"] = {
            container(containerName) {
                if (!fileExists(target)) {
                    error "Trivy IaC target does not exist: ${target}"
                }

                String isolatedCacheDir = ".trivy-cache-iac-${UUID.randomUUID().toString()}"

                withEnv([
                    "TRIVY_SOURCE_CACHE=${cacheDir}",
                    "TRIVY_ISOLATED_CACHE=${env.WORKSPACE}/${isolatedCacheDir}",
                    "TRIVY_TARGET=${target}"
                ]) {
                    try {
                        sh(
                            label: "Trivy IaC scan: ${target}",
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
                                    --scanners misconfig \\
                                    --timeout ${Validation.shellQuote(timeout)} \\
                                    "\$TRIVY_TARGET"
                            """
                        )
                    } finally {
                        sh(
                            label: "Clean Trivy IaC cache: ${target}",
                            script: 'set -eu\nrm -rf "$TRIVY_ISOLATED_CACHE"'
                        )
                    }
                }
            }
        }
    }

    parallel branches + [failFast: failFast]
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
