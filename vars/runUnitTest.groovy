#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Run Python unit tests for one or more service directories with coverage.
 *
 * This library runs pytest inside the Jenkins Kubernetes agent's Python
 * container. It does not build Docker test images. Each build creates a fresh
 * virtualenv in the workspace while pip downloads/wheels are cached on a
 * PVC-backed pip cache.
 *
 * TEST BEHAVIOR:
 * - Runs `pytest` for each configured service
 * - Generates JUnit XML for Jenkins test reporting
 * - Generates coverage XML for future SonarQube ingestion
 * - Supports service-specific requirements, test path, coverage config, and threshold
 * - Executes each service in parallel
 *
 * CACHE BEHAVIOR:
 * - Uses persistent pip cache, not persistent venv cache
 * - Creates an ephemeral venv under the workspace for each build
 * - Avoids sharing executable environments between jobs
 *
 * SECURITY:
 * - Validates paths before using them in shell commands
 * - Only repository-relative service paths are allowed
 * - Does not use Docker-in-Docker or privileged containers
 *
 * @param config Map containing:
 *   - services: REQUIRED - List of service maps
 *   - requirementsFile: Default requirements file (default: '<service>/requirements-test.txt')
 *   - coverageDir: Repository-relative output directory (default: 'coverage-reports')
 *   - container: Container name to run in (default: 'python')
 *   - pipCacheDir: Mounted pip cache path (default: '/cache/pip')
 *   - failFast: Whether parallel branches should fail fast (default: true)
 *
 * Service map keys:
 *   - name: REQUIRED - Service name used for reporting
 *   - target: Service directory (default: name)
 *   - requirementsFile: Service requirements file
 *   - testPath: Path passed to pytest from inside target (default: '.')
 *   - coverageConfig: Service coverage.py config file (default: '<target>/.coveragerc')
 *   - coverageThreshold: REQUIRED - Service-specific minimum coverage percentage
 *
 * @example
 * runUnitTest(
 *     services: [
 *         [
 *             name: 'user-service',
 *             requirementsFile: 'user-service/requirements-test.txt',
 *             testPath: '.',
 *             coverageThreshold: 70
 *         ]
 *     ],
 *     failFast: false
 * )
 */
def call(Map config = [:]) {
    if (config.containsKey('coverageThreshold')) {
        error 'Top-level coverageThreshold is not supported. Set coverageThreshold inside each service map.'
    }

    List serviceConfigs = config.services ?: []
    if (serviceConfigs.isEmpty()) {
        error 'runUnitTest requires services, for example: [services: [[name: "user-service"]]]'
    }

    String containerName = config.container ?: 'python'
    String coverageDir = Validation.relativePath((config.coverageDir ?: 'coverage-reports').toString(), 'Coverage report directory')
    String pipCacheDir = cachePath((config.pipCacheDir ?: '/cache/pip').toString(), 'pipCacheDir')
    String defaultRequirementsFile = config.requirementsFile?.toString()
    boolean failFast = config.get('failFast', true)

    List normalizedServices = normalizeServices(
        serviceConfigs,
        defaultRequirementsFile
    )

    validateUniqueServiceNames(normalizedServices)

    Map branches = [:]
    normalizedServices.each { service ->
        String reportDir = "${coverageDir}/${service.reportName}"

        branches["Unit test: ${service.name}"] = {
            container(containerName) {
                validateServiceFiles(service)

                int status = 0
                withEnv([
                    "UNIT_SERVICE=${service.name}",
                    "UNIT_REPORT_NAME=${service.reportName}",
                    "UNIT_TARGET=${service.target}",
                    "REQUIREMENTS_FILE=${service.requirementsFile}",
                    "TEST_PATH=${service.testPath}",
                    "COVERAGE_RCFILE=${env.WORKSPACE}/${service.coverageConfig}",
                    "REPORT_DIR=${reportDir}",
                    "PIP_CACHE_DIR=${pipCacheDir}",
                    "PIP_NO_CACHE_DIR=false",
                    "COVERAGE_THRESHOLD=${service.coverageThreshold == null ? '' : service.coverageThreshold.toString()}"
                ]) {
                    status = sh(
                        label: "Pytest: ${service.name}",
                        returnStatus: true,
                        script: '''
                            set -eu

                            rm -rf "$WORKSPACE/$REPORT_DIR"
                            mkdir -p "$WORKSPACE/$REPORT_DIR" "$WORKSPACE/.venvs" "$PIP_CACHE_DIR"

                            VENV_PATH="$WORKSPACE/.venvs/$UNIT_REPORT_NAME"
                            rm -rf "$VENV_PATH"
                            python -m venv "$VENV_PATH"

                            "$VENV_PATH/bin/python" -m pip install \
                                --cache-dir "$PIP_CACHE_DIR" \
                                -r "$WORKSPACE/$REQUIREMENTS_FILE"

                            COVERAGE_FAIL_UNDER=""
                            if [ -n "$COVERAGE_THRESHOLD" ]; then
                                COVERAGE_FAIL_UNDER="--cov-fail-under=$COVERAGE_THRESHOLD"
                            fi

                            RESOLVED_TEST_PATH="$UNIT_TARGET"
                            if [ "$TEST_PATH" != "." ]; then
                                RESOLVED_TEST_PATH="$UNIT_TARGET/$TEST_PATH"
                            fi

                            cd "$WORKSPACE"

                            PYTHONPATH="$WORKSPACE/$UNIT_TARGET${PYTHONPATH:+:$PYTHONPATH}" \
                            "$VENV_PATH/bin/python" -m pytest -p no:cacheprovider "$RESOLVED_TEST_PATH" \
                                --junitxml="$WORKSPACE/$REPORT_DIR/junit.xml" \
                                --cov="$UNIT_TARGET" \
                                --cov-report=xml:"$WORKSPACE/$REPORT_DIR/coverage.xml" \
                                --cov-report=term-missing \
                                $COVERAGE_FAIL_UNDER

                            COVERAGE_XML="$WORKSPACE/$REPORT_DIR/coverage.xml"
                            if [ -f "$COVERAGE_XML" ]; then
                                python - <<'PY'
import os
import xml.etree.ElementTree as ET

workspace = os.environ["WORKSPACE"]
target = os.environ["UNIT_TARGET"].strip("/")
coverage_xml = os.path.join(workspace, os.environ["REPORT_DIR"], "coverage.xml")

tree = ET.parse(coverage_xml)
root = tree.getroot()

sources = root.find("sources")
if sources is not None:
    for source in list(sources):
        sources.remove(source)
    source = ET.SubElement(sources, "source")
    source.text = workspace

for class_element in root.findall(".//class"):
    filename = class_element.get("filename")
    if not filename:
        continue

    normalized = filename.replace("\\\\", "/")
    if os.path.isabs(normalized):
        normalized = os.path.relpath(normalized, workspace).replace("\\\\", "/")
    elif not normalized.startswith(target + "/"):
        normalized = "%s/%s" % (target, normalized.lstrip("./"))

    class_element.set("filename", normalized)

tree.write(coverage_xml, encoding="utf-8", xml_declaration=True)
PY
                            fi
                        '''
                    )
                }

                String junitPath = "${reportDir}/junit.xml"
                String coveragePath = "${reportDir}/coverage.xml"

                if (fileExists(junitPath)) {
                    junit(
                        allowEmptyResults: false,
                        testResults: junitPath
                    )
                } else {
                    echo "JUnit report was not generated for ${service.name}"
                }

                archiveArtifacts(
                    allowEmptyArchive: true,
                    artifacts: "${reportDir}/*.xml"
                )

                if (status == 0 && !fileExists(junitPath)) {
                    error "Tests succeeded but JUnit report is missing for ${service.name}"
                }

                if (status == 0 && !fileExists(coveragePath)) {
                    error "Tests succeeded but coverage report is missing for ${service.name}"
                }

                if (status != 0) {
                    error "Unit tests failed for ${service.name}"
                }
            }
        }
    }

    parallel branches + [failFast: failFast]
}

