#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Update Helm values in GitHub and optionally deploy the chart with Helm.
 *
 * This helper is intended for the transitional Helm release flow. It reads the
 * pushed registry image from image-artifacts/pushed-images.txt, updates the
 * service chart's environment values file in the config repository, then runs
 * helm upgrade --install when apply is enabled.
 *
 * DEPLOY BEHAVIOR:
 * - Reads the pushed registry image for the selected service
 * - Updates image.tag in values-staging.yaml or values-production.yaml through
 *   updateGithub
 * - Runs helm upgrade --install from the updated config repository checkout
 * - Does not run helm --wait by default, so rollout observation stays external
 *
 * SECURITY:
 * - Does not rely on the Jenkins pod service account token
 * - Uses a Jenkins Secret Text credential containing kubeconfig
 * - Uses Git credentials only for checkout and pushing the values commit
 * - Validates repository-relative paths, image refs, and Kubernetes names
 *
 * @param config Map containing:
 *   - service: REQUIRED - Logical service name, for example 'user-service'
 *   - environment: REQUIRED - 'staging', 'prod', or 'production'
 *   - pushedManifest: pushed-images.txt path (default: 'image-artifacts/pushed-images.txt')
 *   - configRepoUrl: Helm/config repo URL
 *   - configRepoBranch: Helm/config repo branch (default: 'main')
 *   - configRepoDir: Workspace checkout directory (default: 'helm-config')
 *   - helmRoot: Helm chart root inside config repo (default: '6-Helm-Deploy')
 *   - credentialsId: Git credentials id (default: 'github-token')
 *   - kubeconfigCredentialsId: Jenkins Secret Text credential id (default: 'kubeconfig')
 *   - kubeconfigIsBase64: Whether the credential is base64 encoded (default: true)
 *   - namespace: Kubernetes namespace override
 *   - releaseName: Helm release name override (default: '<namespace>-<service>')
 *   - helmContainer: Jenkins Kubernetes container name (default: 'kubernetes')
 *   - apply: Whether to run helm upgrade after Git update (default: true)
 *   - takeOwnership: Pass --take-ownership to Helm (default: false)
 *   - createNamespace: Pass --create-namespace to Helm (default: true)
 *   - extraArgs: Optional list of additional Helm CLI args
 *   - gitUserName: Commit user name (default: 'Jenkins CI')
 *   - gitUserEmail: Commit user email (default: 'jenkins@ci.local')
 *
 * @example
 * deployWithHelm(
 *     service: 'todo-service',
 *     environment: 'staging',
 *     configRepoUrl: 'https://github.com/KeremAR/Homelab-Infrastructure.git'
 * )
 */
