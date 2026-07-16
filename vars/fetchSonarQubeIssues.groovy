#!/usr/bin/env groovy

import com.company.jenkins.Validation

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
 *   - sonarUrl: SonarQube server URL. Defaults to SONAR_HOST_URL from withSonarQubeEnv.
 *   - sonarToken: SonarQube token. Defaults to SONAR_AUTH_TOKEN from withSonarQubeEnv.
 *   - severities: List or comma-separated string (default: ['BLOCKER', 'CRITICAL', 'MAJOR'])
 *   - statuses: List or comma-separated string (default: ['OPEN', 'CONFIRMED'])
 *   - branch: Optional SonarQube branch filter
 *   - pullRequest: Optional SonarQube pull request filter
 *   - inNewCodePeriod: Fetch only issues in new code when supported (default: false)
 *   - maxIssues: Maximum issues to fetch from the API (default: 100)
 *   - maxIssuesToPrint: Maximum issues to print in Jenkins log (default: 20)
 *   - outputFile: Repository-relative JSON output file (default: 'sonarqube-issues.json')
 *   - summaryFile: Repository-relative text summary file (default: 'sonarqube-issues-summary.txt')
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
            "SONAR_SEVERITIES=${settings.severities}",
            "SONAR_STATUSES=${settings.statuses}",
            "SONAR_BRANCH=${settings.branch ?: ''}",
            "SONAR_PULL_REQUEST=${settings.pullRequest ?: ''}",
            "SONAR_IN_NEW_CODE_PERIOD=${settings.inNewCodePeriod}",
            "SONAR_MAX_ISSUES=${settings.maxIssues}",
            "SONAR_MAX_ISSUES_TO_PRINT=${settings.maxIssuesToPrint}",
            "SONAR_TIMEOUT_SECONDS=${settings.timeoutSeconds}",
            "SONAR_ISSUES_OUTPUT=${settings.outputFile}",
            "SONAR_ISSUES_SUMMARY=${settings.summaryFile}"
        ] + optionalEnv('SONAR_URL', settings.sonarUrl) + optionalEnv('SONAR_TOKEN', settings.sonarToken)) {
            status = sh(
                label: 'Fetch SonarQube issues',
                returnStatus: true,
                script: '''
                    set -eu

                    python - <<'PY'
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
if os.environ.get("SONAR_PULL_REQUEST"):
    params["pullRequest"] = os.environ["SONAR_PULL_REQUEST"]
elif os.environ.get("SONAR_BRANCH"):
    params["branch"] = os.environ["SONAR_BRANCH"]
if os.environ.get("SONAR_IN_NEW_CODE_PERIOD") == "true":
    params["inNewCodePeriod"] = "true"

base_url = (os.environ.get("SONAR_URL") or os.environ.get("SONAR_HOST_URL") or "").rstrip("/")
token = os.environ.get("SONAR_TOKEN") or os.environ.get("SONAR_AUTH_TOKEN") or ""
if not base_url:
    print("SonarQube URL was not provided. Set sonarUrl or run inside withSonarQubeEnv.", file=sys.stderr)
    sys.exit(1)
if not token:
    print("SonarQube token was not provided. Set sonarToken or run inside withSonarQubeEnv.", file=sys.stderr)
    sys.exit(1)

url = base_url + "/api/issues/search?" + urllib.parse.urlencode(params)

request = urllib.request.Request(url)
request.add_header("Authorization", "Bearer " + token)

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
    payload = json.loads(body)
except json.JSONDecodeError as error:
    print("SonarQube API response was not valid JSON: %s" % error, file=sys.stderr)
    sys.exit(1)

with open(os.environ["SONAR_ISSUES_OUTPUT"], "w", encoding="utf-8") as output:
    output.write(body)

issues = payload.get("issues", [])
total = int(payload.get("total", len(issues)))
limit = min(int(os.environ["SONAR_MAX_ISSUES_TO_PRINT"]), len(issues))

summary_lines = []
if not issues:
    summary_lines.append("No SonarQube issues found for %s." % os.environ["SONAR_PROJECT_KEY"])
else:
    summary_lines.append(
        "SonarQube issues found for %s: %s. Showing %s." %
        (os.environ["SONAR_PROJECT_KEY"], total, limit)
    )

    for issue in issues[:limit]:
        component = issue.get("component", "unknown")
        filename = component.split(":")[-1]
        line = issue.get("line", "N/A")
        severity = issue.get("severity", "UNKNOWN")
        issue_type = issue.get("type", "ISSUE")
        rule = issue.get("rule", "")
        message = " ".join(str(issue.get("message", "")).split())
        summary_lines.append("%s %s %s:%s %s %s" % (severity, issue_type, filename, line, rule, message))

    if total > limit:
        summary_lines.append(
            "Download %s from Jenkins artifacts for the full SonarQube issue response." %
            os.environ["SONAR_ISSUES_OUTPUT"]
        )

with open(os.environ["SONAR_ISSUES_SUMMARY"], "w", encoding="utf-8") as summary:
    summary.write("\\n".join(summary_lines) + "\\n")
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
                artifacts: "${settings.outputFile},${settings.summaryFile}"
            )
        }

        if (fileExists(settings.summaryFile)) {
            echo readFile(settings.summaryFile).trim()
        }
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
        sonarUrl: config.sonarUrl ? validateUrl(config.sonarUrl.toString()) : null,
        sonarToken: config.sonarToken ? validatePlainValue(config.sonarToken.toString(), 'sonarToken') : null,
        severities: normalizeTokenList(
            config.severities ?: ['BLOCKER', 'CRITICAL', 'MAJOR'],
            'SonarQube issue severity'
        ),
        statuses: normalizeTokenList(
            config.statuses ?: ['OPEN', 'CONFIRMED'],
            'SonarQube issue status'
        ),
        branch: config.containsKey('branch') ? validateOptionalBranch(config.branch) : null,
        pullRequest: config.containsKey('pullRequest') ? validateOptionalPullRequest(config.pullRequest) : null,
        inNewCodePeriod: config.get('inNewCodePeriod', false) as boolean,
        maxIssues: parseBoundedInteger(config.maxIssues ?: 100, 'maxIssues', 1, 500),
        maxIssuesToPrint: parseBoundedInteger(config.maxIssuesToPrint ?: 20, 'maxIssuesToPrint', 0, 100),
        outputFile: Validation.relativePath((config.outputFile ?: 'sonarqube-issues.json').toString(), 'SonarQube issues output file'),
        summaryFile: Validation.relativePath((config.summaryFile ?: 'sonarqube-issues-summary.txt').toString(), 'SonarQube issues summary file'),
        archive: config.get('archive', true) as boolean,
        failOnError: config.get('failOnError', false) as boolean,
        timeoutSeconds: parseBoundedInteger(config.timeoutSeconds ?: 20, 'timeoutSeconds', 1, 120),
        container: config.containsKey('container') ? validateOptionalContainer(config.container) : 'python'
    ]
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

private List<String> optionalEnv(String key, String value) {
    return value ? ["${key}=${value}"] : []
}

private String validateOptionalBranch(Object value) {
    if (value == null || value.toString().trim() == '') {
        return null
    }

    String branch = validatePlainValue(value.toString(), 'branch')
    if (branch.startsWith('-') || !(branch ==~ /^[A-Za-z0-9._\/-]+$/)) {
        throw new IllegalArgumentException("Invalid SonarQube branch value: ${branch}")
    }

    return branch
}

private String validateOptionalPullRequest(Object value) {
    if (value == null || value.toString().trim() == '') {
        return null
    }

    String pullRequest = validatePlainValue(value.toString(), 'pullRequest')
    if (pullRequest.startsWith('-') || !(pullRequest ==~ /^[A-Za-z0-9._:-]+$/)) {
        throw new IllegalArgumentException("Invalid SonarQube pullRequest value: ${pullRequest}")
    }

    return pullRequest
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
