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
the Jenkins assumptions, then explains the CI flow, then the release flow, and
finally the shared validation and artifact contracts.

---

## 1. Repository Layout

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

## 2. Jenkins Assumptions

Jenkins registers this repo as a global shared library named:

```text
homelab-shared-library
```

Application Jenkinsfiles use it with:

```groovy
@Library('homelab-shared-library') _
```

The current Jenkins setup provides:

```text
Kubernetes plugin
GitHub Branch Source plugin
Job DSL plugin
SonarQube plugin
Dependency-Track plugin
Copy Artifact plugin
Lockable Resources plugin
Credentials Binding plugin
Build User Vars plugin
```

Important Jenkins credentials:

```text
github-token
sonarqube-token
dependency-track-api-key
kubeconfig
```

Important Kubernetes imagePullSecret:

```text
ghcr-creds
```

`github-token` is used for Git checkout, GitHub updates, and GHCR push.
`ghcr-creds` is used by Kubernetes when pulling private agent images from GHCR.

---

## 3. Agent Pods

The library provides two Kubernetes agent pod templates.

### CI Agent

Helper:

```groovy
ciLintPodTemplate(...)
```

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

Helper:

```groovy
releasePodTemplate(...)
```

Resource:

```text
resources/com/company/jenkins/pods/release-pod.yaml
```

The release pod is smaller than the CI pod.

Containers:

```text
jnlp
docker
docker-dind
kubernetes
```

`docker` and `docker-dind` are used for:

```text
docker load
docker tag
docker login
docker push
fallback docker build
```

`kubernetes` uses:

```text
ghcr.io/keremar/kubernetes-tools:kubectl-1.36.1-helm-3.20.1-argocd-3.4.2-rollouts-1.9.1
```

That image contains `sh`, `kubectl`, `helm`, `argocd`, and `kubectl argo
rollouts`. The current preferred deploy helper is `deployWithArgoHelm()`, which
updates Git and lets ArgoCD sync, so it normally does not need to run kubectl or
helm directly. The container remains available for transitional direct
deployment helpers.

---

## 4. Helper Overview

CI helpers:

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

Release-branch CI helpers:

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

Manual release helpers:

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

Pod template helpers:

```text
ciLintPodTemplate
releasePodTemplate
ciPythonPodTemplate
```

`ciPythonPodTemplate()` is a backward-compatible alias for older Jenkinsfiles.
New Jenkinsfiles should use `ciLintPodTemplate()`.

---

## 5. Current App CI Flow

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

## 6. Linting

The linting stage runs three helpers in parallel.

Python:

```groovy
runPythonLinting(
  targets: ['user-service', 'todo-service'],
  failFast: false
)
```

Container:

```text
python
```

Commands:

```text
black --check .
flake8 .
```

Frontend:

```groovy
runNodeLinting(
  packageDirs: ['frontend'],
  lintScript: 'lint',
  failFast: false
)
```

Container:

```text
node
```

Commands:

```text
npm ci --prefer-offline --no-audit
npm run lint
```

Dockerfiles:

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

Container:

```text
hadolint
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

## 7. Unit Tests

Usage:

```groovy
runUnitTest(
  services: unitTestServices,
  coverageDir: 'coverage-reports',
  failFast: false
)
```

Container:

```text
python
```

Each service declares:

```text
name
target
requirementsFile
testPath
coverageThreshold
```

Current services:

```text
user-service -> coverageThreshold 70
todo-service -> coverageThreshold 70
```

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

That path is backed by `jenkins-venv-cache-pvc`. The helper sets `PIP_CACHE_DIR`
and calls `pip install --cache-dir /cache/pip`.

The pip install step is wrapped with:

```groovy
lock(resource: 'jenkins-pip-cache')
```

This protects the shared pip cache from concurrent writes by multiple Jenkins
agent pods. It does not serialize the whole test stage; only the cache-writing
install section is locked.

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

Because unit tests run in parallel, the helper sets a per-service
`COVERAGE_FILE` through `withEnv`:

```text
coverage-reports/user-service/.coverage
coverage-reports/todo-service/.coverage
```

This prevents parallel test branches from writing to the same raw coverage data
file.

---

## 8. SonarQube

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

Container:

```text
sonar
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

The helper writes `sonar-project.properties` into the workspace before running
the scanner. Common properties have direct helper parameters. Less common
project-specific properties can be passed through `extraProperties`.

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

