# Homelab-SharedLibrary

Reusable Jenkins Pipeline helpers for the Homelab CI/CD setup.

## Lint Pod Template

Use `ciLintPodTemplate()` from Jenkinsfiles to run CI steps on a Kubernetes agent with:

- `python` container for linting and unit tests
- `node` container for frontend linting
- `hadolint` container for Dockerfile linting
- `trivy` container for security scans
- `jenkins-tools-cache-pvc` mounted at `/home/jenkins/agent/tools` for Jenkins tool installers
- `jenkins-venv-cache-pvc` mounted at `/cache/pip` for pip downloads/wheels
- `jenkins-npm-cache-pvc` mounted at `/home/node/.npm`
- `jenkins-trivy-cache-pvc` mounted at `/home/jenkins/.cache/trivy`
- `ghcr-creds` image pull secret
- `automountServiceAccountToken: false`
- non-root container execution
- dropped Linux capabilities
- `RuntimeDefault` seccomp profile

The pod YAML lives in:

```text
resources/com/company/jenkins/pods/lint-pod.yaml
```

Example:

```groovy
@Library('homelab-shared-library') _

pipeline {
  agent {
    kubernetes {
      yaml ciLintPodTemplate()
      defaultContainer 'jnlp'
    }
  }

  stages {
    stage('Check') {
      steps {
        sh 'python --version'
      }
    }
  }
}
```

`defaultContainer 'jnlp'` means unqualified Jenkins steps run in the Jenkins
agent container by default. Lint helpers still run in their own containers
because they explicitly call `container('python')`, `container('node')`, or
`container('hadolint')`.

Jenkins auto-installed tools, such as SonarQube Scanner, are cached under:

```text
/home/jenkins/agent/tools
```

That path is backed by `jenkins-tools-cache-pvc`, so tool downloads can survive
new ephemeral Kubernetes agent pods. The PVC is ReadWriteOnce like the other CI
caches, so it assumes the current single-app CI flow with concurrent builds
disabled.

## Lint Helpers

- `runPythonLinting(targets: [...])` runs `black --check .` and `flake8 .`.
- `runNodeLinting(packageDirs: [...])` runs `npm ci --prefer-offline --no-audit` and `npm run lint`.
- `runHadolint(dockerfiles: [...])` runs Hadolint without Docker-in-Docker.
- `runUnitTest(services: [...])` runs pytest with JUnit and coverage XML reports.
- `runSonarQube(projectKey: '...', coverageReports: [...])` runs SonarQube analysis and waits for the Quality Gate.

Set `failFast: false` on lint helpers when you want one build to report as
many lint errors as possible instead of stopping sibling branches early.

Project-specific lint policy should live in the application repository:

- `pyproject.toml` for Black
- `.flake8` for Flake8
- `.hadolint.yaml` for Hadolint
- `package.json` for frontend lint scripts

Python unit tests create a fresh workspace-local venv on every build and use a
PVC-backed pip cache at `/cache/pip`. Coverage reports are written under
`coverage-reports/<target>/coverage.xml` so they can later be passed to
SonarQube.

## How Python Linting Works

Example Jenkinsfile usage:

```groovy
runPythonLinting(
  targets: ['user-service', 'todo-service'],
  failFast: false
)
```

The helper receives this as a Groovy `Map` named `config`.

```groovy
String containerName = config.container ?: 'python'
```

If the Jenkinsfile does not pass `container`, the helper uses the `python`
container by default. Passing `container: 'custom-python'` would override it.

```groovy
List safeTargets = Validation.uniqueRelativePaths(targets, 'Python lint target')
```

This validates every target before Jenkins enters the directory. It rejects
absolute paths, parent directory traversal, values starting with `-`, invalid
characters, and duplicate targets. The result is called `safeTargets` because
those paths are safe to pass to `dir()` and shell commands.

The helper then creates a Jenkins `parallel` branch for each target:

```groovy
branches["Python lint: ${safeTarget}"] = {
  container(containerName) {
    if (!fileExists(safeTarget)) {
      error "Python lint target does not exist: ${safeTarget}"
    }

    dir(safeTarget) {
      sh(label: "Black: ${safeTarget}", script: 'black --check .')
      sh(label: "Flake8: ${safeTarget}", script: 'flake8 .')
    }
  }
}
```

`branches` is a map where each key becomes a visible Jenkins parallel branch
name. `sh(label: ..., script: ...)` gives the shell step a readable name in the
Jenkins UI instead of showing only "Shell Script".

Finally, the helper runs all branches:

```groovy
parallel branches + [failFast: failFast]
```

For linting, prefer `failFast: false` so one service failing lint does not hide
lint errors from the other services in the same build.

## How Unit Testing Works

Example Jenkinsfile usage:

```groovy
runUnitTest(
  services: [
    [
      name: 'user-service',
      target: 'user-service',
      requirementsFile: 'user-service/requirements-test.txt',
      testPath: '.',
      coverageThreshold: 70
    ],
    [
      name: 'todo-service',
      target: 'todo-service',
      requirementsFile: 'todo-service/requirements-test.txt',
      testPath: '.',
      coverageThreshold: 75
    ]
  ],
  coverageDir: 'coverage-reports',
  failFast: false
)
```

`runUnitTest` intentionally accepts only `services` maps. This keeps the helper
explicit: each service declares its requirements file, test path, and coverage
config.

Coverage behavior is maintained by each service in its own coverage.py config:

```ini
[run]
relative_files = true
source =
    .

[report]
show_missing = true
omit =
    test_*.py
    tests/*
```

`relative_files = true` helps coverage XML contain paths that are easier to
reuse outside the Jenkins workspace. Since pytest runs from inside the service
directory, `source = .` means "measure this service". Coverage thresholds are
owned by the Jenkinsfile as service-level `coverageThreshold` values, because
each service can mature at a different pace without editing every service config.

After reading `services`, the helper validates repository-relative paths,
checks for duplicate service names, and creates one Jenkins `parallel` branch
per service.

Each branch runs inside the `python` container and performs this flow:

```text
check service directory exists
check test path exists
check service requirements file exists
check service .coveragerc exists
delete old report directory
create fresh report directory
create a fresh venv under $WORKSPACE/.venvs
install requirements using persistent pip cache
run pytest from inside the service directory with COVERAGE_RCFILE
publish junit.xml to Jenkins test results
archive junit.xml and coverage.xml as build artifacts
fail if tests passed but junit.xml is missing
fail if tests passed but coverage.xml is missing
fail the branch if pytest returned non-zero
```

### Why Unit Test Has Helper Functions

The unit test helper has more private helper functions than linting helpers
because it manages a full test contract, not only a single command. Linting can
mostly validate a path and run `black`, `flake8`, or `hadolint`; unit testing
also needs service metadata, dependency files, coverage configuration,
thresholds, report paths, cache paths, and Jenkins test publishing.

The helper functions run in this order:

```text
cachePath
normalizeServices
parseRequiredInteger
validateUniqueServiceNames
validateServiceFiles
```

`cachePath` validates the pip cache path before it is passed into the shell. It
matters only because `pipCacheDir` is configurable. If the helper always
hard-coded `/cache/pip`, this function could be removed.

`normalizeServices` converts each Jenkinsfile service map into one consistent
internal structure. It fills defaults such as `target`, `testPath`,
`coverageConfig`, `requirementsFile`, and `reportName`. Without it, the main
pipeline code would need to repeat that defaulting logic inline, or every
Jenkinsfile would need to provide every field perfectly.

`parseRequiredInteger` is called while services are normalized and makes
`coverageThreshold` explicit and safe. Without it, empty, non-numeric, or
out-of-range values could produce confusing pytest errors, or accidentally
remove the coverage gate.

`validateUniqueServiceNames` prevents duplicate service names before Jenkins
parallel branches are created. Without it, two services with the same name could
overwrite each other in the `parallel` map and also collide on report and venv
directories such as `coverage-reports/<service>` and `.venvs/<service>`.

`validateServiceFiles` checks that the service directory, test path,
requirements file, and service `.coveragerc` exist before running expensive
commands. Without it, the build would usually still fail, but later and with
less useful errors from `pip`, `pytest`, or missing report files.

These checks are mostly guardrails, but they prevent silent CI mistakes. The
most important ones to keep are service normalization, duplicate service
detection, file existence checks, and coverage threshold validation.

The venv is intentionally ephemeral:

```text
$WORKSPACE/.venvs/<service>
```

Only pip's download/wheel cache is persistent:

```text
/cache/pip
```

This avoids sharing executable environments between jobs while still speeding up
dependency installation.
The Python runner image must not set `PIP_NO_CACHE_DIR=1`; the helper explicitly
sets `PIP_CACHE_DIR` and passes `--cache-dir` to `pip install`.

The pytest command produces two important files per service:

```text
coverage-reports/user-service/junit.xml
coverage-reports/user-service/coverage.xml
coverage-reports/todo-service/junit.xml
coverage-reports/todo-service/coverage.xml
```

