#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Run SonarQube analysis and optionally wait for the Quality Gate result.
 *
 * This library writes a small sonar-project.properties file from Jenkinsfile
 * configuration, runs the Jenkins-managed SonarQube Scanner tool, and lets the
 * Jenkins SonarQube plugin inject the server URL and token.
 *
 * ANALYSIS BEHAVIOR:
 * - Uses `withSonarQubeEnv(serverName)` for SonarQube URL and credentials
 * - Uses `tool scannerName` for the Jenkins-installed SonarQube Scanner
 * - Generates sonar-project.properties in the workspace
 * - Imports Python coverage XML reports when configured
 * - Waits for the Quality Gate by default
 *
 * SECURITY:
 * - Validates repository-relative paths and glob patterns
 * - Rejects unsafe property keys and multiline property values
 * - Fails early when configured coverage reports are missing
 * - Avoids project-specific hardcoded exclusions in the library
 *
 * @param config Map containing:
 *   - projectKey: REQUIRED - SonarQube project key
 *   - projectName: Optional SonarQube display name
 *   - projectVersion: Optional project version
 *   - scannerName: Jenkins tool name (default: 'SonarQube Scanner')
 *   - serverName: Jenkins SonarQube server name (default: 'sonarqube')
 *   - sources: Repository-relative source directories (default: ['.'])
 *   - exclusions: SonarQube exclusion globs
 *   - coverageReports: Python coverage XML report paths
 *   - requireCoverageReports: Fail if coverage reports are missing (default: true when coverageReports is set)
 *   - cpdExclusions: SonarQube duplicate-code exclusion globs
 *   - testSources: Optional repository-relative test source directories
 *   - testInclusions: Optional test inclusion globs
 *   - extraProperties: Optional Map of additional sonar.* properties
 *   - fetchIssues: Fetch and archive SonarQube issues after analysis (default: false)
 *   - fetchIssuesConfig: Optional Map passed to fetchSonarQubeIssues
 *   - branch: Optional branch filter passed to issue fetch
 *   - pullRequest: Optional pull request filter passed to issue fetch
 *   - inNewCodePeriod: Fetch only new-code issues when supported (default: false)
 *   - waitForQualityGate: Wait for Quality Gate result (default: true)
 *   - abortPipeline: Abort pipeline on failed Quality Gate (default: true)
 *   - timeoutMinutes: Timeout for scan and Quality Gate wait (default: 15)
 *   - propertiesFile: Generated properties file path (default: 'sonar-project.properties')
 *   - container: Optional Kubernetes container name. If omitted, current/default container is used.
 *
 * @example
 * runSonarQube(
 *     projectKey: 'homelab-app',
 *     sources: ['user-service', 'todo-service', 'frontend'],
 *     coverageReports: [
 *         'coverage-reports/user-service/coverage.xml',
 *         'coverage-reports/todo-service/coverage.xml'
 *     ]
 * )
 */
def call(Map config = [:]) {
    Map settings = normalizeConfig(config)

    Closure runScanner = {
        validateInputFiles(settings)
        writeSonarProperties(settings)

        timeout(time: settings.timeoutMinutes, unit: 'MINUTES') {
            withSonarQubeEnv(settings.serverName) {
                String scannerHome = tool settings.scannerName

                withEnv([
                    "SONAR_SCANNER_HOME=${scannerHome}",
                    "SONAR_PROJECT_SETTINGS=${settings.propertiesFile}"
                ]) {
                    sh(
                        label: 'SonarQube scan',
                        script: '''
                            set -eu

                            if [ ! -x "$SONAR_SCANNER_HOME/bin/sonar-scanner" ]; then
                                echo "SonarQube scanner executable was not found: $SONAR_SCANNER_HOME/bin/sonar-scanner"
                                exit 1
                            fi

                            "$SONAR_SCANNER_HOME/bin/sonar-scanner" \
                                -Dproject.settings="$SONAR_PROJECT_SETTINGS"
                        '''
                    )
                }
            }
        }
    }

    if (settings.container) {
        container(settings.container) {
            runScanner()
        }
    } else {
        runScanner()
    }

    def qualityGate = null
    String qualityGateFailureMessage = ''
    if (settings.waitForQualityGate) {
        echo 'Waiting for SonarQube Quality Gate result'
        timeout(time: settings.timeoutMinutes, unit: 'MINUTES') {
            qualityGate = waitForQualityGate abortPipeline: false
        }
    }

    if (qualityGate) {
        echo "SonarQube Quality Gate: ${qualityGate.status}"

        if (qualityGate.status != 'OK') {
            qualityGateFailureMessage = "SonarQube Quality Gate failed: ${qualityGate.status}"
        }
    }

    try {
        maybeFetchIssues(settings)
    } catch (fetchError) {
        if (!qualityGateFailureMessage) {
            throw fetchError
        }

        echo "SonarQube issue fetch also failed: ${fetchError.message}"
    }

    if (qualityGateFailureMessage) {
        if (settings.abortPipeline) {
            error qualityGateFailureMessage
        }

        unstable qualityGateFailureMessage
    }
}

