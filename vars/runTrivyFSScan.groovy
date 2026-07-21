#!/usr/bin/env groovy

import com.company.jenkins.TrivyValidation
import com.company.jenkins.Validation

/**
 * Run Trivy filesystem vulnerability scanning.
 *
 * This scans dependency manifests and lock files without building Docker images.
 * It reads the persistent Trivy DB cache prepared by ensureTrivyDB, skips DB
 * updates during scans, and keeps Trivy's scan cache in memory.
 *
 * @param config Map containing:
 *   - target: Repository-relative path to scan (default: '.')
 *   - severities: CSV severity list (default: 'HIGH,CRITICAL')
 *   - failOnVulnerabilities: Fail build when findings exist (default: true)
 *   - includeDevDeps: Include development/test dependencies (default: true)
 *   - filePatterns: Additional Trivy file patterns
 *   - timeout: Trivy timeout (default: '15m')
 *   - skipDirs: Repository-relative glob directories to skip
 *   - cacheDir: Persistent Trivy cache path (default: '/home/jenkins/.cache/trivy')
 *   - container: Jenkins Kubernetes container name (default: 'trivy')
 */
def call(Map config = [:]) {
    String target = Validation.relativePath((config.target ?: '.').toString(), 'Trivy filesystem target')
    String severities = TrivyValidation.severities((config.severities ?: 'HIGH,CRITICAL').toString(), 'Trivy severities')
    boolean failBuild = config.get('failOnVulnerabilities', true)
    boolean includeDevDeps = config.get('includeDevDeps', true)
    String timeout = TrivyValidation.timeout((config.timeout ?: '15m').toString(), 'Trivy timeout')
    List skipDirs = Validation.uniqueSafeGlobs(
        config.skipDirs ?: ['**/node_modules', '**/.venv', '**/.venvs', '**/venv', '**/__pycache__', '.git', 'coverage-reports'],
        'Trivy skip directory'
    )
    List filePatterns = TrivyValidation.filePatterns(
        config.filePatterns ?: ['pip:requirements-.*\\.txt'],
        'Trivy file pattern'
    )
    String cacheDir = TrivyValidation.cachePath((config.cacheDir ?: '/home/jenkins/.cache/trivy').toString(), 'Trivy cache directory')
    String containerName = config.container ?: 'trivy'
    int exitCode = failBuild ? 1 : 0
    String skipDirFlags = skipDirs.collect { dir -> "--skip-dirs ${Validation.shellQuote(dir)}" }.join(' ')
    String filePatternFlags = filePatterns.collect { pattern -> "--file-patterns ${Validation.shellQuote(pattern)}" }.join(' ')
    String includeDevDepsFlag = includeDevDeps ? '--include-dev-deps' : ''

    container(containerName) {
        if (!fileExists(target)) {
            error "Trivy filesystem target does not exist: ${target}"
        }

        withEnv([
            "TRIVY_CACHE_DIR=${cacheDir}",
            "TRIVY_TARGET=${target}"
        ]) {
            sh(
                label: "Trivy FS scan: ${target}",
                script: """
                    set -eu
                    cd "\$WORKSPACE"
                    trivy fs \\
                        --skip-db-update \\
                        --cache-dir "\$TRIVY_CACHE_DIR" \\
                        --cache-backend memory \\
                        ${skipDirFlags} \\
                        ${filePatternFlags} \\
                        ${includeDevDepsFlag} \\
                        --exit-code ${exitCode} \\
                        --severity ${Validation.shellQuote(severities)} \\
                        --scanners vuln \\
                        --timeout ${Validation.shellQuote(timeout)} \\
                        "\$TRIVY_TARGET"
                """
            )
        }
    }
}