`pytest` is the step that actually runs the tests:

```bash
cd user-service

python -m pytest . \
  --junitxml=coverage-reports/user-service/junit.xml \
  --cov \
  --cov-report=xml:coverage-reports/user-service/coverage.xml \
  --cov-report=term-missing \
  --cov-fail-under=70
```

The source path comes from the service `.coveragerc`; the threshold comes from
that service's Jenkinsfile `coverageThreshold` value. `runUnitTest` does not
accept a top-level coverage threshold; every service declares its own threshold.

The Jenkins `junit(...)` step does not run tests. It reads the `junit.xml` file
that pytest already created and adds a Test Result section to the Jenkins build.
It is a built-in Jenkins Pipeline step provided by Jenkins' test reporting
support:

```groovy
junit(
  allowEmptyResults: false,
  testResults: "${reportDir}/junit.xml"
)
```

The helper only calls `junit(...)` when the file exists. If tests pass but
`junit.xml` is missing, the helper fails the build because that means the test
reporting contract is broken.

The Jenkins `archiveArtifacts(...)` step stores report files on the build page
so they can be downloaded later or reused by later integrations. It is also a
built-in Jenkins Pipeline step:

```groovy
archiveArtifacts(
  allowEmptyArchive: true,
  artifacts: "${reportDir}/*.xml"
)
```

`coverage.xml` is kept because SonarQube can use it later for coverage import.
`junit.xml` is kept because Jenkins uses it for test history and UI reporting.

`allowEmptyArchive: true` avoids masking the real pytest failure with a
secondary "archive file not found" error. `allowEmptyResults` is intentionally
`false` when `junit(...)` is called, so Jenkins does not silently accept a
missing or empty test report.

The helper still fails the branch explicitly:

```groovy
if (status != 0) {
  error "Unit tests failed for ${service.name}"
}
```

## How SonarQube Analysis Works

`runSonarQube` is the Shared Library wrapper around Jenkins' SonarQube plugin
and scanner tool. SonarQube itself runs in the cluster and stores analysis
results, issues, quality gates, and coverage data. The scanner runs inside the
Jenkins pipeline and sends the current workspace analysis to the SonarQube
server.

Example Jenkinsfile usage:

```groovy
runSonarQube(
  projectKey: 'homelab-app',
  sources: ['user-service', 'todo-service', 'frontend'],
  coverageReports: [
    'coverage-reports/user-service/coverage.xml',
    'coverage-reports/todo-service/coverage.xml'
  ],
  fetchIssues: true,
  fetchIssuesConfig: [
    severities: ['BLOCKER', 'CRITICAL', 'MAJOR'],
    statuses: ['OPEN', 'CONFIRMED'],
    maxIssues: 100
  ],
  cpdExclusions: [
    '**/tests/**'
  ]
)
```

The helper uses these Jenkins-side objects:

```text
withSonarQubeEnv('sonarqube')
  injects SonarQube URL and token from Jenkins Global Configuration

tool 'SonarQube Scanner'
  resolves the Jenkins-managed sonar-scanner installation

waitForQualityGate
  waits for SonarQube's webhook response after analysis
```

The helper generates `sonar-project.properties` in the workspace before running
the scanner. It owns generic properties such as `sonar.projectKey`,
`sonar.sources`, `sonar.exclusions`, `sonar.python.coverage.reportPaths`, and
`sonar.cpd.exclusions`. If a project needs extra SonarQube settings, pass them
through `extraProperties`; do not hardcode project-specific rules in the Shared
Library.

Coverage reports come from the earlier `runUnitTest` stage:

```text
coverage-reports/user-service/coverage.xml
coverage-reports/todo-service/coverage.xml
```

By default, configured coverage reports must exist. This catches a broken test
or report path before SonarQube analysis runs without coverage.

`runSonarQube` can also call `fetchSonarQubeIssues` when `fetchIssues: true` is
set. This is diagnostic: it archives the SonarQube issue API response and prints
a short summary in the Jenkins log. It does not replace Quality Gate
enforcement.

SonarQube properties are passed in two ways. Common properties have dedicated
parameters:

```groovy
runSonarQube(
  projectKey: 'homelab-app',
  sources: ['.'],
  exclusions: [
    '**/node_modules/**',
    '**/test/**',
    '**/test_*.py',
    'docker-compose*.yml',
    'site/**',
    'docs/**',
    'custom-images/**'
  ],
  coverageReports: [
    'coverage-reports/user-service/coverage.xml',
    'coverage-reports/todo-service/coverage.xml'
  ],
  cpdExclusions: [
    '**/verify_token',
    '**/*service/app.py'
  ]
)
```

