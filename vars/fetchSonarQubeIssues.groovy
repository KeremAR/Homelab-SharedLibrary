#!/usr/bin/env groovy

import com.company.jenkins.Validation
import groovy.json.JsonSlurper

/**
 * Fetch SonarQube issues from the SonarQube Web API and archive the response.
 *
 * This helper is diagnostic. It does not replace Quality Gate enforcement.
 * `runSonarQube` can call it after analysis when `fetchIssues: true` is set.
 *
 * FETCH BEHAVIOR:
 * - Calls `/api/issues/search` for the configured project
 * - Stores the raw JSON response as a Jenkins artifact
 * - Prints a small readable issue summary in the build log
 * - Does not fail the build by default
 *
 * SECURITY:
 * - Passes the SonarQube token through environment variables, not shell args
 * - Validates URL, project key, paths, severities, statuses, and limits
 * - Uses Python standard library instead of curl to avoid assuming a curl image
 *
 * @param config Map containing:
 *   - projectKey: REQUIRED - SonarQube project key
 *   - sonarUrl: REQUIRED - SonarQube server URL
 *   - sonarToken: REQUIRED - SonarQube token
 *   - severities: List or comma-separated string (default: ['BLOCKER', 'CRITICAL', 'MAJOR'])
 *   - statuses: List or comma-separated string (default: ['OPEN', 'CONFIRMED'])
 *   - maxIssues: Maximum issues to fetch from the API (default: 100)
 *   - maxIssuesToPrint: Maximum issues to print in Jenkins log (default: 20)
 *   - outputFile: Repository-relative JSON output file (default: 'sonarqube-issues.json')
 *   - archive: Archive outputFile as Jenkins artifact (default: true)
 *   - failOnError: Fail the build if fetching/parsing issues fails (default: false)
 *   - timeoutSeconds: HTTP timeout in seconds (default: 20)
 *   - container: Kubernetes container name (default: 'python')
 *
 * @example
 * fetchSonarQubeIssues(
 *     projectKey: 'homelab-app',
 *     sonarUrl: env.SONAR_HOST_URL,
 *     sonarToken: env.SONAR_AUTH_TOKEN,
 *     maxIssues: 50
 * )
 */
def call(Map config = [:]) {
    Map settings = normalizeConfig(config)

    Closure fetch = {
        int status = 0

        withEnv([
            "SONAR_PROJECT_KEY=${settings.projectKey}",
            "SONAR_URL=${settings.sonarUrl}",
            "SONAR_TOKEN=${settings.sonarToken}",
            "SONAR_SEVERITIES=${settings.severities}",
            "SONAR_STATUSES=${settings.statuses}",
            "SONAR_MAX_ISSUES=${settings.maxIssues}",
            "SONAR_TIMEOUT_SECONDS=${settings.timeoutSeconds}",
            "SONAR_ISSUES_OUTPUT=${settings.outputFile}"
        ]) {
            status = sh(
                label: 'Fetch SonarQube issues',
                returnStatus: true,
                script: '''
                    set -eu

                    python - <<'PY'
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

params = {
    "componentKeys": os.environ["SONAR_PROJECT_KEY"],
    "severities": os.environ["SONAR_SEVERITIES"],
    "statuses": os.environ["SONAR_STATUSES"],
    "ps": os.environ["SONAR_MAX_ISSUES"],
}

base_url = os.environ["SONAR_URL"].rstrip("/")
url = base_url + "/api/issues/search?" + urllib.parse.urlencode(params)
token = os.environ["SONAR_TOKEN"] + ":"
auth = base64.b64encode(token.encode("utf-8")).decode("ascii")

request = urllib.request.Request(url)
request.add_header("Authorization", "Basic " + auth)

try:
    with urllib.request.urlopen(
        request,
        timeout=int(os.environ["SONAR_TIMEOUT_SECONDS"]),
    ) as response:
        body = response.read().decode("utf-8")
except urllib.error.HTTPError as error:
    print("SonarQube API returned HTTP %s" % error.code, file=sys.stderr)
    sys.exit(1)
except urllib.error.URLError as error:
    print("Could not reach SonarQube API: %s" % error.reason, file=sys.stderr)
    sys.exit(1)

try:
    json.loads(body)
except json.JSONDecodeError as error:
    print("SonarQube API response was not valid JSON: %s" % error, file=sys.stderr)
    sys.exit(1)

with open(os.environ["SONAR_ISSUES_OUTPUT"], "w", encoding="utf-8") as output:
    output.write(body)
PY
                '''
            )
        }

        if (status != 0) {
            handleFailure(settings, 'Could not fetch SonarQube issues')
            return
        }

        if (!fileExists(settings.outputFile)) {
            handleFailure(settings, "SonarQube issue output was not generated: ${settings.outputFile}")
            return
        }

        if (settings.archive) {
            archiveArtifacts(
                allowEmptyArchive: false,
                artifacts: settings.outputFile
            )
        }

        printIssueSummary(settings)
    }

    if (settings.container) {
        container(settings.container) {
            fetch()
        }
    } else {
        fetch()
    }
}

