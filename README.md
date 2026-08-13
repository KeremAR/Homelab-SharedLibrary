# Homelab-SharedLibrary

Reusable Jenkins Shared Library for the Homelab CI/CD setup.

This repository owns the reusable Pipeline steps, Kubernetes agent pod
templates, validation classes, and manual release Jenkinsfile used by Jenkins.
Jenkins itself is installed and configured elsewhere:

```text
4-Jenkins-Setup/4A-Jenkins.md
4-Jenkins-Setup/4B-Jenkins-Install.md
```

Read this file from top to bottom if you are new to the project. It starts with
the library structure and agent pods, then explains the CI flow, then the
release flow, and finally the shared validation and artifact contracts.

---

## Repository Layout

```text
SharedLibrary/
  vars/
    *.groovy  -> global Jenkins Pipeline steps
    *.txt     -> Jenkins Global Variable Reference help pages

  src/com/company/jenkins/
    *.groovy  -> reusable helper classes and validators

  resources/com/company/jenkins/pods/
    lint-pod.yaml
    release-pod.yaml

  pipelines/release/
    Jenkinsfile
```

Files under `vars/` become callable Jenkins steps:

```groovy
runPythonLinting(...)
runUnitTest(...)
pushToRegistry(...)
```

Files under `src/` are regular Groovy classes used by those steps:

```groovy
com.company.jenkins.Validation
com.company.jenkins.TrivyValidation
com.company.jenkins.ReleaseResolver
com.company.jenkins.ImageArtifactManifest
```

Files under `resources/` are loaded with `libraryResource(...)`, mainly for
Kubernetes agent pod YAML.

---

## Agent Pods

The library provides two Kubernetes agent pod templates.

### CI Agent

Pod template function:

```groovy
ciLintPodTemplate(...)
```

`ciLintPodTemplate()` returns Kubernetes Pod YAML for the Jenkins Kubernetes
plugin. Jenkins uses that YAML to create the temporary CI agent pod for the
current build.

Resource:

```text
resources/com/company/jenkins/pods/lint-pod.yaml
```

Despite the name, this is the main CI pod. It started as a lint pod and later
grew to host tests, SonarQube, Trivy, and release-branch image builds.

Containers:

```text
jnlp
  Jenkins remoting container. Unqualified Jenkins steps run here by default.

python
  Python linting and unit tests.

node
  frontend linting.

sonar
  SonarScanner runtime. Uses a JRE image because the scanner is Java based.

hadolint
  Dockerfile linting.

trivy
  dependency, secret, image, and SBOM scans.

docker
  Docker CLI.

docker-dind
  pod-local Docker daemon for Docker image builds and docker save.
```

Main cache mounts:

```text
jenkins-tools-cache-pvc  -> /home/jenkins/agent/tools
jenkins-venv-cache-pvc   -> /cache/pip
jenkins-npm-cache-pvc    -> /home/node/.npm
jenkins-trivy-cache-pvc  -> /home/jenkins/.cache/trivy
jenkins-sonar-cache-pvc  -> /home/jenkins/.sonar
```

Docker cache storage is selected by `ciLintPodTemplate(...)`:

```text
release/user-service-v1.0.0 -> jenkins-docker-cache-user-service-pvc
release/todo-service-v1.0.0 -> jenkins-docker-cache-todo-service-pvc
release/frontend-v1.0       -> jenkins-docker-cache-frontend-pvc
non-release branches        -> emptyDir
```

The service-specific Docker cache PVCs use `ReadWriteOncePod`. This prevents two
Docker daemons from mounting the same `/var/lib/docker` cache at the same time.

Jenkins auto-installed tools, such as SonarQube Scanner, are cached under:

```text
/home/jenkins/agent/tools
```

That path is backed by `jenkins-tools-cache-pvc`, so tool downloads can survive
new ephemeral Kubernetes agent pods. The PVC is `ReadWriteOnce`, like the other
CI caches, so it assumes the current single-app CI flow with concurrent builds
disabled per job.

SonarScanner also downloads analyzer and plugin cache files under
`SONAR_USER_HOME`. The `sonar` container sets:

```text
HOME=/home/jenkins
SONAR_USER_HOME=/home/jenkins/.sonar
```

That directory is backed by `jenkins-sonar-cache-pvc`, separate from the Jenkins
tool cache. The tool cache stores the scanner binary; the Sonar cache stores
scanner analyzer files reused by later builds.

Security choices:

```text
automountServiceAccountToken: false
non-root containers where possible
dropped Linux capabilities where possible
RuntimeDefault seccomp profile
privileged only for docker-dind
no host Docker socket mount
```

### Release Agent

Pod template function:

```groovy
releasePodTemplate(...)
```

`releasePodTemplate()` returns a smaller Kubernetes Pod YAML for manual release
jobs. It keeps lint/test/security-analysis containers out of release jobs and
includes only the containers needed to load, push, and optionally deploy an
image.

Resource:

```text
resources/com/company/jenkins/pods/release-pod.yaml
```

The release pod is smaller than the CI pod.

Containers:

```text
jnlp
  Jenkins remoting container. Unqualified Jenkins steps run here by default.

docker
  Docker CLI container. It runs docker load, docker tag, docker login,
  docker push, and fallback docker build commands.

docker-dind
  Pod-local Docker daemon. The docker CLI container talks to it through
  DOCKER_HOST=tcp://localhost:2375.

kubernetes
  kubectl, helm, argocd, and kubectl argo rollouts tooling container.
```

`docker` and `docker-dind` are two separate containers on purpose. `docker` is
only the CLI, while `docker-dind` is the daemon and image store. This avoids
mounting the host Docker socket into Jenkins. The tradeoff is that
`docker-dind` must run privileged.

The release pod needs Docker graph storage even for production releases. A
production release may not rebuild the image, but `docker load` still writes the
archived image layers into the Docker daemon before `docker tag` and
`docker push` run. The release pod mounts the service-specific Docker cache PVC
at `/var/lib/docker`, so fallback builds can reuse cache and `ReadWriteOncePod`
can make a CI job and release job for the same service wait instead of sharing
one Docker data directory.

`kubernetes` uses:

```text
ghcr.io/keremar/kubernetes-tools:kubectl-1.36.1-helm-3.20.1-argocd-3.4.2-rollouts-1.9.1
```

That image contains `sh`, `kubectl`, `helm`, `argocd`, and `kubectl argo
rollouts`. The current preferred deploy library step is `deployWithArgoHelm()`, which
updates Git and lets ArgoCD sync, so it normally does not need to run kubectl or
helm directly. The container remains available for transitional direct
deployment library steps.