private Map normalizeConfig(Map config) {
    String projectKey = validateProjectKey(config.projectKey)
    String scannerName = validatePlainValue((config.scannerName ?: 'SonarQube Scanner').toString(), 'scannerName')
    String serverName = validatePlainValue((config.serverName ?: 'sonarqube').toString(), 'serverName')
    String propertiesFile = Validation.relativePath((config.propertiesFile ?: 'sonar-project.properties').toString(), 'SonarQube properties file')
    String containerName = config.container ? validatePlainValue(config.container.toString(), 'container') : null

    List<String> sources = Validation.uniqueRelativePaths(asList(config.sources ?: ['.']), 'SonarQube source')
    List<String> exclusions = Validation.uniqueSafeGlobs(
        asList(config.exclusions ?: defaultExclusions()),
        'SonarQube exclusion'
    )
    List<String> coverageReports = Validation.uniqueRelativePaths(
        asList(config.coverageReports ?: []),
        'SonarQube coverage report'
    )
    List<String> cpdExclusions = Validation.uniqueSafeGlobs(
        asList(config.cpdExclusions ?: []),
        'SonarQube CPD exclusion'
    )
    List<String> testSources = Validation.uniqueRelativePaths(
        asList(config.testSources ?: []),
        'SonarQube test source'
    )
    List<String> testInclusions = Validation.uniqueSafeGlobs(
        asList(config.testInclusions ?: []),
        'SonarQube test inclusion'
    )

    boolean requireCoverageReports = config.containsKey('requireCoverageReports') ?
        config.requireCoverageReports as boolean :
        !coverageReports.isEmpty()

    return [
        projectKey: projectKey,
        projectName: config.projectName ? validatePropertyValue(config.projectName.toString(), 'projectName') : null,
        projectVersion: config.projectVersion ? validatePropertyValue(config.projectVersion.toString(), 'projectVersion') : null,
        scannerName: scannerName,
        serverName: serverName,
        sources: sources,
        exclusions: exclusions,
        coverageReports: coverageReports,
        requireCoverageReports: requireCoverageReports,
        cpdExclusions: cpdExclusions,
        testSources: testSources,
        testInclusions: testInclusions,
        extraProperties: normalizeExtraProperties(config.extraProperties ?: [:]),
        fetchIssues: config.get('fetchIssues', false) as boolean,
        fetchIssuesConfig: normalizeFetchIssuesConfig(config.fetchIssuesConfig ?: [:]),
        branch: config.containsKey('branch') ? validateOptionalBranch(config.branch) : null,
        pullRequest: config.containsKey('pullRequest') ? validateOptionalPullRequest(config.pullRequest) : null,
        inNewCodePeriod: config.get('inNewCodePeriod', false) as boolean,
        waitForQualityGate: config.get('waitForQualityGate', true) as boolean,
        abortPipeline: config.get('abortPipeline', true) as boolean,
        timeoutMinutes: parsePositiveInteger(config.timeoutMinutes ?: 15, 'timeoutMinutes'),
        propertiesFile: propertiesFile,
        container: containerName
    ]
}

