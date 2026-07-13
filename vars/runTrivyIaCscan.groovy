#!/usr/bin/env groovy

import com.company.jenkins.TrivyValidation
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

                String isolatedCacheDir = "/tmp/trivy-iac-${UUID.randomUUID().toString()}"

                withEnv([
                    "TRIVY_SOURCE_CACHE=${cacheDir}",
                    "TRIVY_ISOLATED_CACHE=${isolatedCacheDir}",
                    "TRIVY_TARGET=${target}"
                ]) {
                    try {
                        sh(
                            label: "Trivy IaC scan: ${target}",
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
                                    --exit-code ${exitCode} \\
                                    --severity ${Validation.shellQuote(severities)} \\
                                    --scanners misconfig \\
                                    --timeout ${Validation.shellQuote(timeout)} \\
                                    "\$TRIVY_TARGET"
                            """
                        )
                    } finally {
                        int cleanupStatus = sh(
                            label: "Clean Trivy IaC cache: ${target}",
                            returnStatus: true,
                            script: 'rm -rf "$TRIVY_ISOLATED_CACHE"'
                        )

                        if (cleanupStatus != 0) {
                            echo "WARNING: Trivy IaC temporary cache cleanup failed."
                        }
                    }
                }
            }
        }
    }

    parallel branches + [failFast: failFast]
}
