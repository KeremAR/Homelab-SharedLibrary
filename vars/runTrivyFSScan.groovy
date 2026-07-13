#!/usr/bin/env groovy

import com.company.jenkins.TrivyValidation
import com.company.jenkins.Validation

/**
 * Run Trivy filesystem vulnerability scanning.
 *
 * This scans dependency manifests and lock files without building Docker images.
 * It uses an isolated copy of the persistent Trivy cache outside the workspace
 * so parallel scans do not share writable DB files and Trivy does not scan its
 * own temporary cache.
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

        String isolatedCacheDir = "/tmp/trivy-fs-${UUID.randomUUID().toString()}"

        withEnv([
            "TRIVY_SOURCE_CACHE=${cacheDir}",
            "TRIVY_ISOLATED_CACHE=${isolatedCacheDir}",
            "TRIVY_TARGET=${target}"
        ]) {
            try {
                sh(
                    label: "Trivy FS scan: ${target}",
                    script: """
                        set -eu
                        mkdir -p "\$TRIVY_ISOLATED_CACHE"
                        if [ -d "\$TRIVY_SOURCE_CACHE" ]; then
                            for ITEM in "\$TRIVY_SOURCE_CACHE"/* "\$TRIVY_SOURCE_CACHE"/.[!.]* "\$TRIVY_SOURCE_CACHE"/..?*; do
                                [ -e "\$ITEM" ] || continue
                                [ "\$(basename "\$ITEM")" = "lost+found" ] && continue
                                cp -R "\$ITEM" "\$TRIVY_ISOLATED_CACHE"/
                            done
                        fi

                        cd "\$WORKSPACE"
                        trivy fs \\
                            --skip-db-update \\
                            --cache-dir "\$TRIVY_ISOLATED_CACHE" \\
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
            } finally {
                int cleanupStatus = sh(
                    label: "Clean Trivy FS cache: ${target}",
                    returnStatus: true,
                    script: 'rm -rf "$TRIVY_ISOLATED_CACHE"'
                )

                if (cleanupStatus != 0) {
                    echo "WARNING: Trivy filesystem temporary cache cleanup failed."
                }
            }
        }
    }
}
