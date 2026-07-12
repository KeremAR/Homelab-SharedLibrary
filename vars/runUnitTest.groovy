#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Run Python unit tests for one or more service directories with coverage.
 *
 * This library runs pytest inside the Jenkins Kubernetes agent's Python
 * container. It does not build Docker test images. Dependencies are installed
 * into a PVC-backed virtualenv cache keyed by target path, Python version, and
 * the requirements file hash.
 *
 * TEST BEHAVIOR:
 * - Runs `pytest` for each target path
 * - Generates JUnit XML for Jenkins test reporting
 * - Generates coverage XML for future SonarQube ingestion
 * - Executes each target path in parallel
 *
 * CACHE BEHAVIOR:
 * - Uses `/cache/venvs` by default
 * - Reuses a venv while requirements.txt and Python minor version stay the same
 * - Creates a new venv automatically when requirements change
 *
 * SECURITY:
 * - Validates paths before using them in shell commands
 * - Only repository-relative target and requirements paths are allowed
 * - Does not use Docker-in-Docker or privileged containers
 *
 * @param config Map containing:
 *   - targets: REQUIRED - List of repository-relative Python service directories
 *   - pythonTargets: Backward-compatible alias for targets
 *   - services: Backward-compatible list of maps with a `name` field
 *   - requirementsFile: Repository-relative requirements file (default: 'requirements.txt')
 *   - coverageDir: Repository-relative output directory (default: 'coverage-reports')
 *   - container: Container name to run in (default: 'python')
 *   - venvCacheDir: Mounted venv cache path (default: '/cache/venvs')
 *   - failFast: Whether parallel branches should fail fast (default: true)
 *
 * @example
 * runUnitTest(
 *     targets: ['user-service', 'todo-service'],
 *     requirementsFile: 'requirements.txt',
 *     coverageDir: 'coverage-reports',
 *     failFast: false
 * )
 */
def call(Map config = [:]) {
    List targets = resolveTargets(config)
    if (targets.isEmpty()) {
        error 'runUnitTest requires targets, for example: [targets: ["user-service", "todo-service"]]'
    }

    String containerName = config.container ?: 'python'
    String requirementsFile = Validation.relativePath((config.requirementsFile ?: 'requirements.txt').toString(), 'Python requirements file')
    String coverageDir = Validation.relativePath((config.coverageDir ?: 'coverage-reports').toString(), 'Coverage report directory')
    String venvCacheDir = (config.venvCacheDir ?: '/cache/venvs').toString()
    boolean failFast = config.get('failFast', true)

    List safeTargets = Validation.uniqueRelativePaths(targets, 'Python unit test target')

    Map branches = [:]
    safeTargets.each { safeTarget ->
        String reportName = safeTarget.replaceAll(/[^A-Za-z0-9_.-]/, '-')
        String reportDir = "${coverageDir}/${reportName}"

        branches["Unit test: ${safeTarget}"] = {
            container(containerName) {
                if (!fileExists(safeTarget)) {
                    error "Python unit test target does not exist: ${safeTarget}"
                }

                if (!fileExists(requirementsFile)) {
                    error "Python requirements file does not exist: ${requirementsFile}"
                }

                int status = 0
                withEnv([
                    "UNIT_TARGET=${safeTarget}",
                    "REQUIREMENTS_FILE=${requirementsFile}",
                    "REPORT_DIR=${reportDir}",
                    "VENV_CACHE_DIR=${venvCacheDir}"
                ]) {
                    status = sh(
                        label: "Pytest: ${safeTarget}",
                        returnStatus: true,
                        script: '''
                            set -eu

                            mkdir -p "$WORKSPACE/$REPORT_DIR" "$VENV_CACHE_DIR"

                            REQ_HASH="$(sha256sum "$WORKSPACE/$REQUIREMENTS_FILE" | awk '{print $1}')"
                            PY_VER="$(python -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')"
                            VENV_NAME="$(printf '%s-%s-%s' "$UNIT_TARGET" "$PY_VER" "$REQ_HASH" | tr '/ ' '--' | tr -cd 'A-Za-z0-9_.-')"
                            VENV_PATH="$VENV_CACHE_DIR/$VENV_NAME"

                            if [ ! -x "$VENV_PATH/bin/python" ]; then
                                rm -rf "$VENV_PATH.tmp" "$VENV_PATH"
                                python -m venv "$VENV_PATH.tmp"
                                "$VENV_PATH.tmp/bin/python" -m pip install --upgrade pip setuptools wheel
                                "$VENV_PATH.tmp/bin/python" -m pip install -r "$WORKSPACE/$REQUIREMENTS_FILE"
                                mv "$VENV_PATH.tmp" "$VENV_PATH"
                            fi

                            PYTHONPATH="$WORKSPACE/$UNIT_TARGET${PYTHONPATH:+:$PYTHONPATH}" \
                            "$VENV_PATH/bin/python" -m pytest -p no:cacheprovider "$WORKSPACE/$UNIT_TARGET" \
                                --junitxml="$WORKSPACE/$REPORT_DIR/junit.xml" \
                                --cov="$WORKSPACE/$UNIT_TARGET" \
                                --cov-report=xml:"$WORKSPACE/$REPORT_DIR/coverage.xml" \
                                --cov-report=term-missing
                        '''
                    )
                }

                junit(
                    allowEmptyResults: true,
                    testResults: "${reportDir}/junit.xml"
                )

                archiveArtifacts(
                    allowEmptyArchive: true,
                    artifacts: "${reportDir}/coverage.xml,${reportDir}/junit.xml"
                )

                if (status != 0) {
                    error "Unit tests failed for ${safeTarget}"
                }
            }
        }
    }

    parallel branches + [failFast: failFast]
}

private List resolveTargets(Map config) {
    if (config.targets) {
        return config.targets
    }

    if (config.pythonTargets) {
        return config.pythonTargets
    }

    if (config.services) {
        return config.services.collect { service ->
            if (service instanceof Map) {
                return service.name
            }

            return service
        }
    }

    return []
}
