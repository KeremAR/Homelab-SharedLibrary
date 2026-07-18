#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Run Python formatting and lint checks for one or more repository paths.
 *
 * This library runs Black and Flake8 directly inside the Jenkins Kubernetes
 * agent's Python container. It does not use Docker-in-Docker and does not
 * install tools at runtime; the tools should be baked into the CI runner image.
 *
 * LINT BEHAVIOR:
 * - Runs `black --check .`
 * - Runs `flake8 .`
 * - Executes each target path in parallel
 * - Reads project-specific policy from repo files such as pyproject.toml and .flake8
 *
 * SECURITY:
 * - Validates paths before passing them to Jenkins dir()
 * - Only repository-relative paths are allowed
 * - No privileged container or Docker daemon is used
 *
 * @param config Map containing:
 *   - targets: REQUIRED - List of repository-relative paths to lint
 *   - pythonTargets: Backward-compatible alias for targets
 *   - container: Container name to run in (default: 'python')
 *   - failFast: Whether parallel branches should fail fast (default: true)
 *
 * @example
 * runPythonLinting(
 *     targets: ['user-service', 'todo-service']
 * )
 */
def call(Map config = [:]) {
    List targets = config.targets ?: config.pythonTargets ?: []
    if (targets.isEmpty()) {
        error 'runPythonLinting requires targets, for example: [targets: ["user-service", "todo-service"]]'
    }

    String containerName = config.container ?: 'python'
    boolean failFast = config.get('failFast', true)

    List safeTargets = Validation.uniqueRelativePaths(targets, 'Python lint target')

    Map branches = [:]
    safeTargets.each { safeTarget ->

        branches["Python lint: ${safeTarget}"] = {
            container(containerName) {
                if (!fileExists(safeTarget)) {
                    error "Python lint target does not exist: ${safeTarget}"
                }

                dir(safeTarget) {
                    sh(
                        label: "Black: ${safeTarget}",
                        script: '''
                            set -eu
                            black --check --diff .
                        '''
                    )
                    sh(
                        label: "Flake8: ${safeTarget}",
                        script: '''
                            set -eu
                            flake8 .
                        '''
                    )
                }
            }
        }
    }

    parallel branches + [failFast: failFast]
}
