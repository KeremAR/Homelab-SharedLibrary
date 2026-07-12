#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Run Hadolint against one or more Dockerfiles.
 *
 * This library runs Hadolint directly inside the Jenkins Kubernetes agent's
 * hadolint container. It does not use Docker-in-Docker. Project-specific
 * Hadolint policy should live in a repository .hadolint.yaml file.
 *
 * LINT BEHAVIOR:
 * - Validates every Dockerfile path
 * - Fails if a configured Dockerfile does not exist
 * - Uses .hadolint.yaml when present
 * - Executes each Dockerfile lint in parallel
 *
 * SECURITY:
 * - No Docker daemon is used
 * - Paths are validated and shell-quoted
 * - Only repository-relative paths are allowed
 *
 * @param config Map containing:
 *   - dockerfiles: REQUIRED - List of Dockerfile paths
 *   - configFile: Hadolint config path (default: '.hadolint.yaml')
 *   - container: Container name to run in (default: 'hadolint')
 *   - failFast: Whether parallel branches should fail fast (default: true)
 *
 * @example
 * runHadolint(
 *     dockerfiles: ['user-service/Dockerfile', 'frontend/Dockerfile']
 * )
 */
def call(Map config = [:]) {
    List dockerfiles = config.dockerfiles ?: []
    if (dockerfiles.isEmpty()) {
        error 'runHadolint requires dockerfiles, for example: [dockerfiles: ["Dockerfile"]]'
    }

    String containerName = config.container ?: 'hadolint'
    boolean configExplicitlyProvided = config.containsKey('configFile')
    String configFile = Validation.relativePath((config.configFile ?: '.hadolint.yaml').toString(), 'Hadolint config file')
    boolean failFast = config.get('failFast', true)

    List safeDockerfiles = Validation.uniqueRelativePaths(dockerfiles, 'Dockerfile path')

    Map branches = [:]
    safeDockerfiles.each { safePath ->

        branches["Hadolint: ${safePath}"] = {
            container(containerName) {
                if (!fileExists(safePath)) {
                    error "Dockerfile does not exist: ${safePath}"
                }

                if (configExplicitlyProvided && !fileExists(configFile)) {
                    error "Hadolint config file does not exist: ${configFile}"
                }

                if (fileExists(configFile)) {
                    sh(
                        label: "Hadolint: ${safePath}",
                        script: "set -eu\nhadolint --config ${Validation.shellQuote(configFile)} ${Validation.shellQuote(safePath)}"
                    )
                } else {
                    sh(
                        label: "Hadolint: ${safePath}",
                        script: "set -eu\nhadolint ${Validation.shellQuote(safePath)}"
                    )
                }
            }
        }
    }

    parallel branches + [failFast: failFast]
}