def call(Map config = [:]) {
    String service = resourceName(required(config, 'service').toString(), 'service')
    String environment = environmentName(required(config, 'environment').toString())
    String namespace = config.namespace ? resourceName(config.namespace.toString(), 'namespace') : namespaceForEnvironment(environment)
    String valuesSuffix = valuesSuffixForEnvironment(environment)
    String releaseName = config.releaseName ? validateReleaseName(config.releaseName.toString()) : "${namespace}-${service}"
    String pushedManifest = Validation.relativePath((config.pushedManifest ?: 'image-artifacts/pushed-images.txt').toString(), 'Pushed image manifest')
    String configRepoUrl = required(config, 'configRepoUrl').toString()
    String configRepoBranch = branchName((config.configRepoBranch ?: 'main').toString(), 'configRepoBranch')
    String configRepoDir = Validation.relativePath((config.configRepoDir ?: 'helm-config').toString(), 'Config repo checkout directory')
    String helmRoot = Validation.relativePath((config.helmRoot ?: '6-Helm-Deploy').toString(), 'Helm root')
    String credentialsId = (config.credentialsId ?: 'github-token').toString()
    String kubeconfigCredentialsId = (config.kubeconfigCredentialsId ?: 'kubeconfig').toString()
    boolean kubeconfigIsBase64 = config.get('kubeconfigIsBase64', true)
    String helmContainer = (config.helmContainer ?: 'kubernetes').toString()
    boolean applyEnabled = config.get('apply', true)
    boolean takeOwnership = config.get('takeOwnership', false)
    boolean createNamespace = config.get('createNamespace', true)
    List<String> extraArgs = (config.extraArgs ?: []).collect { safeHelmArg(it.toString()) }
    String gitUserName = (config.gitUserName ?: 'Jenkins CI').toString()
    String gitUserEmail = emailAddress((config.gitUserEmail ?: 'jenkins@ci.local').toString(), 'gitUserEmail')

    if (!fileExists(pushedManifest)) {
        error "Pushed image manifest does not exist: ${pushedManifest}. Run pushToRegistry before deployWithHelm."
    }

    String imageRef = pushedImageRef(readFile(pushedManifest), pushedManifest, service)
    String imageTag = tagFromImageRef(imageRef)
    String chartDir = "${helmRoot}/${service}"
    String valuesFile = "${chartDir}/values-${valuesSuffix}.yaml"

    updateGithub(
        repoUrl: configRepoUrl,
        branch: configRepoBranch,
        checkoutDir: configRepoDir,
        credentialsId: credentialsId,
        file: valuesFile,
        operation: 'helmImageTag',
        imageTag: imageTag,
        commitMessage: "ci: update ${service} Helm image to ${imageRef}",
        gitUserName: gitUserName,
        gitUserEmail: gitUserEmail
    )

    if (!applyEnabled) {
        echo "Helm values updated for ${service} in ${namespace}; helm upgrade is disabled."
        return
    }

    container(helmContainer) {
        withCredentials([
            string(credentialsId: kubeconfigCredentialsId, variable: 'KUBECONFIG_CONTENT')
        ]) {
            withEnv([
                "HELM_RELEASE=${releaseName}",
                "HELM_NAMESPACE=${namespace}",
                "HELM_CHART_DIR=${configRepoDir}/${chartDir}",
                "HELM_VALUES_FILE=${configRepoDir}/${valuesFile}",
                "HELM_TAKE_OWNERSHIP=${takeOwnership.toString()}",
                "HELM_CREATE_NAMESPACE=${createNamespace.toString()}",
                "HELM_EXTRA_ARGS=${extraArgs.join(' ')}",
                "KUBECONFIG_IS_BASE64=${kubeconfigIsBase64.toString()}"
            ]) {
                sh(
                    label: "helm deploy ${service}",
                    script: '''
                        set -eu

                        mkdir -p "$WORKSPACE/.kube"
                        export KUBECONFIG="$WORKSPACE/.kube/config"

                        if [ "$KUBECONFIG_IS_BASE64" = "true" ]; then
                            printf '%s' "$KUBECONFIG_CONTENT" | base64 -d > "$KUBECONFIG"
                        else
                            printf '%s' "$KUBECONFIG_CONTENT" > "$KUBECONFIG"
                        fi

                        chmod 600 "$KUBECONFIG"
                        trap 'rm -f "$KUBECONFIG"' EXIT

                        command -v helm
                        helm version --short

                        args=""
                        if [ "$HELM_CREATE_NAMESPACE" = "true" ]; then
                            args="$args --create-namespace"
                        fi

                        if [ "$HELM_TAKE_OWNERSHIP" = "true" ]; then
                            args="$args --take-ownership"
                        fi

                        echo "Deploying Helm release $HELM_RELEASE to namespace $HELM_NAMESPACE"
                        helm upgrade --install "$HELM_RELEASE" "$WORKSPACE/$HELM_CHART_DIR" \
                            --namespace "$HELM_NAMESPACE" \
                            --values "$WORKSPACE/$HELM_VALUES_FILE" \
                            $args \
                            ${HELM_EXTRA_ARGS:-}

                        helm status "$HELM_RELEASE" -n "$HELM_NAMESPACE"
                    '''
                )
            }
        }
    }

    echo "Helm deploy submitted for ${service} in ${namespace} with image ${imageRef}"
}

private Object required(Map config, String key) {
    if (!config.containsKey(key) || config[key] == null || config[key].toString().trim() == '') {
        error "deployWithHelm requires '${key}'"
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

private String valuesSuffixForEnvironment(String environment) {
    return environment == 'prod' ? 'production' : environment
}

private String resourceName(String value, String label) {
    String normalized = value.trim()
    if (!(normalized ==~ /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/)) {
        error "Invalid Kubernetes ${label}: ${value}"
    }

    return normalized
}

private String validateReleaseName(String value) {
    return resourceName(value, 'releaseName')
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

    if (!normalized.contains(':') || normalized.contains('@')) {
        error "${label} must be a tagged image reference: ${value}"
    }

    return normalized
}

private String tagFromImageRef(String imageRef) {
    int slash = imageRef.lastIndexOf('/')
    int colon = imageRef.lastIndexOf(':')

    if (colon > slash && colon < imageRef.length() - 1) {
        return imageRef.substring(colon + 1)
    }

    error "Image reference does not contain a tag: ${imageRef}"
}

private String safeHelmArg(String value) {
    String normalized = value.trim()
    if (!normalized || normalized.contains('\n') || normalized.contains('\r')) {
        error "Invalid Helm extra arg: ${value}"
    }

    if (!(normalized ==~ /^[A-Za-z0-9._=,:\/-]+$/)) {
        error "Invalid characters in Helm extra arg: ${value}"
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