---

## Library Step Overview

CI library steps:

```text
runPythonLinting
  Runs Black and Flake8 for Python services.

runNodeLinting
  Runs npm ci and npm run lint for frontend packages.

runHadolint
  Runs Hadolint against Dockerfiles.

runUnitTest
  Runs pytest, publishes JUnit, archives coverage XML.

runSonarQube
  Runs SonarScanner, waits for Quality Gate, optionally fetches issue summary.

ensureTrivyDB
  Prepares the shared Trivy vulnerability DB cache.

runTrivyFSScan
  Scans source/dependency files such as package-lock and requirements files.

runTrivySecretScan
  Scans repository files for committed secrets.

runTrivyIaCscan
  Scans IaC files when a repository owns manifests, Helm charts, or Terraform.
```

Release-branch CI library steps:

```text
runReleaseImages
  Parses release branch, selects the service image, tags it, and calls runBuildImages.

runBuildImages
  Runs docker build, docker save, archives Docker tar files, writes images.txt.

runTrivySBOM
  Generates CycloneDX SBOMs from Docker archives and can upload to Dependency-Track.

runTrivyScan
  Scans Docker archives with Trivy image scan.

markReleaseCiArtifact
  Writes ci-success.txt after release branch CI reaches the end successfully.
```

Manual release library steps:

```text
pushToRegistry
  Loads Docker archives, retags them for GHCR, pushes, writes pushed-images.txt.

updateGithub
  Checks out a GitHub repo, patches a file, commits, locks, retries, and pushes.

deployWithArgoHelm
  Updates Helm values in Git; ArgoCD auto-sync deploys the change.

deployWithHelm
  Updates Helm values in Git, then runs helm upgrade.

deployWithArgoKubectl
  Updates raw manifests in Git; ArgoCD auto-sync deploys the change.

deployWithKubectl
  Updates raw manifests in Git, then runs kubectl apply.
```

Pod template functions:

```text
ciLintPodTemplate
releasePodTemplate
```

---

## Current App CI Flow

The application repository currently uses:

```text
App/Jenkinsfile
```

It runs on the CI agent:

```groovy
agent {
  kubernetes {
    yaml ciLintPodTemplate(images: imageBuildConfig.images)
    defaultContainer 'jnlp'
  }
}
```

Main Jenkins options:

```text
buildDiscarder
skipDefaultCheckout(true)
timestamps()
disableConcurrentBuilds()
timeout(30 minutes)
cleanWs in post
```

CI stages:

```text
Checkout
Linting
Unit Tests
Code Quality Analysis
Prepare Security Scanner
Static Security Scan
Build Images              release/* only
Generate Image SBOM       release/* only
Image Security Scan       release/* only
Mark Release CI Artifact  release/* only
```

The first part runs for PR jobs and release branch jobs. The image artifact part
runs only for `release/*` branches.

---

## GitOps Repository Model

The release library steps assume two separate Git repositories with different
responsibilities.

```text
homelab-gitops
  ArgoCD Application topology.
  Defines root-app / App of Apps structure.
  Tells ArgoCD which Application should watch which repository path.

Homelab-Infrastructure
  Actual desired state.
  Contains plain Kubernetes manifests, Helm charts, and environment values.
  Jenkins updates this repository when a release changes an image tag.
```

In the current Helm GitOps flow:

```text
homelab-gitops
  argocd-helm/root-application.yaml
    -> creates environment-level Applications
    -> creates service-level Applications
    -> service Applications watch Homelab-Infrastructure/6-Helm-Deploy/<service>

Homelab-Infrastructure
  6-Helm-Deploy/<service>/values-staging.yaml
  6-Helm-Deploy/<service>/values-production.yaml
```

Jenkins does not directly modify `homelab-gitops` during a normal release.
Jenkins updates the image tag in `Homelab-Infrastructure`; ArgoCD already knows
which paths to watch because that topology lives in `homelab-gitops`.

---

## Build Once, Promote Same Artifact

The central release decision is:

```text
CI builds and validates a Docker archive.
Production release promotes that exact archive.
Production does not rebuild source code.
```

This matters for reproducibility and security. If production rebuilt the source
later, the result could differ because of base image changes, dependency
resolution, registry state, or Dockerfile behavior. Promoting the archive that
CI already built and scanned keeps the deployed artifact tied to the CI result.

Environment behavior:

```text
staging
  Convenience fallback rebuild is allowed when USE_CI_ARTIFACT=false.

prod
  CI artifact is required. Fallback rebuild is blocked.
```

For production, the tag can change from `staging` to `prod`, but the image is
not rebuilt from source. The release job loads the archived image, retags it for
the target registry/environment, and pushes it.

---

## Linting

The linting stage runs three library steps in parallel.

Python linting runs in the `python` container and executes
`black --check --diff .` followed by `flake8 .` for each target.

```groovy
runPythonLinting(
  targets: ['user-service', 'todo-service'],
  failFast: false
)
```

Parameters:

```text
targets
  Required list of repository-relative Python target directories.

pythonTargets
  Backward-compatible alias for targets.

container
  Jenkins Kubernetes container name. Default: python.

failFast
  Whether sibling parallel branches stop after the first failure. Default: true.
```

Frontend linting runs in the `node` container. It executes
`npm ci --prefer-offline --no-audit`, then `npm run <lintScript>`.

```groovy
runNodeLinting(
  packageDirs: ['frontend'],
  lintScript: 'lint',
  failFast: false
)
```

Parameters:

```text
packageDirs
  Required list of repository-relative Node package directories.

path
  Single-package alias for packageDirs.

lintScript
  npm script name to run. Default: lint.

container
  Jenkins Kubernetes container name. Default: node.

failFast
  Whether sibling parallel branches stop after the first failure. Default: true.
```

Dockerfile linting runs in the `hadolint` container and does not use
Docker-in-Docker.

```groovy
runHadolint(
  dockerfiles: [
    'user-service/Dockerfile',
    'todo-service/Dockerfile',
    'frontend/Dockerfile'
  ],
  failFast: false
)
```

Parameters:

```text
dockerfiles
  Required list of repository-relative Dockerfile paths.

configFile
  Hadolint config path. Default: .hadolint.yaml.
  If explicitly provided, it must exist.

container
  Jenkins Kubernetes container name. Default: hadolint.

failFast
  Whether sibling parallel branches stop after the first failure. Default: true.
```

For linting, `failFast: false` is preferred so one service failing lint does not
hide lint findings from sibling branches in the same build.