private void maybeFetchIssues(Map settings) {
    if (!settings.fetchIssues) {
        return
    }

    if (!settings.waitForQualityGate) {
        echo 'SonarQube issue fetch skipped because waitForQualityGate is false. Fetching immediately after scanner upload can return stale issues.'
        return
    }

    withSonarQubeEnv(settings.serverName) {
        Map fetchConfig = new LinkedHashMap(settings.fetchIssuesConfig)
        fetchConfig.projectKey = settings.projectKey
        if (!fetchConfig.containsKey('branch') && settings.branch) {
            fetchConfig.branch = settings.branch
        }
        if (!fetchConfig.containsKey('pullRequest') && settings.pullRequest) {
            fetchConfig.pullRequest = settings.pullRequest
        }
        fetchConfig.inNewCodePeriod = fetchConfig.containsKey('inNewCodePeriod') ? fetchConfig.inNewCodePeriod : settings.inNewCodePeriod

        try {
            fetchSonarQubeIssues(fetchConfig)
        } catch (e) {
            if (fetchConfig.get('failOnError', false) as boolean) {
                throw e
            }

            echo "SonarQube issue fetch failed but will not fail the build: ${e.message}"
        }
    }
}

private void writeSonarProperties(Map settings) {
    Map<String, String> properties = new LinkedHashMap<>()
    properties['sonar.projectKey'] = settings.projectKey

    if (settings.projectName) {
        properties['sonar.projectName'] = settings.projectName
    }
    if (settings.projectVersion) {
        properties['sonar.projectVersion'] = settings.projectVersion
    }

    properties['sonar.sources'] = joinCsv(settings.sources)
    properties['sonar.exclusions'] = joinCsv(settings.exclusions)

    if (!settings.coverageReports.isEmpty()) {
        properties['sonar.python.coverage.reportPaths'] = joinCsv(settings.coverageReports)
    }
    if (!settings.cpdExclusions.isEmpty()) {
        properties['sonar.cpd.exclusions'] = joinCsv(settings.cpdExclusions)
    }
    if (!settings.testSources.isEmpty()) {
        properties['sonar.tests'] = joinCsv(settings.testSources)
    }
    if (!settings.testInclusions.isEmpty()) {
        properties['sonar.test.inclusions'] = joinCsv(settings.testInclusions)
    }

    properties.putAll(settings.extraProperties)

    String content = properties.collect { key, value ->
        "${key}=${value}"
    }.join('\n') + '\n'

    writeFile file: settings.propertiesFile, text: content
}

private void validateInputFiles(Map settings) {
    settings.sources.each { source ->
        if (source != '.' && !fileExists(source)) {
            error "SonarQube source path does not exist: ${source}"
        }
    }

    if (settings.requireCoverageReports) {
        settings.coverageReports.each { report ->
            if (!fileExists(report)) {
                error "SonarQube coverage report does not exist: ${report}"
            }
        }
    }
}

private Map<String, String> normalizeExtraProperties(Object rawProperties) {
    if (!(rawProperties instanceof Map)) {
        throw new IllegalArgumentException('extraProperties must be a Map')
    }

    Map<String, String> normalized = new LinkedHashMap<>()
    rawProperties.each { key, value ->
        if (value == null) {
            throw new IllegalArgumentException("SonarQube property value cannot be null for ${key}")
        }

        String propertyKey = validatePropertyKey(key.toString())
        if (managedPropertyKeys().contains(propertyKey)) {
            throw new IllegalArgumentException("Use the dedicated runSonarQube parameter instead of extraProperties for ${propertyKey}")
        }
        if (forbiddenPropertyKeys().contains(propertyKey) || forbiddenPropertyPrefixes().any { prefix -> propertyKey.startsWith(prefix) }) {
            throw new IllegalArgumentException("Security-sensitive SonarQube property cannot be set through extraProperties: ${propertyKey}")
        }
        normalized[propertyKey] = validatePropertyValue(value.toString(), propertyKey)
    }

    return normalized
}