private List normalizeServices(
    List services,
    String defaultRequirementsFile
) {
    return services.collect { rawService ->
        if (!(rawService instanceof Map)) {
            throw new IllegalArgumentException("Unit test service must be a Map: ${rawService}")
        }

        Map service = new LinkedHashMap(rawService)

        String name = service.name?.toString()
        if (!name) {
            throw new IllegalArgumentException('Unit test service name cannot be empty')
        }
        name = Validation.relativePath(name, 'Unit test service name')

        String target = Validation.relativePath((service.target ?: name).toString(), "Unit test target for ${name}")
        String testPath = Validation.relativePath((service.testPath ?: '.').toString(), "Unit test path for ${name}")
        String coverageConfig = Validation.relativePath((service.coverageConfig ?: "${target}/.coveragerc").toString(), "Coverage config for ${name}")
        String requirementsFile = Validation.relativePath(
            (service.requirementsFile ?: defaultRequirementsFile ?: "${target}/requirements-test.txt").toString(),
            "Requirements file for ${name}"
        )
        if (!service.containsKey('coverageThreshold')) {
            throw new IllegalArgumentException("coverageThreshold is required for ${name}")
        }
        Integer coverageThreshold = parseRequiredInteger(service.coverageThreshold, "Coverage threshold for ${name}")

        return [
            name: name,
            target: target,
            testPath: testPath,
            coverageConfig: coverageConfig,
            requirementsFile: requirementsFile,
            coverageThreshold: coverageThreshold,
            reportName: name.replaceAll(/[^A-Za-z0-9_.-]/, '-')
        ]
    }
}

private void validateServiceFiles(Map service) {
    if (!fileExists(service.target)) {
        error "Python unit test target does not exist for ${service.name}: ${service.target}"
    }

    String resolvedTestPath = service.testPath == '.' ? service.target : "${service.target}/${service.testPath}"
    if (!fileExists(resolvedTestPath)) {
        error "Python test path does not exist for ${service.name}: ${resolvedTestPath}"
    }

    if (!fileExists(service.coverageConfig)) {
        error "Coverage config does not exist for ${service.name}: ${service.coverageConfig}"
    }

    if (!fileExists(service.requirementsFile)) {
        error "Python requirements file does not exist for ${service.name}: ${service.requirementsFile}"
    }
}

private void validateUniqueServiceNames(List services) {
    List names = services.collect { service -> service.name }
    if (names.size() != names.unique().size()) {
        throw new IllegalArgumentException("Duplicate unit test service names are not allowed: ${names}")
    }
}

private Integer parseOptionalInteger(Object value, String label) {
    if (value == null || value.toString().trim() == '') {
        return null
    }

    if (!(value.toString() ==~ /^[0-9]+$/)) {
        throw new IllegalArgumentException("${label} must be a non-negative integer: ${value}")
    }

    Integer parsed = value.toString().toInteger()
    if (parsed > 100) {
        throw new IllegalArgumentException("${label} must be between 0 and 100: ${value}")
    }

    return parsed
}

private Integer parseRequiredInteger(Object value, String label) {
    Integer parsed = parseOptionalInteger(value, label)
    if (parsed == null) {
        throw new IllegalArgumentException("${label} cannot be empty")
    }

    return parsed
}

private String cachePath(String path, String label) {
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