Project-specific lint rules stay in the application repo:

```text
pyproject.toml
.flake8
.hadolint.yaml
package.json
```

---

## Unit Tests

Usage:

```groovy
runUnitTest(
  services: unitTestServices,
  coverageDir: 'coverage-reports',
  failFast: false
)
```

`runUnitTest` runs in the `python` container by default. The `container`
parameter can override this when another pod template uses a different name.

Each service declares:

```text
name
target
requirementsFile
testPath
coverageThreshold
```

Parameters:

```text
services
  Required list of service maps.

requirementsFile
  Optional default requirements file. Service-level requirementsFile overrides it.
  Default per service: <target>/requirements-test.txt.

coverageDir
  Repository-relative report output directory. Default: coverage-reports.

container
  Jenkins Kubernetes container name. Default: python.

pipCacheDir
  Mounted pip cache path. Default: /cache/pip.

pipCacheLock
  Lockable Resources name for shared pip cache writes.
  Default: jenkins-pip-cache.

pipInstallRetries
  Retry count around pip install for transient PyPI/network/cache errors.
  Default: 2.

failFast
  Whether sibling parallel branches stop after the first failure. Default: true.
```

Service map keys:

```text
name
  Required service name used in Jenkins branch names and reports.

target
  Service directory. Default: name.

requirementsFile
  Service requirements file.

testPath
  Path passed to pytest under target. Default: ..

coverageConfig
  Service coverage.py config file. Default: <target>/.coveragerc.

coverageThreshold
  Required service-specific minimum coverage percentage.
```

Current services:

```text
user-service -> coverageThreshold 70
todo-service -> coverageThreshold 70
```

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

The coverage threshold comes from that service's Jenkinsfile
`coverageThreshold` value. `runUnitTest` does not accept a top-level coverage
threshold; every service declares its own threshold.

Unit test flow per service:

```text
validate service config
validate service directory, test path, requirements file, .coveragerc
delete old report directory
create fresh report directory
create fresh venv under $WORKSPACE/.venvs/<service>
install requirements with persistent pip cache
run pytest
publish junit.xml with Jenkins junit step
archive junit.xml and coverage.xml
fail if pytest failed
fail if expected reports are missing after a successful pytest run
```

The venv is ephemeral:

```text
$WORKSPACE/.venvs/<service>
```

The pip download/wheel cache is persistent:

```text
/cache/pip
```

That path is backed by `jenkins-venv-cache-pvc`. The library step sets `PIP_CACHE_DIR`
and calls `pip install --cache-dir /cache/pip`.

This avoids sharing executable environments between jobs while still speeding up
dependency installation. The Python runner image must not set
`PIP_NO_CACHE_DIR=1`; the library step explicitly sets `PIP_NO_CACHE_DIR=false`,
sets `PIP_CACHE_DIR`, and passes `--cache-dir` to `pip install`.

The pip install step is wrapped with:

```groovy
lock(resource: 'jenkins-pip-cache')
```

This protects the shared pip cache from concurrent writes by multiple Jenkins
agent pods. It does not serialize the whole test stage; only the cache-writing
install section is locked. The install step is also retried twice by default
for transient PyPI, network, or cache read errors.

Reports:

```text
coverage-reports/user-service/junit.xml
coverage-reports/user-service/coverage.xml
coverage-reports/todo-service/junit.xml
coverage-reports/todo-service/coverage.xml
```

`junit(...)` is a Jenkins Pipeline step. It does not run tests; it reads the
`junit.xml` produced by pytest and adds test results to the Jenkins UI.

`archiveArtifacts(...)` is also a Jenkins Pipeline step. It stores the report
files on the build page.

Coverage has two different files:

```text
.coverage
  raw coverage.py data file

coverage.xml
  XML report consumed by Jenkins artifacts and SonarQube
```

Because unit tests run in parallel, the library step sets a per-service
`COVERAGE_FILE` through `withEnv`:

```text
coverage-reports/user-service/.coverage
coverage-reports/todo-service/.coverage
```

This prevents parallel test branches from writing to the same raw coverage data
file.

---

## SonarQube

Usage:

```groovy
runSonarQube(
  projectKey: 'homelab-app',
  sources: ['user-service', 'todo-service', 'frontend'],
  coverageReports: [
    'coverage-reports/user-service/coverage.xml',
    'coverage-reports/todo-service/coverage.xml'
  ],
  fetchIssues: true,
  fetchIssuesConfig: issueFetchConfig,
  extraProperties: [
    'sonar.python.version': '3.11'
  ],
  inNewCodePeriod: newCodeIssues,
  container: 'sonar',
  abortPipeline: false
)
```

`runSonarQube` runs in the `sonar` container by default because SonarScanner is
Java based and can use meaningful memory during analysis.

Parameters:

```text
projectKey
  Required SonarQube project key.

sources
  Source directories or files. Default: ..

exclusions
  SonarQube exclusion globs. Default includes common generated/cache paths.

coverageReports
  Coverage XML report paths. Default: [].

cpdExclusions
  Duplication-detection exclusion globs. Default: [].

extraProperties
  Additional sonar.* properties after validation. Default: [:].

serverName
  Jenkins SonarQube server name. Default: sonarqube.

scannerName
  Jenkins tool name. Default: SonarQube Scanner.

container
  Jenkins Kubernetes container name. Default: sonar.

timeoutMinutes
  Timeout around scanner and Quality Gate wait. Default: 15.

waitForQualityGate
  Whether to wait for SonarQube processing. Default: true.

abortPipeline
  Whether a failed Quality Gate fails the build. Default: true.

fetchIssues
  Whether to fetch and archive a SonarQube issue summary. Default: false.

fetchIssuesConfig
  Issue API filters such as severities, statuses, maxIssues,
  maxIssuesToPrint, and inNewCodePeriod.

inNewCodePeriod
  Adds new-code filtering to generated scanner properties when requested.
```

Jenkins plugin steps used:

```text
withSonarQubeEnv('sonarqube')
tool 'SonarQube Scanner'
waitForQualityGate
```

`withSonarQubeEnv` injects SonarQube URL and token from Jenkins Global
Configuration. `tool` resolves the Jenkins-managed scanner installation.
`waitForQualityGate` waits for SonarQube to finish processing the uploaded
analysis.

The library step writes `sonar-project.properties` into the workspace before running
the scanner. Common properties have direct library-step parameters. Less common
project-specific properties can be passed through `extraProperties`.

Coverage reports come from the earlier `runUnitTest` stage:

```text
coverage-reports/user-service/coverage.xml
coverage-reports/todo-service/coverage.xml
```

Configured coverage reports must exist before analysis, so Jenkins fails early
instead of sending a successful SonarQube analysis with missing coverage.

Security-sensitive properties are blocked from `extraProperties`, including:

```text
sonar.token
sonar.login
sonar.password
sonar.host.url
sonar.projectBaseDir
sonar.working.directory
sonar.userHome
sonar.scanner.*
```

Branch, pull request, and new-code filters are intentionally not enabled
automatically inside the Shared Library. The application Jenkinsfile owns that
policy.

Current Homelab issue-fetch policy:

```text
release/* branch
  fetch project-level issues

everything else
  fetch new-code issues with inNewCodePeriod: true
```

`fetchSonarQubeIssues` is diagnostic. It calls SonarQube's issue API after the
scanner upload and Quality Gate wait. It archives `sonarqube-issues.json` and
prints a compact summary. The real pass/fail contract remains the Quality Gate.

`abortPipeline: false` means a failed Quality Gate marks the build unstable
instead of failing the whole Pipeline.

---

## Static Security Scan

The static security stages run after SonarQube.

First:

```groovy
ensureTrivyDB()
```

`ensureTrivyDB` parameters:

```text
container
  Jenkins Kubernetes container name. Default: trivy.

cacheDir
  Trivy cache directory. Default: /home/jenkins/.cache/trivy.

lockResource
  Lockable Resources name for DB updates. Default: trivy-db-cache.
```

`ensureTrivyDB` runs in the `trivy` container by default. Its default cache
directory is `/home/jenkins/.cache/trivy`, backed by `jenkins-trivy-cache-pvc`.

`ensureTrivyDB()` wraps Trivy DB update with:

```groovy
lock(resource: 'trivy-db-cache')
```

This lets concurrent Jenkins jobs share the same Trivy DB PVC without racing on
DB update files.

Then source-level scans run in parallel.

Dependency/filesystem scan:

```groovy
runTrivyFSScan(
  target: '.',
  skipDirs: securityConfig.trivyFsSkipDirs,
  filePatterns: ['pip:requirements-.*\\.txt'],
  includeDevDeps: true,
  failOnVulnerabilities: true
)
```

`runTrivyFSScan` common parameters:

```text
target
  Repository-relative scan target. Default: ..

targets
  Optional list of targets for multi-target scans.

skipDirs
  Repository-relative paths/globs to skip. Default: [].

filePatterns
  Extra Trivy file patterns, such as pip:requirements-.*\.txt.

includeDevDeps
  Include development dependencies where supported. Default: false.

severities
  Comma-separated Trivy severities. Default: HIGH,CRITICAL.

failOnVulnerabilities
  Whether selected findings fail Jenkins. Default: true.

container
  Jenkins Kubernetes container name. Default: trivy.
```

Secret scan:

```groovy
runTrivySecretScan(
  target: '.',
  skipDirs: securityConfig.trivyFsSkipDirs,
  failOnSecrets: true
)
```

`runTrivySecretScan` common parameters:

```text
target / targets
  Repository-relative scan target or target list.

skipDirs
  Repository-relative paths/globs to skip. Default: [].

severities
  Secret finding severities. Default includes all severities.

failOnSecrets
  Whether findings fail Jenkins. Default: true.

container
  Jenkins Kubernetes container name. Default: trivy.
```

Repository scan skip paths are repository-relative:

```text
frontend/node_modules
node_modules
.venvs
.venv
venv
__pycache__
.git
coverage-reports
```

`runTrivyFSScan` uses `--skip-db-update`, `--cache-dir`, and
`--cache-backend memory`. The DB comes from the persistent PVC cache; each scan
process keeps runtime cache in memory.

`failOnVulnerabilities` controls Trivy's `--exit-code`:

```text
true
  findings at the selected severities return exit code 1 and fail Jenkins

false
  findings are reported, but Trivy returns exit code 0
```

`runTrivySecretScan` does not need the vulnerability DB because it uses secret
rules instead of vulnerability matching.

`runTrivyIaCscan` exists for repositories that own infrastructure manifests,
Helm charts, Terraform, Compose files, or similar IaC. The app repository does
not call it because manifests and Helm charts live in the infrastructure repo.

---

## Release-Branch CI Artifacts

Only `release/*` branch builds run the image artifact stages.

Current release branch stages:

```text
Build Images
Generate Image SBOM
Image Security Scan
Mark Release CI Artifact
```

### Build Images

Usage:

```groovy
runReleaseImages(
  images: imageBuildConfig.images,
  outputDir: 'image-artifacts',
  platform: 'linux/amd64',
  environment: 'staging',
  failFast: false
)
```

The application Jenkinsfile declares project image metadata:

```groovy
def imageBuildConfig = [
  outputDir: 'image-artifacts',
  platform: 'linux/amd64',
  images: [
    [name: 'user-service', context: 'user-service', dockerfile: 'user-service/Dockerfile'],
    [name: 'todo-service', context: 'todo-service', dockerfile: 'todo-service/Dockerfile'],
    [name: 'frontend', context: 'frontend', dockerfile: 'frontend/Dockerfile']
  ]
]
```

This keeps the Shared Library project-agnostic. A monorepo can pass three
images; a future single-service repository can pass only one image without
changing the library code.

`runReleaseImages` parameters:

```text
images
  Required list of image maps.

branchName
  Branch name to parse. Default: env.BRANCH_NAME.

environment
  Tag environment suffix. Default: staging.

releasePrefix
  Release branch prefix. Default: release/.

outputDir
  Passed to runBuildImages. Default: image-artifacts.

platform
  Passed to runBuildImages. Default: linux/amd64.

container
  Passed to runBuildImages. Default: docker.

archiveArtifacts
  Whether Docker archives are archived in Jenkins. Default: true.

failFast
  Whether sibling image builds stop after the first failure. Default: true.
```

`runReleaseImages()` applies the Homelab release branch convention:

```text
release/<service>-v<version>
```

Example:

```text
release/todo-service-v1.1
```

The library step parses:

```text
service = todo-service
version = v1.1
```

Then it builds only the image that belongs to that service, even if the
Jenkinsfile passes a list of all monorepo images.

Tag format:

```text
<commit>-v<version>-<environment>
```

Example:

```text
todo-service:5ca78aa-v1.1-staging
```

`runReleaseImages()` delegates the actual build to `runBuildImages()`.

`runBuildImages()`:

```text
validates image config
waits for pod-local Docker daemon
runs docker build
runs docker save
writes image-artifacts/images.txt
archives image-artifacts/*.docker.tar and images.txt
sets env.BUILT_IMAGE_ARCHIVES
sets env.BUILT_IMAGE_REFS
```

`runBuildImages` image map keys:

```text
name
  Required logical image name.

context
  Docker build context. Default: ..

dockerfile
  Dockerfile path. Default: <context>/Dockerfile.

image
  Full image reference written to images.txt. Default: <name>:<tag>.

tag
  Image tag used when image is omitted. Default: local, or value passed by
  runReleaseImages.

platform
  Image-specific platform override.

target
  Optional Dockerfile target stage.

buildArgs
  Optional Docker build args map.
```

It uses the `docker` container connected to `docker-dind`:

```text
DOCKER_HOST=tcp://localhost:2375
```

The library intentionally does not allow Jenkinsfiles to override `dockerHost`.
The build must use only the pod-local Docker daemon.

Build output is archived with Jenkins' built-in `archiveArtifacts(...)` step.
These image tar files are Jenkins build artifacts, not BuildKit artifacts. They
are stored under Jenkins build history on `jenkins-home-pvc`. Retention is
controlled from the Jenkinsfile with `buildDiscarder(...)`; in the current test
setup only a small number of recent artifacts should remain.

### Image Manifest

`images.txt` is a small manifest file written next to the tar archives. It is
the contract between build, scan, push, and deploy library steps.

Format:

```text
<image-name>  <local-image-ref>  <archive-path>  <platform>
```

Example:

```text
todo-service  todo-service:5ca78aa-v1.1-staging  image-artifacts/todo-service_5ca78aa-v1.1-staging.docker.tar  linux/amd64
```

This file lets later library steps know exactly which tar file belongs to which image
without guessing filenames. It records which service was built, which image
reference was used, where the tar file is, and which platform was targeted.

### Generate Image SBOM

Usage:

```groovy
runTrivySBOM(
  imageManifest: 'image-artifacts/images.txt',
  outputDir: 'sbom-reports',
  format: 'cyclonedx',
  uploadToDependencyTrack: false,
  failFast: false
)
```

`runTrivySBOM` runs in the `trivy` container by default.

Input:

```text
image-artifacts/<image>.docker.tar
```

Output:

```text
sbom-reports/<image>.cyclonedx.json
sbom-reports/sboms.txt
```

`runTrivySBOM` parameters:

```text
imageManifest
  Path to images.txt. Default: image-artifacts/images.txt.

imageArchives
  Explicit Docker archive paths. Requires imageRefs when used.

imageRefs
  Explicit image references paired with imageArchives.

outputDir
  SBOM report directory. Default: sbom-reports.

format
  SBOM format. Default: cyclonedx.

uploadToDependencyTrack
  Upload generated CycloneDX SBOMs. Default: false.

dependencyTrackUrl
  Dependency-Track API URL.

dependencyTrackCredentialsId
  Jenkins Secret Text credential. Default: dependency-track-api-key.

container
  Jenkins Kubernetes container name. Default: trivy.

failFast
  Whether sibling SBOM branches stop after the first failure. Default: true.
```

SBOM generation runs before the image vulnerability gate so the supply-chain
artifact is still available even if the scan later reports vulnerabilities.

SBOM generation uses Trivy's memory cache backend. It does not need the Trivy
vulnerability DB because CycloneDX SBOM generation is component inventory, not
vulnerability matching.

If `uploadToDependencyTrack: true`, the library step calls
`uploadSBOMsToDependencyTrack()`, which wraps the Dependency-Track Jenkins
plugin step:

```text
dependencyTrackPublisher
```

The Trivy container is pinned to:

```text
aquasec/trivy:0.70.0
```

This keeps generated CycloneDX output compatible with the current
Dependency-Track setup.

### Dependency-Track Upload

Dependency-Track upload is optional and disabled by default:

```text
uploadToDependencyTrack: false
```

When enabled, the flow is:

```text
runTrivySBOM
  -> generate CycloneDX SBOM
  -> uploadSBOMsToDependencyTrack
  -> dependencyTrackPublisher
  -> Dependency-Track API
```

Dependency-Track upload requires CycloneDX:

```text
uploadToDependencyTrack=true
format must be cyclonedx
```

The upload library step uses Jenkins Secret Text credential:

```text
dependency-track-api-key
```

`uploadSBOMsToDependencyTrack` parameters:

```text
sboms
  Required list of [file, projectName, projectVersion] maps.

dependencyTrackUrl
  Dependency-Track API server URL.
  Default: http://dtrack-dependency-track-api-server.dependency-track.svc.cluster.local:8080.

credentialsId
  Jenkins Secret Text credential id. Default: dependency-track-api-key.

synchronous
  Wait for Dependency-Track processing result. Default: false.

failOnUploadError
  Fail Jenkins when upload fails. Default: true.
```

The library does not pass `autoCreateProjects` to the Jenkins plugin because
some installed plugin versions do not support that Pipeline parameter. Missing
project behavior should be handled by Dependency-Track/plugin configuration.

### Image Security Scan

Usage:

```groovy
runTrivyScan(
  imageManifest: 'image-artifacts/images.txt',
  outputDir: 'trivy-image-reports',
  severities: 'HIGH,CRITICAL',
  failOnVulnerabilities: false,
  skipDirs: securityConfig.trivyImageSkipDirs,
  failFast: false
)
```

Input:

```text
image-artifacts/<image>.docker.tar
```

Output:

```text
trivy-image-reports/<image>.trivy.txt
trivy-image-reports/<image>.trivy.json
```

`runTrivyScan` parameters:

```text
imageManifest
  Path to images.txt. Default: image-artifacts/images.txt.

imageArchives
  Explicit Docker archive paths. Requires imageRefs when used.

imageRefs
  Explicit image references paired with imageArchives.

outputDir
  Trivy image report directory. Default: trivy-image-reports.

severities
  Comma-separated Trivy severities. Default: HIGH,CRITICAL.

failOnVulnerabilities
  Whether selected findings fail Jenkins. Default: true.

skipDirs
  Image-internal skip paths. Default: [].

container
  Jenkins Kubernetes container name. Default: trivy.

failFast
  Whether sibling image scans stop after the first failure. Default: true.
```

Image scan skip paths are image-internal paths. They are intentionally separate
from repository scan skip paths. In the current app Jenkinsfile,
`trivyImageSkipDirs` is empty.

`failOnVulnerabilities` controls Trivy's exit code:

```text
true
  selected severity findings fail the Jenkins build

false
  findings are reported and archived, but the build can continue
```

### Mark Release CI Artifact

Usage:

```groovy
markReleaseCiArtifact(
  outputDir: 'image-artifacts'
)
```

This writes:

```text
image-artifacts/ci-success.txt
```

Example content:

```text
branch=release/todo-service-v1.1
commit=5ca78aa
build=12
```

`markReleaseCiArtifact` parameters:

```text
outputDir
  Directory containing image artifacts. Default: image-artifacts.

imageManifest
  Manifest that must exist before marking success.
  Default: <outputDir>/images.txt.

markerFile
  Marker file path. Default: <outputDir>/ci-success.txt.

branchName
  Branch name. Default: env.BRANCH_NAME.

releasePrefix
  Release branch prefix. Default: release/.

onlyReleaseBranches
  Skip non-release branches when true. Default: true.
```

The marker is archived as a Jenkins artifact. Manual release jobs require this
marker when they reuse CI artifacts. If the latest completed CI build failed
before this marker stage, release stops instead of silently promoting an older
successful build.

---

## Manual Release Flow

Manual release jobs are defined by Jenkins JCasC but load their Pipeline from:

```text
pipelines/release/Jenkinsfile
```

Current jobs:

```text
release-user-service
release-todo-service
release-frontend
```

User parameters:

```text
RELEASE_BRANCH
DEPLOY_ENVIRONMENT
USE_CI_ARTIFACT
DEPLOY
```

Release stages:

```text
Resolve CI Artifact
Checkout Selected Branch
Copy CI Image Artifact
Validate CI Image Artifact
Build Image Fallback
Push to Registry
Deploy with ArgoCD Helm
```

### Resolve CI Artifact

The release Jenkinsfile resolves service config from the Jenkins job name:

```text
release-user-service -> user-service
release-todo-service -> todo-service
release-frontend     -> frontend
```

It validates that the selected branch belongs to that service:

```text
release/user-service-v<version>
release/todo-service-v<version>
release/frontend-v<version>
```

Production releases require `USE_CI_ARTIFACT=true`. Fallback rebuilds are
allowed for non-prod release jobs only.

The release job also writes a build description:

```text
User: <user> / env: <env> / deploy: <true|false> / branch: <branch> / service: <service> / mode: <ci-artifact|self-build>
```

This uses the Build User Vars plugin through `withBuildUser()`.

### Checkout Selected Branch

The release job checks out the selected app branch and records:

```text
SELECTED_BRANCH_COMMIT
SELECTED_BRANCH_SHORT_COMMIT
```

This commit is later compared with the image artifact commit.

### Copy And Validate CI Artifact

When `USE_CI_ARTIFACT=true`, the release job uses the Copy Artifact plugin:

```groovy
copyArtifacts(
  projectName: env.CI_JOB_FULL_NAME,
  selector: lastCompleted(),
  filter: 'image-artifacts/**',
  fingerprintArtifacts: true
)
```

This copies:

```text
image-artifacts/images.txt
image-artifacts/<image>.docker.tar
image-artifacts/ci-success.txt
```

Validation then checks:

```text
ci-success.txt exists
images.txt contains the selected service
image tag commit matches selected branch HEAD commit
```

The job deliberately uses the latest completed CI build plus the
`ci-success.txt` marker. If the latest completed build failed before the marker,
release fails. It does not silently fall back to an older successful build.

Example:

```text
commit A -> CI SUCCESS
commit B -> CI FAILURE
```

If the release job used `lastSuccessful()`, Jenkins could return commit A and a
release could accidentally promote an old artifact while the branch HEAD is
commit B.

With the current `lastCompleted()` selector, Jenkins selects commit B. Because
that failed CI run did not archive `ci-success.txt`, the release stops. The
selected artifact must also match the selected branch HEAD commit, so an older
archive cannot be promoted after the branch moves.

### Build Image Fallback

When `USE_CI_ARTIFACT=false`, the release job builds the selected service image
itself:

```groovy
runReleaseImages(
  images: releaseImages(),
  branchName: env.SELECTED_BRANCH_NORMALIZED,
  outputDir: 'image-artifacts',
  platform: 'linux/amd64',
  environment: params.DEPLOY_ENVIRONMENT,
  failFast: false
)
```

This fallback path exists for staging convenience. It is blocked for production.

### Push To Registry

Usage:

```groovy
pushToRegistry(
  imageManifest: 'image-artifacts/images.txt',
  registry: 'ghcr.io',
  registryNamespace: 'keremar',
  repositories: registryRepositories(),
  environment: params.DEPLOY_ENVIRONMENT,
  branchName: env.SELECTED_BRANCH_NORMALIZED,
  credentialsId: 'github-token'
)
```

`pushToRegistry` runs in the `docker` container by default.

Flow:

```text
read image-artifacts/images.txt
docker load image-artifacts/<image>.docker.tar
rewrite tag environment when branch is release/*
docker tag local image to registry image
docker login with github-token
docker push
docker logout and remove workspace Docker auth
write image-artifacts/pushed-images.txt
archive pushed-images.txt
```

`pushToRegistry` parameters:

```text
imageManifest
  Path to images.txt. Default: image-artifacts/images.txt.

registry
  Registry host. Default: ghcr.io.

registryNamespace
  Registry owner or namespace. Required for this setup.

repositories
  Map from logical image name to registry repository name.
  When non-empty, every image must have an explicit mapping.
  Missing mappings fail the release.

repositoryPrefix
  Used only when no repositories map is supplied.

environment
  Optional release tag environment, such as staging or prod.

branchName
  Branch name used to decide whether release tag rewriting applies.

credentialsId
  Jenkins username/password credential for docker login. Default: github-token.

container
  Jenkins Kubernetes container name. Default: docker.
```

Example `pushed-images.txt` row:

```text
todo-service  todo-service:5ca78aa-v1.1-staging  ghcr.io/keremar/todo-app-todo-service:5ca78aa-v1.1-staging  image-artifacts/todo-service_5ca78aa-v1.1-staging.docker.tar
```

Fields:

```text
logical image name
local image reference loaded from Docker archive
final registry image reference
source archive path
```

### Deploy With ArgoCD Helm

Current preferred deploy library step:

```groovy
deployWithArgoHelm(
  service: service.name,
  environment: params.DEPLOY_ENVIRONMENT,
  pushedManifest: 'image-artifacts/pushed-images.txt',
  configRepoUrl: 'https://github.com/KeremAR/Homelab-Infrastructure.git',
  configRepoBranch: 'main',
  helmRoot: '6-Helm-Deploy',
  credentialsId: 'github-token'
)
```

