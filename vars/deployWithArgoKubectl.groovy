#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Update the Kubernetes config repository and let ArgoCD deploy it.
 *
 * This helper is intended for GitOps-based manual release pipelines. It reads
 * the pushed registry image from image-artifacts/pushed-images.txt, updates the
 * source-controlled Kubernetes deployment manifest through updateGithub, and
 * stops there. ArgoCD observes the Git change and syncs the application.
 *
 * DEPLOY BEHAVIOR:
 * - Reads the pushed registry image from pushed-images.txt
 * - Calls updateGithub to update and push the deployment.yaml image
 * - Does not run kubectl apply
 * - Does not require kubeconfig credentials
 * - Relies on ArgoCD automated sync for the actual cluster rollout
 *
 * SECURITY:
 * - Uses Git credentials only for checkout and pushing the manifest commit
 * - Does not need Kubernetes credentials in Jenkins
 * - Validates repository-relative paths, Kubernetes names, and image refs
 *
 * @param config Map containing:
 *   - service: REQUIRED - Logical service name, for example 'user-service'
 *   - environment: REQUIRED - 'staging', 'prod', or 'production'
 *   - pushedManifest: pushed-images.txt path (default: 'image-artifacts/pushed-images.txt')
 *   - configRepoUrl: Kubernetes config repo URL
 *   - configRepoBranch: Kubernetes config repo branch (default: 'main')
 *   - configRepoDir: Workspace checkout directory (default: 'deploy-config')
 *   - manifestsRoot: Manifests root inside config repo (default: '3-Kubectl-Deploy')
 *   - credentialsId: Git credentials id (default: 'github-token')
 *   - namespace: Kubernetes namespace override
 *   - containerName: Container name inside Kubernetes manifest (default: service)
 *   - argoApplication: Optional ArgoCD Application name for logs
 *   - gitUserName: Commit user name (default: 'Jenkins CI')
 *   - gitUserEmail: Commit user email (default: 'jenkins@ci.local')
 *
 * @example
 * deployWithArgoKubectl(
 *     service: 'todo-service',
 *     environment: 'staging',
 *     configRepoUrl: 'https://github.com/KeremAR/Homelab-Infrastructure.git'
 * )
 */
def call(Map config = [:]) {
    String service = resourceName(required(config, 'service').toString(), 'service')
    String environment = environmentName(required(config, 'environment').toString())
    String namespace = config.namespace ? resourceName(config.namespace.toString(), 'namespace') : namespaceForEnvironment(environment)
    String containerName = resourceName((config.containerName ?: service).toString(), 'containerName')
    String pushedManifest = Validation.relativePath((config.pushedManifest ?: 'image-artifacts/pushed-images.txt').toString(), 'Pushed image manifest')
    String configRepoUrl = required(config, 'configRepoUrl').toString()
    String configRepoBranch = branchName((config.configRepoBranch ?: 'main').toString(), 'configRepoBranch')
    String configRepoDir = Validation.relativePath((config.configRepoDir ?: 'deploy-config').toString(), 'Config repo checkout directory')
    String manifestsRoot = Validation.relativePath((config.manifestsRoot ?: '3-Kubectl-Deploy').toString(), 'Manifests root')
    String credentialsId = (config.credentialsId ?: 'github-token').toString()
    String gitUserName = (config.gitUserName ?: 'Jenkins CI').toString()
    String gitUserEmail = emailAddress((config.gitUserEmail ?: 'jenkins@ci.local').toString(), 'gitUserEmail')
    String argoApplication = config.argoApplication ? resourceName(config.argoApplication.toString(), 'argoApplication') : "${namespace}-${service}"

    if (!fileExists(pushedManifest)) {
        error "Pushed image manifest does not exist: ${pushedManifest}. Run pushToRegistry before deployWithArgoKubectl."
    }

    String imageRef = pushedImageRef(readFile(pushedManifest), pushedManifest, service)

    String manifestDir = "${manifestsRoot}/${namespace}/${service}/templates"
    String deploymentFile = "${manifestDir}/deployment.yaml"

    updateGithub(
        repoUrl: configRepoUrl,
        branch: configRepoBranch,
        checkoutDir: configRepoDir,
        credentialsId: credentialsId,
        file: deploymentFile,
        operation: 'kubernetesContainerImage',
        containerName: containerName,
        image: imageRef,
        commitMessage: "ci: update ${service} image to ${imageRef}",
        gitUserName: gitUserName,
        gitUserEmail: gitUserEmail
    )

    echo "GitOps deploy requested for ${service} in ${namespace} with image ${imageRef}"
    echo "ArgoCD Application ${argoApplication} will sync ${deploymentFile} from ${configRepoUrl}@${configRepoBranch}"
}

private Object required(Map config, String key) {
    if (!config.containsKey(key) || config[key] == null || config[key].toString().trim() == '') {
        error "deployWithArgoKubectl requires '${key}'"
    }

    return config[key]
}

private String pushedImageRef(String content, String manifestPath, String service) {
    List<String> lines = content.readLines().findAll { line -> line.trim() }
    String row = lines.find { line ->
        List<String> fields = line.split('\t') as List<String>
        fields.size() == 4 && fields[0] == service
    }

    if (!row) {
        error "Pushed image manifest does not contain service ${service}: ${manifestPath}"
    }

    List<String> fields = row.split('\t') as List<String>
    return imageReference(fields[2], "Pushed image reference for ${service}")
}

private String environmentName(String value) {
    String normalized = value.trim().toLowerCase()
    if (!(normalized in ['staging', 'prod', 'production'])) {
        error "Unsupported deploy environment: ${value}. Expected staging, prod, or production."
    }

    return normalized
}

private String namespaceForEnvironment(String environment) {
    return environment == 'prod' ? 'production' : environment
}

private String resourceName(String value, String label) {
    String normalized = value.trim()
    if (!(normalized ==~ /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/)) {
        error "Invalid Kubernetes ${label}: ${value}"
    }

    return normalized
}

private String branchName(String value, String label) {
    String normalized = value.trim()
    normalized = normalized.replaceFirst(/^refs\/heads\//, '')
    normalized = normalized.replaceFirst(/^origin\//, '')

    if (!normalized || normalized.startsWith('-') || normalized.contains('..')) {
        error "Invalid ${label}: ${value}"
    }

    if (!(normalized ==~ /^[A-Za-z0-9._\/-]+$/)) {
        error "Invalid characters in ${label}: ${value}"
    }

    return normalized
}

private String imageReference(String value, String label) {
    String normalized = value.trim()
    if (!normalized || normalized.startsWith('-') || normalized.contains('..')) {
        error "Invalid ${label}: ${value}"
    }

    if (!(normalized ==~ /^[A-Za-z0-9._:\/@-]+$/)) {
        error "Invalid characters in ${label}: ${value}"
    }

    if (!normalized.contains(':') && !normalized.contains('@')) {
        error "${label} must contain a tag or digest: ${value}"
    }

    return normalized
}

private String emailAddress(String value, String label) {
    String normalized = value.trim()
    if (!(normalized ==~ /^[^@\s]+@[^@\s]+\.[^@\s]+$/)) {
        error "Invalid ${label}: ${value}"
    }

    return normalized
}