Branch issue policy is owned by the application Jenkinsfile:

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

## 9. Static Security Scan

The static security stages run after SonarQube.

First:

```groovy
ensureTrivyDB()
```

Container:

```text
trivy
```

Cache:

```text
/home/jenkins/.cache/trivy
```

That path is backed by `jenkins-trivy-cache-pvc`.

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

Secret scan:

```groovy
runTrivySecretScan(
  target: '.',
  skipDirs: securityConfig.trivyFsSkipDirs,
  failOnSecrets: true
)
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

`runTrivySecretScan` does not need the vulnerability DB because it uses secret
rules instead of vulnerability matching.

`runTrivyIaCscan` exists for repositories that own infrastructure manifests,
Helm charts, Terraform, Compose files, or similar IaC. The app repository does
not call it because manifests and Helm charts live in the infrastructure repo.

---

## 10. Release-Branch CI Artifacts

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

`runReleaseImages()` applies the Homelab release branch convention:

```text
release/<service>-v<version>
```

Example:

```text
release/todo-service-v1.1
```

The helper parses:

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

It uses the `docker` container connected to `docker-dind`:

```text
DOCKER_HOST=tcp://localhost:2375
```

The library intentionally does not allow Jenkinsfiles to override `dockerHost`.
The build must use only the pod-local Docker daemon.

### Image Manifest

`images.txt` is the contract between build, scan, push, and deploy helpers.

Format:

```text
<image-name>  <local-image-ref>  <archive-path>  <platform>
```

Example:

```text
todo-service  todo-service:5ca78aa-v1.1-staging  image-artifacts/todo-service_5ca78aa-v1.1-staging.docker.tar  linux/amd64
```

This file lets later helpers know exactly which tar file belongs to which image
without guessing filenames.

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

Container:

```text
trivy
```

Input:

```text
image-artifacts/<image>.docker.tar
```

Output:

```text
sbom-reports/<image>.cyclonedx.json
sbom-reports/sboms.txt
```

SBOM generation runs before the image vulnerability gate so the supply-chain
artifact is still available even if the scan later reports vulnerabilities.

SBOM generation uses Trivy's memory cache backend. It does not need the Trivy
vulnerability DB because CycloneDX SBOM generation is component inventory, not
vulnerability matching.

If `uploadToDependencyTrack: true`, the helper calls
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

The marker is archived as a Jenkins artifact. Manual release jobs require this
marker when they reuse CI artifacts. If the latest completed CI build failed
before this marker stage, release stops instead of silently promoting an older
successful build.

---

## 11. Manual Release Flow

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

Container:

```text
docker
```

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

Current preferred deploy helper:

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

`deployWithArgoHelm()` does not run `helm upgrade` and does not run
`kubectl apply`. The cluster deployment is performed by ArgoCD.

Other deploy helpers still exist for transitional paths:

```text
deployWithHelm
  Updates Helm values and runs helm upgrade directly with kubeconfig.

deployWithArgoKubectl
  Updates raw manifests and lets ArgoCD sync plain YAML.

deployWithKubectl
  Updates raw manifests and runs kubectl apply directly with kubeconfig.
```

---

## 12. updateGithub

`updateGithub()` is the generic safe Git update helper used by deploy helpers.

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

The lock is important when two release jobs update the same config repository
branch at the same time. It serializes Jenkins-side pushes. The retry handles
the remaining case where something outside Jenkins updates the branch between
checkout and push.

---

## 13. Validators

Reusable libraries validate user-provided config before passing values into
`dir()`, `sh`, Git commands, Docker commands, Trivy commands, or generated
properties files.

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

Used by image scan, SBOM, and push helpers to parse image artifact manifests.

It keeps this format consistent:

```text
image-artifacts/images.txt
image-artifacts/pushed-images.txt
sbom-reports/sboms.txt
```

---

## 14. Jenkins Plugins Used By The Library

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

## 15. Artifact Contracts

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

## 16. Adding A New Project

For a new repository, keep project-specific policy in the Jenkinsfile and reuse
the helpers.

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

## 17. Global Variable Reference

Most `vars/*.groovy` files have matching `vars/*.txt` files. Jenkins renders
those `.txt` files under:

```text
Pipeline Syntax -> Global Variable Reference
```

Keep the `.txt` files short and parameter-focused. Use this README for the
end-to-end flow and design explanation.