Flow:

```text
read pushed image from pushed-images.txt
extract image tag
call updateGithub
update 6-Helm-Deploy/<service>/values-staging.yaml
  or 6-Helm-Deploy/<service>/values-production.yaml
commit and push Homelab-Infrastructure
ArgoCD notices Git change
ArgoCD syncs Helm Application
```

`deployWithArgoHelm` parameters:

```text
service
  Required service name.

environment
  Required target environment: staging, prod, or production.

pushedManifest
  Path to pushed-images.txt. Default: image-artifacts/pushed-images.txt.

configRepoUrl
  Git repository containing Helm charts and values files.

configRepoBranch
  Config repository branch. Default: main.

helmRoot
  Helm chart root inside the config repository. Default: 6-Helm-Deploy.

credentialsId
  Jenkins credentials used to push config repo changes. Default: github-token.

argoApplication
  Optional ArgoCD Application name used only for logs.
```

`deployWithArgoHelm()` does not run `helm upgrade` and does not run
`kubectl apply`. The cluster deployment is performed by ArgoCD.

Other deploy library steps still exist for transitional paths:

```text
deployWithHelm
  Updates Helm values and runs helm upgrade directly with kubeconfig.

deployWithArgoKubectl
  Updates raw manifests and lets ArgoCD sync plain YAML.

deployWithKubectl
  Updates raw manifests and runs kubectl apply directly with kubeconfig.
```

---

## updateGithub

`updateGithub()` is the generic safe Git update library step used by deploy library steps.

It handles:

```text
checkout config repository
patch one file according to operation
show git diff
commit
push
lock repo/branch updates
retry rejected pushes with fetch/rebase
restore remote URL after credentials are used
```

Supported operations:

```text
kubernetesContainerImage
  Updates the image field for a named container in a Kubernetes manifest.

helmImageTag
  Updates top-level image.tag in a Helm values file.
```

Common `updateGithub` parameters:

```text
repoUrl
  Required GitHub repository URL.

branch
  Target branch. Default: main.

file
  Repository-relative file path to patch.

operation
  Patch operation: kubernetesContainerImage or helmImageTag.

credentialsId
  Jenkins credentials used for checkout and push. Default: github-token.

commitMessage
  Optional commit message. A default is generated when omitted.

lockResource
  Optional lock override. By default the lock is derived from repository and
  branch: github-update-<repo>-<branch>.

maxPushRetries
  Maximum Git push attempts after fetch/rebase when the remote branch moved.
  Default: 3.

containerName / image / imageTag
  Operation-specific values used to patch the selected file.
```

The lock is important when two release jobs update the same config repository
branch at the same time. It serializes Jenkins-side pushes. The retry handles
the remaining case where something outside Jenkins updates the branch between
checkout and push.

---

## Validators

Reusable libraries validate user-provided config before passing values into
`dir()`, `sh`, Git commands, Docker commands, Trivy commands, or generated
properties files.

### Internal Helper Function Order

Most private functions exist to normalize Jenkinsfile input, validate paths, and
make failures happen before shell commands run.

Lint helpers:

```text
runPythonLinting
  Validation.uniqueRelativePaths -> validates targets and detects duplicates.
  fileExists                    -> fails before Jenkins dir() can create an empty directory.

runNodeLinting
  Validation.npmScriptName      -> validates lintScript.
  Validation.uniqueRelativePaths -> validates packageDirs and detects duplicates.
  fileExists package files      -> requires package.json and package-lock.json.

runHadolint
  Validation.relativePath       -> validates configFile.
  Validation.uniqueRelativePaths -> validates Dockerfile paths and detects duplicates.
  fileExists                    -> requires Dockerfile and explicit configFile.
  Validation.shellQuote         -> safely quotes paths in the shell command.
```

Unit test helper:

```text
cachePath
  Validates pipCacheDir before it reaches shell commands.

lockName
  Validates the Jenkins lock resource name for pip cache writes.

parsePositiveInteger
  Validates pipInstallRetries.

normalizeServices
  Applies defaults and converts every service map into one internal shape.

parseRequiredInteger
  Requires service-level coverageThreshold.

validateUniqueServiceNames
  Prevents duplicate parallel branch/report/venv names.

validateServiceFiles
  Requires target, test path, requirements file, and .coveragerc before pytest.
```

SonarQube helper:

```text
normalizeConfig
  Applies defaults for server, scanner, properties file, timeout, and booleans.

validateProjectKey
  Validates the SonarQube project key.

asList
  Normalizes single values and lists for path/glob settings.

Validation.uniqueRelativePaths
  Validates sources and coverage report paths.

Validation.uniqueSafeGlobs
  Validates exclusions, test inclusions, and CPD exclusions.

normalizeExtraProperties
  Validates custom sonar.* properties and blocks managed/security keys.

validateInputFiles
  Requires source paths and coverage reports before scanner execution.

writeSonarProperties
  Writes sonar-project.properties for sonar-scanner.

parsePositiveInteger
  Validates timeoutMinutes.

joinCsv
  Builds comma-separated SonarQube property values.
```

Trivy helpers:

```text
TrivyValidation.severities
  Validates severity lists.

TrivyValidation.timeout
  Validates Trivy timeout format.

TrivyValidation.cachePath
  Validates cache directories.

TrivyValidation.filePatterns
  Validates extra file discovery patterns.

TrivyValidation.skipPaths
  Validates repository-relative skip paths.

TrivyValidation.imageSkipPaths
  Validates image-internal skip paths.
```

Image build helpers:

```text
runReleaseImages
  ReleaseResolver.resolve       -> parses branch, service, and version.
  resolveShortCommit            -> reads the Git short commit.
  tagSegment                    -> validates tag pieces.
  nonReleaseImages              -> requires one image for non-release use.
  runBuildImages                -> performs the actual build/archive work.

runBuildImages
  Validation.relativePath       -> validates outputDir, context, dockerfile, target.
  platform                      -> validates linux/amd64-style platform values.
  dockerTag / imageReference    -> validates tags and image references.
  normalizeImages               -> applies image defaults and output filenames.
  validateBuildFiles            -> requires build context and Dockerfile.
  validateUniqueOutputFiles     -> prevents archive filename collisions.
  normalizeBuildArgs            -> validates Docker build args.
  writeImageManifest            -> writes image-artifacts/images.txt.
```

Image scan and SBOM helpers:

```text
resolveImages
  Reads images.txt or explicit imageArchives/imageRefs.

validateUniqueReports / validateUniqueSboms
  Prevents output filename collisions.

safeFileBase
  Converts image references into filesystem-safe report names.

writeSbomManifest
  Writes sbom-reports/sboms.txt.
```

Release push and deploy library steps:

```text
pushToRegistry
  ImageArtifactManifest.parse   -> parses image-artifacts/images.txt.
  normalizeRepositories         -> validates repository map.
  validateUniqueTargets         -> prevents pushing the same target twice.
  rewriteEnvironmentTag         -> rewrites release tags for staging/prod.
  writePushedManifest           -> writes image-artifacts/pushed-images.txt.

deployWithArgoHelm / deployWithHelm
  pushedImageRef                -> selects the service image from pushed-images.txt.
  environmentName               -> normalizes staging/prod/production.
  valuesSuffixForEnvironment    -> selects staging or production values file.
  tagFromImageRef               -> extracts the tag written into Helm values.

deployWithArgoKubectl / deployWithKubectl
  pushedImageRef                -> selects the service image from pushed-images.txt.
  namespaceForEnvironment       -> maps prod to production.
  resourceName / imageReference -> validates service names and image references.

updateGithub
  required                      -> requires operation inputs.
  branchName / resourceName     -> validates branch and resource names.
  imageReference / validateImageTag -> validates image values.
  lockKey                       -> builds a repo/branch-specific lock key.
  positiveInt                   -> validates retry count.
```

### Validation

Class:

```text
src/com/company/jenkins/Validation.groovy
```

Used for generic paths, globs, npm script names, and shell quoting.

Important behavior:

```text
repository-relative paths only
no absolute paths
no parent directory traversal
no values starting with '-'
restricted character sets
duplicate list detection
single-quote shell escaping
```

Why this matters:

```text
wrong path should fail before Jenkins dir() creates an empty directory
CLI option-looking values should not become command flags
duplicate parallel branch names should not overwrite each other
generated config files should not receive injected multiline values
```

### TrivyValidation

Class:

```text
src/com/company/jenkins/TrivyValidation.groovy
```

Used for:

```text
severity lists
timeouts
cache paths
file patterns
repository skip paths
image-internal skip paths
```

Repository skip paths and image skip paths are intentionally different:

```text
repository scan skip path
  frontend/node_modules

image scan skip path
  /app/tmp
```

Most image scans should keep skip paths empty so SBOM and image vulnerability
scans see the real image contents.

### ReleaseResolver

Class:

```text
src/com/company/jenkins/ReleaseResolver.groovy
```

Used by:

```text
ciLintPodTemplate
runReleaseImages
```

It keeps release branch parsing consistent:

```text
release/<service>-v<version>
```

This matters because the pod template chooses the Docker cache PVC before
stages run, while `runReleaseImages()` chooses which service image to build
during the stage. Both must resolve the same service.

### ImageArtifactManifest

Class:

```text
src/com/company/jenkins/ImageArtifactManifest.groovy
```

Used by image scan, SBOM, and push library steps to parse image artifact manifests.

It is specifically for the build artifact manifest:

```text
image-artifacts/images.txt
```

The contract is one tab-separated row per built image:

```text
<image-name>  <local-image-ref>  <archive-path>  <platform>
```

Main method:

```groovy
ImageArtifactManifest.parse(...)
```

`image-artifacts/pushed-images.txt` and `sbom-reports/sboms.txt` are separate
contracts written by `pushToRegistry()` and `runTrivySBOM()`.

---

## Jenkins Plugins Used By The Library

```text
Kubernetes plugin
  Provides agent { kubernetes { ... } } and container('name').

Credentials Binding plugin
  Provides withCredentials for GitHub, Dependency-Track, and kubeconfig.

Lockable Resources plugin
  Provides lock() for Trivy DB, pip cache, and Git update serialization.

SonarQube plugin
  Provides withSonarQubeEnv, SonarScanner tool config, and waitForQualityGate.

Dependency-Track plugin
  Provides dependencyTrackPublisher for CycloneDX SBOM upload.

Copy Artifact plugin
  Provides copyArtifacts for manual release jobs to reuse CI image artifacts.

Build User Vars plugin
  Provides build user variables for release build descriptions.

JUnit / test reporting
  Provides junit(...) for pytest JUnit XML files.

Core Pipeline artifact support
  Provides archiveArtifacts(...).
```

---

## Artifact Contracts

Coverage reports:

```text
coverage-reports/<service>/junit.xml
coverage-reports/<service>/coverage.xml
```

Image build manifest:

```text
image-artifacts/images.txt
```

Image archive:

```text
image-artifacts/<image-ref-safe>.docker.tar
```

Release CI success marker:

```text
image-artifacts/ci-success.txt
```

Pushed image manifest:

```text
image-artifacts/pushed-images.txt
```

SBOM manifest:

```text
sbom-reports/sboms.txt
```

Trivy image reports:

```text
trivy-image-reports/<image>.trivy.txt
trivy-image-reports/<image>.trivy.json
```

SonarQube issue fetch:

```text
sonarqube-issues.json
```

These files are deliberately plain text or XML/JSON artifacts so later jobs can
reuse them without calling Jenkins internal APIs.

---

## Adding A New Project

For a new repository, keep project-specific policy in the Jenkinsfile and reuse
the library steps.

Define lint targets:

```groovy
def lintConfig = [
  pythonTargets: ['service-a'],
  nodePackageDirs: ['frontend'],
  dockerfiles: ['service-a/Dockerfile']
]
```

Define unit test services:

```groovy
def unitTestServices = [
  [
    name: 'service-a',
    target: 'service-a',
    requirementsFile: 'service-a/requirements-test.txt',
    testPath: '.',
    coverageThreshold: 70
  ]
]
```

Define image metadata:

```groovy
def imageBuildConfig = [
  outputDir: 'image-artifacts',
  platform: 'linux/amd64',
  images: [
    [
      name: 'service-a',
      context: 'service-a',
      dockerfile: 'service-a/Dockerfile'
    ]
  ]
]
```

The library stays project-agnostic because the Jenkinsfile supplies paths,
services, Dockerfiles, coverage thresholds, registry repository names, and
deployment repository paths.

---

## Global Variable Reference

Most `vars/*.groovy` files have matching `vars/*.txt` files. Jenkins renders
those `.txt` files under:

```text
Pipeline Syntax -> Global Variable Reference
```

Keep the `.txt` files short and parameter-focused. Use this README for the
end-to-end flow and design explanation.
