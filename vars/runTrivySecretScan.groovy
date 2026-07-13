#!/usr/bin/env groovy

import com.company.jenkins.TrivyValidation
import com.company.jenkins.Validation

/**
 * Run Trivy secret scanning against repository files.
 *
 * Secret scanning does not need the Trivy vulnerability DB. It scans plaintext
 * source files with Trivy's built-in secret rules.
 *
 * @param config Map containing:
 *   - target: Repository-relative path to scan (default: '.')
 *   - severities: CSV severity list (default: 'UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL')
 *   - failOnSecrets: Fail build when matching secrets are found (default: true)
 *   - timeout: Trivy timeout (default: '10m')
 *   - skipDirs: Repository-relative glob directories to skip
 *   - container: Jenkins Kubernetes container name (default: 'trivy')
 */
def call(Map config = [:]) {
    String target = Validation.relativePath((config.target ?: '.').toString(), 'Trivy secret scan target')
    String severities = TrivyValidation.severities(
        (config.severities ?: 'UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL').toString(),
        'Trivy secret severities'
    )
    boolean failBuild = config.get('failOnSecrets', true)
    String timeout = TrivyValidation.timeout((config.timeout ?: '10m').toString(), 'Trivy timeout')
    List skipDirs = Validation.uniqueSafeGlobs(
        config.skipDirs ?: ['**/node_modules', '**/.venv', '**/.venvs', '**/venv', '**/__pycache__', '.git', 'coverage-reports'],
        'Trivy skip directory'
    )
    String containerName = config.container ?: 'trivy'
    int exitCode = failBuild ? 1 : 0
    String skipDirFlags = skipDirs.collect { dir -> "--skip-dirs ${Validation.shellQuote(dir)}" }.join(' ')

    container(containerName) {
        if (!fileExists(target)) {
            error "Trivy secret scan target does not exist: ${target}"
        }

        withEnv(["TRIVY_TARGET=${target}"]) {
            sh(
                label: "Trivy secret scan: ${target}",
                script: """
                    set -eu
                    cd "\$WORKSPACE"
                    trivy fs \\
                        ${skipDirFlags} \\
                        --exit-code ${exitCode} \\
                        --severity ${Validation.shellQuote(severities)} \\
                        --scanners secret \\
                        --timeout ${Validation.shellQuote(timeout)} \\
                        "\$TRIVY_TARGET"
                """
            )
        }
    }
}