Less common or project-specific properties go into `extraProperties`:

```groovy
runSonarQube(
  projectKey: 'homelab-app',
  sources: ['.'],
  extraProperties: [
    'sonar.issue.ignore.multicriteria': 'e1,e2',
    'sonar.issue.ignore.multicriteria.e1.ruleKey': 'docker:S6471',
    'sonar.issue.ignore.multicriteria.e1.resourceKey': '**/frontend/Dockerfile',
    'sonar.issue.ignore.multicriteria.e2.ruleKey': 'docker:S4507',
    'sonar.issue.ignore.multicriteria.e2.resourceKey': '**/custom-images/**'
  ]
)
```

The helper writes these values into `sonar-project.properties` before running
`sonar-scanner`.

### Why SonarQube Has Helper Functions

The SonarQube helper has several private functions because it builds a
configuration file and passes user-provided values into scanner commands. The
extra functions keep the main `call` flow readable and make invalid Jenkinsfile
configuration fail before the scanner starts.

The helper functions run in this order:

```text
normalizeConfig
validateProjectKey
asList
Validation.uniqueRelativePaths / Validation.uniqueSafeGlobs
normalizeExtraProperties
validateInputFiles
writeSonarProperties
parsePositiveInteger
joinCsv
```

`normalizeConfig` is the main adapter between the Jenkinsfile and the internal
settings map. It fills defaults such as `scannerName: 'SonarQube Scanner'`,
`serverName: 'sonarqube'`, `propertiesFile: 'sonar-project.properties'`, and
`timeoutMinutes: 15`. Without it, the main pipeline flow would be full of
defaulting and validation code.

`validateProjectKey` checks that `projectKey` exists and only contains safe
SonarQube project key characters. Without it, a typo or unsafe value would fail
later inside the scanner with a less useful error.

`asList` lets callers pass either a single value or a list for list-like
settings. For example, `sources: 'frontend'` becomes `['frontend']`. Without it,
a single string could be treated like an unexpected collection shape.

`Validation.uniqueRelativePaths` is used for source paths and coverage reports.
It rejects absolute paths, parent directory traversal, values starting with
`-`, invalid characters, and duplicates. Without it, a bad Jenkinsfile path
could scan the wrong place, fail late, or overwrite another value.

`Validation.uniqueSafeGlobs` is used for SonarQube glob settings such as
`exclusions`, `cpdExclusions`, and `testInclusions`. It keeps glob patterns
repository-relative. Without it, project config could accidentally point outside
the workspace or pass option-looking values.

`normalizeExtraProperties` validates optional custom `sonar.*` properties. It
also blocks properties that already have first-class helper parameters, such as
`sonar.sources` or `sonar.projectKey`. Without it, the same property could be
defined twice and make the final scanner config hard to reason about.

`validatePropertyKey`, `validatePropertyValue`, and `validatePlainValue` protect
the generated `sonar-project.properties` file. They reject invalid property
keys and multiline values. Without them, a value containing a newline could
silently inject another SonarQube property.

`validateInputFiles` checks configured source paths and required coverage
reports before the scanner runs. Without it, SonarQube could run successfully
but miss coverage because a report path was wrong.

`writeSonarProperties` creates the actual `sonar-project.properties` file that
`sonar-scanner` reads. Without it, every Jenkinsfile would need to maintain its
own SonarQube properties file or inline scanner flags.

`parsePositiveInteger` validates the timeout value. Without it, an empty,
negative, or very large timeout could create confusing Jenkins behavior.

`defaultExclusions`, `managedPropertyKeys`, and `joinCsv` are small support
functions. They keep default ignore patterns, protected property names, and
comma-separated SonarQube values in one place.

`fetchSonarQubeIssues` calls SonarQube's `/api/issues/search` endpoint after
analysis. It stores the raw JSON as `sonarqube-issues.json`, archives it as a
Jenkins artifact, and prints a compact issue summary.

The fetch helper is useful when you want quick feedback directly in Jenkins
logs, especially after a Quality Gate failure. It should stay diagnostic. The
main pass/fail contract remains:

```text
scanner sends analysis
SonarQube evaluates Quality Gate
waitForQualityGate fails or passes the build
Jenkins links back to SonarQube for issue details
```

## How Static Security Scanning Works

The application repository runs source-level security scans after unit tests:

```groovy
stage('Prepare Security Scanner') {
  steps {
    ensureTrivyDB()
  }
}

stage('Static Security Scan') {
  parallel {
    stage('Dependencies') {
      steps {
        runTrivyFSScan(
          target: '.',
          skipDirs: securityConfig.trivySkipDirs,
          failOnVulnerabilities: true
        )
      }
    }

    stage('Secrets') {
      steps {
        runTrivySecretScan(
          target: '.',
          skipDirs: securityConfig.trivySkipDirs,
          failOnSecrets: true
        )
      }
    }
  }
}
```

The helpers run directly in the Jenkins agent pod's `trivy` container. They do
not use Docker-in-Docker or `docker run`.

`ensureTrivyDB()` prepares the persistent Trivy database cache:

```text
/home/jenkins/.cache/trivy
```

This path is backed by `jenkins-trivy-cache-pvc`, so the DB survives across
short-lived Jenkins agent pods. `ensureTrivyDB()` runs Trivy's DB update command
on every pipeline run and lets Trivy decide whether the local DB is already
fresh. If the DB is current, Trivy reuses it. If it is stale or missing, Trivy
updates it. When the DB is updated, the same source code can start failing if a
new CVE is added for one of its dependencies.

DB updates are wrapped with Jenkins' Lockable Resources Plugin:

```groovy
lock(resource: 'trivy-db-cache') {
  trivy image --download-db-only ...
}
```

This means concurrent Jenkins jobs share the same PVC cache safely: one job
updates the DB while the others wait for the Jenkins lock instead of racing on
the same cache files. The Jenkins controller must have the `lockable-resources`
plugin installed for this helper.

Each scan uses an isolated copy of the persistent cache:

```text
persistent PVC cache
  /home/jenkins/.cache/trivy

temporary scan copy
  /tmp/trivy-fs-<uuid>
  /tmp/trivy-iac-<uuid>
```

The vulnerability and IaC helpers copy the persistent cache into a temporary
cache outside the workspace and run Trivy with `--skip-db-update`:

```bash
cp -R "$TRIVY_SOURCE_CACHE"/<trivy-cache-files> "$TRIVY_ISOLATED_CACHE"/
trivy fs --skip-db-update --cache-dir "$TRIVY_ISOLATED_CACHE" ...
```

This keeps parallel scans from writing to the same DB/cache files at the same
time. The persistent cache is the shared source of truth; the isolated cache is
a temporary per-scan working copy. The copy is outside `$WORKSPACE`, so Trivy
does not recursively scan its own DB/cache files. The copy step does not preserve
file ownership and skips `lost+found`, which avoids non-root PVC ownership
warnings. The `finally` block removes only the temporary isolated copy:

```bash
rm -rf "$TRIVY_ISOLATED_CACHE"
```

It does not delete the PVC-backed persistent DB.

Secret scanning does not use the Trivy vulnerability DB. It scans source files
with built-in secret rules, so `runTrivySecretScan` does not create an isolated
cache and does not use `--skip-db-update`.

`runTrivyFSScan` scans dependency manifests and lock files for vulnerabilities:

```groovy
runTrivyFSScan(
  target: '.',
  severities: 'HIGH,CRITICAL',
  filePatterns: ['pip:requirements-.*\\.txt'],
  includeDevDeps: true,
  failOnVulnerabilities: true
)
```

The `filePatterns` option is important for this repo because backend services
use `requirements-test.txt`; Trivy's default pip discovery focuses on standard
requirements filenames. `includeDevDeps: true` includes frontend development and
build-time dependencies from npm lock files.

`failOnVulnerabilities` controls Trivy's `--exit-code`:

```text
true  -> findings at the selected severities return exit code 1 and fail Jenkins
false -> findings are reported, but Trivy returns exit code 0
```

`runTrivySecretScan` scans source files for committed secrets:

```groovy
runTrivySecretScan(
  target: '.',
  severities: 'UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL',
  failOnSecrets: true
)
```

Secret scan defaults include all severities, because even low-severity secret
findings should be reviewed.

`runTrivyIaCscan` exists for repositories that contain Kubernetes manifests,
Helm charts, Terraform, or similar infrastructure files. The app source repo no
longer calls it because manifests now live in a separate infrastructure/config
repository. Use it in that repo instead:

```groovy
runTrivyIaCscan(
  targets: ['3-DeployWithManifests', 'helm-charts/manifests'],
  failOnIssues: true
)
```

For the application repo, the static security stage should normally include:

```text
ensureTrivyDB
runTrivyFSScan
runTrivySecretScan
```

For the infrastructure/config repo, add:

```text
runTrivyIaCscan
```
