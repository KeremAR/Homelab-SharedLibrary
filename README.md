# Homelab-SharedLibrary

Reusable Jenkins Pipeline helpers for the Homelab CI/CD setup.

## Lint Pod Template

Use `ciLintPodTemplate()` from Jenkinsfiles to run CI steps on a Kubernetes agent with:

- `python` container for linting and unit tests
- `node` container for frontend linting
- `hadolint` container for Dockerfile linting
- `trivy` container for security scans
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

## Lint Helpers

- `runPythonLinting(targets: [...])` runs `black --check .` and `flake8 .`.
- `runNodeLinting(packageDirs: [...])` runs `npm ci --prefer-offline --no-audit` and `npm run lint`.
- `runHadolint(dockerfiles: [...])` runs Hadolint without Docker-in-Docker.
- `runUnitTest(services: [...])` runs pytest with JUnit and coverage XML reports.

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