private Map normalizeFetchIssuesConfig(Object rawConfig) {
    if (!(rawConfig instanceof Map)) {
        throw new IllegalArgumentException('fetchIssuesConfig must be a Map')
    }

    return new LinkedHashMap(rawConfig)
}

private String validateProjectKey(Object value) {
    if (!value) {
        throw new IllegalArgumentException('projectKey is required')
    }

    String projectKey = value.toString()
    if (projectKey.startsWith('-') || !(projectKey ==~ /^[A-Za-z0-9_.:-]+$/)) {
        throw new IllegalArgumentException("Invalid SonarQube projectKey: ${projectKey}")
    }

    return projectKey
}

private String validatePropertyKey(String key) {
    if (!key || key.startsWith('-') || !(key ==~ /^[A-Za-z0-9_.-]+$/)) {
        throw new IllegalArgumentException("Invalid SonarQube property key: ${key}")
    }

    return key
}

private String validatePropertyValue(String value, String label) {
    if (value.contains('\n') || value.contains('\r')) {
        throw new IllegalArgumentException("SonarQube property value cannot contain newlines for ${label}")
    }

    return value
}

private String validatePlainValue(String value, String label) {
    if (!value) {
        throw new IllegalArgumentException("${label} cannot be empty")
    }

    return validatePropertyValue(value, label)
}

private String validateOptionalBranch(Object value) {
    if (value == null || value.toString().trim() == '') {
        return null
    }

    String branch = validatePropertyValue(value.toString(), 'branch')
    if (branch.startsWith('-') || !(branch ==~ /^[A-Za-z0-9._\/-]+$/)) {
        throw new IllegalArgumentException("Invalid SonarQube branch value: ${branch}")
    }

    return branch
}

private String validateOptionalPullRequest(Object value) {
    if (value == null || value.toString().trim() == '') {
        return null
    }

    String pullRequest = validatePropertyValue(value.toString(), 'pullRequest')
    if (pullRequest.startsWith('-') || !(pullRequest ==~ /^[A-Za-z0-9._:-]+$/)) {
        throw new IllegalArgumentException("Invalid SonarQube pullRequest value: ${pullRequest}")
    }

    return pullRequest
}

private Integer parsePositiveInteger(Object value, String label) {
    if (!(value.toString() ==~ /^[0-9]+$/)) {
        throw new IllegalArgumentException("${label} must be a positive integer: ${value}")
    }

    Integer parsed = value.toString().toInteger()
    if (parsed < 1 || parsed > 120) {
        throw new IllegalArgumentException("${label} must be between 1 and 120: ${value}")
    }

    return parsed
}

private List<String> defaultExclusions() {
    return [
        '**/node_modules/**',
        '**/.venv/**',
        '**/.venvs/**',
        '**/venv/**',
        '**/__pycache__/**',
        '.git/**',
        'coverage-reports/**'
    ]
}

private List asList(Object value) {
    if (value == null) {
        return []
    }

    if (value instanceof List) {
        return value
    }

    return [value]
}

private Set<String> managedPropertyKeys() {
    return [
        'sonar.projectKey',
        'sonar.projectName',
        'sonar.projectVersion',
        'sonar.sources',
        'sonar.exclusions',
        'sonar.python.coverage.reportPaths',
        'sonar.cpd.exclusions',
        'sonar.tests',
        'sonar.test.inclusions'
    ] as Set
}

private Set<String> forbiddenPropertyKeys() {
    return [
        'sonar.token',
        'sonar.login',
        'sonar.password',
        'sonar.host.url',
        'sonar.projectBaseDir',
        'sonar.working.directory',
        'sonar.userHome',
        'sonar.scanner.metadataFilePath'
    ] as Set
}

private List<String> forbiddenPropertyPrefixes() {
    return [
        'sonar.auth.',
        'sonar.scanner.'
    ]
}

private String joinCsv(List<String> values) {
    return values.join(',')
}
