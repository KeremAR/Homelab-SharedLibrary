#!/usr/bin/env groovy

import com.company.jenkins.TrivyValidation
import com.company.jenkins.Validation

/**
 * Run Trivy IaC/misconfiguration scanning.
 *
 * Use this in repositories that actually contain Kubernetes manifests, Helm
 * charts, Terraform, or other infrastructure files. Application source repos
 * without manifests usually do not need this stage.
 * It reads the persistent Trivy cache prepared before scans and keeps Trivy's
 * runtime scan cache in memory.
 *
 * @param config Map containing:
 *   - targets: Repository-relative files/directories to scan (default: ['.'])
 *   - severities: CSV severity list (default: 'MEDIUM,HIGH,CRITICAL')
 *   - failOnIssues: Fail build when misconfigurations are found (default: true)
 *   - timeout: Trivy timeout (default: '10m')
 *   - skipDirs: Repository-relative glob directories to skip
 *   - cacheDir: Persistent Trivy cache path (default: '/home/jenkins/.cache/trivy')
 *   - container: Jenkins Kubernetes container name (default: 'trivy')
 *   - failFast: Whether sibling target scans stop after the first failure (default: true)
 */
def call(Map config = [:]) {
    List targets = Validation.uniqueRelativePaths(config.targets ?: ['.'], 'Trivy IaC target')
    String severities = TrivyValidation.severities((config.severities ?: 'MEDIUM,HIGH,CRITICAL').toString(), 'Trivy severities')
    boolean failBuild = config.get('failOnIssues', true)
    String timeout = TrivyValidation.timeout((config.timeout ?: '10m').toString(), 'Trivy timeout')
    List skipDirs = Validation.uniqueSafeGlobs(
        config.skipDirs ?: ['**/node_modules', '**/.venv', '**/.venvs', '**/venv', '**/__pycache__', '.git', 'coverage-reports'],
        'Trivy skip directory'
    )
    String cacheDir = TrivyValidation.cachePath((config.cacheDir ?: '/home/jenkins/.cache/trivy').toString(), 'Trivy cache directory')
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

                withEnv([
                    "TRIVY_CACHE_DIR=${cacheDir}",
                    "TRIVY_TARGET=${target}"
                ]) {
                    sh(
                        label: "Trivy IaC scan: ${target}",
                        script: """
                            set -eu
                            cd "\$WORKSPACE"
                            trivy fs \\
                                --skip-db-update \\
                                --cache-dir "\$TRIVY_CACHE_DIR" \\
                                --cache-backend memory \\
                                ${skipDirFlags} \\
                                --exit-code ${exitCode} \\
                                --severity ${Validation.shellQuote(severities)} \\
                                --scanners misconfig \\
                                --timeout ${Validation.shellQuote(timeout)} \\
                                "\$TRIVY_TARGET"
                        """
                    )
                }
            }
        }
    }

    parallel branches + [failFast: failFast]
}
