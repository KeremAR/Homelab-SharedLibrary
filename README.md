# Homelab-SharedLibrary

Reusable Jenkins Pipeline helpers for the Homelab CI/CD setup.

## Lint Pod Template

Use `ciLintPodTemplate()` from Jenkinsfiles to run CI steps on a Kubernetes agent with:

- `python` container for linting and unit tests
- `node` container for frontend linting
- `hadolint` container for Dockerfile linting
- `trivy` container for security scans
- `jenkins-venv-cache-pvc` mounted at `/cache/venvs`
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
- `runUnitTest(targets: [...])` runs pytest with JUnit and coverage XML reports.

Set `failFast: false` on lint helpers when you want one build to report as
many lint errors as possible instead of stopping sibling branches early.

Project-specific lint policy should live in the application repository:

- `pyproject.toml` for Black
- `.flake8` for Flake8
- `.hadolint.yaml` for Hadolint
- `package.json` for frontend lint scripts

Python unit tests use a PVC-backed venv cache at `/cache/venvs`. The cache key
includes the target path, Python minor version, and requirements file hash.
Coverage reports are written under `coverage-reports/<target>/coverage.xml` so
they can later be passed to SonarQube.

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