private Map normalizeConfig(Map config) {
    return [
        projectKey: validateProjectKey(config.projectKey),
        sonarUrl: validateUrl(config.sonarUrl?.toString()),
        sonarToken: validatePlainValue(config.sonarToken?.toString(), 'sonarToken'),
        severities: normalizeTokenList(
            config.severities ?: ['BLOCKER', 'CRITICAL', 'MAJOR'],
            'SonarQube issue severity'
        ),
        statuses: normalizeTokenList(
            config.statuses ?: ['OPEN', 'CONFIRMED'],
            'SonarQube issue status'
        ),
        maxIssues: parseBoundedInteger(config.maxIssues ?: 100, 'maxIssues', 1, 500),
        maxIssuesToPrint: parseBoundedInteger(config.maxIssuesToPrint ?: 20, 'maxIssuesToPrint', 0, 100),
        outputFile: Validation.relativePath((config.outputFile ?: 'sonarqube-issues.json').toString(), 'SonarQube issues output file'),
        archive: config.get('archive', true) as boolean,
        failOnError: config.get('failOnError', false) as boolean,
        timeoutSeconds: parseBoundedInteger(config.timeoutSeconds ?: 20, 'timeoutSeconds', 1, 120),
        container: config.containsKey('container') ? validateOptionalContainer(config.container) : 'python'
    ]
}

private void printIssueSummary(Map settings) {
    Map response = new JsonSlurper().parseText(readFile(settings.outputFile)) as Map
    List issues = response.issues ?: []
    Integer total = response.total ?: issues.size()

    if (issues.isEmpty()) {
        echo "No SonarQube issues found for ${settings.projectKey}."
        return
    }

    Integer limit = Math.min(settings.maxIssuesToPrint, issues.size())
    echo "SonarQube issues found for ${settings.projectKey}: ${total}. Showing ${limit}."

    issues.take(limit).each { issue ->
        String fileName = issue.component?.toString()?.tokenize(':')?.last() ?: 'unknown'
        String lineNumber = issue.line ? issue.line.toString() : 'N/A'
        String message = issue.message?.toString()?.replaceAll(/\s+/, ' ') ?: ''

        echo "${issue.severity ?: 'UNKNOWN'} ${issue.type ?: 'ISSUE'} ${fileName}:${lineNumber} ${issue.rule ?: ''} ${message}"
    }

    if (total > limit) {
        echo "Download ${settings.outputFile} from Jenkins artifacts for the full SonarQube issue response."
    }
}

private void handleFailure(Map settings, String message) {
    if (settings.failOnError) {
        error message
    }

    echo "${message}. Continuing because failOnError is false."
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

private String validateUrl(String value) {
    String url = validatePlainValue(value, 'sonarUrl')
    if (!(url ==~ /^https?:\/\/\S+$/)) {
        throw new IllegalArgumentException("sonarUrl must be an http(s) URL: ${url}")
    }

    return url
}

private String validatePlainValue(String value, String label) {
    if (!value || value == 'null') {
        throw new IllegalArgumentException("${label} cannot be empty")
    }

    if (value.contains('\n') || value.contains('\r')) {
        throw new IllegalArgumentException("${label} cannot contain newlines")
    }

    return value
}

private String validateOptionalContainer(Object value) {
    if (value == null || value.toString().trim() == '') {
        return null
    }

    return validatePlainValue(value.toString(), 'container')
}

private String normalizeTokenList(Object value, String label) {
    List values
    if (value instanceof List) {
        values = value
    } else {
        values = value.toString().split(',') as List
    }

    List normalized = values.collect { item ->
        String token = item.toString().trim()
        if (!token || !(token ==~ /^[A-Z_]+$/)) {
            throw new IllegalArgumentException("Invalid ${label}: ${item}")
        }
        return token
    }

    if (normalized.size() != normalized.unique().size()) {
        throw new IllegalArgumentException("Duplicate ${label} values are not allowed: ${normalized}")
    }

    return normalized.join(',')
}

private Integer parseBoundedInteger(Object value, String label, Integer min, Integer max) {
    if (!(value.toString() ==~ /^[0-9]+$/)) {
        throw new IllegalArgumentException("${label} must be a positive integer: ${value}")
    }

    Integer parsed = value.toString().toInteger()
    if (parsed < min || parsed > max) {
        throw new IllegalArgumentException("${label} must be between ${min} and ${max}: ${value}")
    }

    return parsed
}
