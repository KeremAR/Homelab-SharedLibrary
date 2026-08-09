#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Update a file in a GitHub repository and push the change.
 *
 * This helper is intentionally not tied to kubectl or ArgoCD. It checks out a
 * GitHub repository, applies a supported source-controlled change, commits it,
 * and pushes it back. The first supported operation updates a Kubernetes-style
 * container image field in a YAML manifest.
 *
 * CURRENT OPERATIONS:
 * - kubernetesContainerImage: update the image of a named container
 *
 * SECURITY:
 * - Requires an HTTPS Git URL so credentials can be injected only for push
 * - Restores the clean remote URL when the shell exits
 * - Validates repository-relative paths and image references
 * - Serializes updates to the same repository and branch with Jenkins locks
 * - Retries rejected pushes after fetch/rebase when the remote branch moved
 *
 * @param config Map containing:
 *   - repoUrl: REQUIRED - HTTPS Git repository URL
 *   - branch: Repository branch to update (default: 'main')
 *   - checkoutDir: Workspace checkout directory (default: 'github-update')
 *   - credentialsId: Jenkins username/password credential id (default: 'github-token')
 *   - file: REQUIRED - Repository-relative file to update
 *   - operation: Update operation (default: 'kubernetesContainerImage')
 *   - containerName: REQUIRED for kubernetesContainerImage
 *   - image: REQUIRED for kubernetesContainerImage
 *   - commitMessage: Optional commit message
 *   - gitUserName: Commit user name (default: 'Jenkins CI')
 *   - gitUserEmail: Commit user email (default: 'jenkins@ci.local')
 *   - lockResource: Lockable Resources name used to serialize repo/branch updates
 *   - maxPushRetries: Number of push attempts after fetch/rebase (default: 3)
 *
 * @example
 * updateGithub(
 *     repoUrl: 'https://github.com/KeremAR/Homelab-Infrastructure.git',
 *     branch: 'main',
 *     file: '3-Kubectl-Deploy/staging/user-service/templates/deployment.yaml',
 *     operation: 'kubernetesContainerImage',
 *     containerName: 'user-service',
 *     image: 'ghcr.io/keremar/todo-app-user-service:abc1234-v1.0-staging'
 * )
 */
def call(Map config = [:]) {
    String repoUrl = httpsGitUrl(required(config, 'repoUrl').toString(), 'repoUrl')
    String repoAuthPath = httpsUrlWithoutScheme(repoUrl)
    String branch = branchName((config.branch ?: 'main').toString(), 'branch')
    String checkoutDir = Validation.relativePath((config.checkoutDir ?: 'github-update').toString(), 'checkoutDir')
    String credentialsId = (config.credentialsId ?: 'github-token').toString()
    String file = Validation.relativePath(required(config, 'file').toString(), 'file')
    String operation = (config.operation ?: 'kubernetesContainerImage').toString()
    String gitUserName = (config.gitUserName ?: 'Jenkins CI').toString()
    String gitUserEmail = emailAddress((config.gitUserEmail ?: 'jenkins@ci.local').toString(), 'gitUserEmail')

    if (operation != 'kubernetesContainerImage') {
        error "Unsupported updateGithub operation: ${operation}"
    }

    String containerName = resourceName(required(config, 'containerName').toString(), 'containerName')
    String image = imageReference(required(config, 'image').toString(), 'image')
    String commitMessage = (config.commitMessage ?: "ci: update ${containerName} image to ${image}").toString()
    String lockResource = lockResourceName(
        (config.lockResource ?: "github-update-${lockKey(repoAuthPath)}-${lockKey(branch)}").toString()
    )
    int maxPushRetries = positiveInt(config.maxPushRetries ?: 3, 'maxPushRetries')

    echo "Waiting for GitHub update lock: ${lockResource}"
    lock(resource: lockResource) {
        dir(checkoutDir) {
            deleteDir()
            checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${branch}"]],
                userRemoteConfigs: [[
                    url: repoUrl,
                    credentialsId: credentialsId
                ]]
            ])
        }

        String workspaceFile = "${checkoutDir}/${file}"
        if (!fileExists(workspaceFile)) {
            error "GitHub update target file does not exist: ${workspaceFile}"
        }

        withCredentials([
            usernamePassword(credentialsId: credentialsId, usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD')
        ]) {
            withEnv([
                "GITHUB_UPDATE_REPO_DIR=${checkoutDir}",
                "GITHUB_UPDATE_BRANCH=${branch}",
                "GITHUB_UPDATE_REPO_URL=${repoUrl}",
                "GITHUB_UPDATE_REPO_AUTH_PATH=${repoAuthPath}",
                "GITHUB_UPDATE_FILE=${file}",
                "GITHUB_UPDATE_CONTAINER=${containerName}",
                "GITHUB_UPDATE_IMAGE=${image}",
                "GITHUB_UPDATE_COMMIT_MESSAGE=${commitMessage}",
                "GITHUB_UPDATE_USER_NAME=${gitUserName}",
                "GITHUB_UPDATE_USER_EMAIL=${gitUserEmail}",
                "GITHUB_UPDATE_MAX_PUSH_RETRIES=${maxPushRetries.toString()}"
            ]) {
                sh(
                    label: "Update GitHub file",
                    script: '''
                        set -eu

                        cd "$WORKSPACE/$GITHUB_UPDATE_REPO_DIR"
                        trap 'git remote set-url origin "$GITHUB_UPDATE_REPO_URL" 2>/dev/null || true' EXIT

                        awk -v container="$GITHUB_UPDATE_CONTAINER" -v image="$GITHUB_UPDATE_IMAGE" '
                            /^[[:space:]]*-[[:space:]]*name:[[:space:]]*/ {
                                name = $0
                                sub(/^[[:space:]]*-[[:space:]]*name:[[:space:]]*/, "", name)
                                gsub(/["'\\''"]/, "", name)
                                in_target = (name == container)
                            }

                            in_target && /^[[:space:]]*image:[[:space:]]*/ {
                                indent = $0
                                sub(/image:.*/, "", indent)
                                print indent "image: " image
                                patched = 1
                                in_target = 0
                                next
                            }

                            { print }

                            END {
                                if (!patched) {
                                    exit 42
                                }
                            }
                        ' "$GITHUB_UPDATE_FILE" > "$GITHUB_UPDATE_FILE.tmp" || {
                            echo "Could not patch image for container $GITHUB_UPDATE_CONTAINER in $GITHUB_UPDATE_FILE" >&2
                            exit 1
                        }

                        mv "$GITHUB_UPDATE_FILE.tmp" "$GITHUB_UPDATE_FILE"

                        if git diff --quiet -- "$GITHUB_UPDATE_FILE"; then
                            echo "GitHub file already has the requested value; nothing to commit."
                            exit 0
                        fi

                        echo "GitHub file change:"
                        git --no-pager diff -- "$GITHUB_UPDATE_FILE"

                        git config user.name "$GITHUB_UPDATE_USER_NAME"
                        git config user.email "$GITHUB_UPDATE_USER_EMAIL"
                        git add "$GITHUB_UPDATE_FILE"
                        git commit -m "$GITHUB_UPDATE_COMMIT_MESSAGE"

                        git remote set-url origin "https://${GIT_USERNAME}:${GIT_PASSWORD}@${GITHUB_UPDATE_REPO_AUTH_PATH}"

                        attempt=1
                        while [ "$attempt" -le "$GITHUB_UPDATE_MAX_PUSH_RETRIES" ]; do
                            if git push origin "HEAD:${GITHUB_UPDATE_BRANCH}"; then
                                echo "GitHub push succeeded on attempt $attempt."
                                exit 0
                            fi

                            if [ "$attempt" -eq "$GITHUB_UPDATE_MAX_PUSH_RETRIES" ]; then
                                echo "GitHub push failed after $attempt attempts." >&2
                                exit 1
                            fi

                            echo "GitHub push was rejected; fetching and rebasing before retry $((attempt + 1))."
                            git fetch origin "$GITHUB_UPDATE_BRANCH"
                            git rebase FETCH_HEAD || {
                                git rebase --abort 2>/dev/null || true
                                echo "Could not rebase GitHub update on origin/$GITHUB_UPDATE_BRANCH." >&2
                                exit 1
                            }

                            attempt=$((attempt + 1))
                        done
                    '''
                )
            }
        }
    }

    echo "Updated ${file} in ${repoUrl}@${branch}"
}

private Object required(Map config, String key) {
    if (!config.containsKey(key) || config[key] == null || config[key].toString().trim() == '') {
        error "updateGithub requires '${key}'"
    }

    return config[key]
}

private int positiveInt(Object value, String label) {
    String normalized = value.toString().trim()
    if (!(normalized ==~ /^[1-9][0-9]*$/)) {
        error "${label} must be a positive integer: ${value}"
    }

    return normalized.toInteger()
}

private String lockResourceName(String value) {
    String normalized = value.trim()
    if (!normalized || normalized.startsWith('-') || normalized.contains('..')) {
        error "Invalid lockResource: ${value}"
    }

    if (!(normalized ==~ /^[A-Za-z0-9_.:-]+$/)) {
        error "Invalid characters in lockResource: ${value}"
    }

    return normalized
}

private String lockKey(String value) {
    String normalized = value.trim().replaceAll(/[^A-Za-z0-9_.-]+/, '-')
    normalized = normalized.replaceAll(/^-+/, '').replaceAll(/-+$/, '')

    if (!normalized) {
        return 'default'
    }

    return normalized.take(120)
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

private String resourceName(String value, String label) {
    String normalized = value.trim()
    if (!(normalized ==~ /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/)) {
        error "Invalid Kubernetes ${label}: ${value}"
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

private String httpsGitUrl(String value, String label) {
    String normalized = value.trim()
    if (!normalized.startsWith('https://')) {
        error "${label} must be an HTTPS Git URL so Jenkins can push with credentials: ${value}"
    }

    if (normalized.contains('..') || normalized.startsWith('-')) {
        error "Invalid ${label}: ${value}"
    }

    return normalized
}

private String httpsUrlWithoutScheme(String url) {
    return url.substring('https://'.length())
}

private String emailAddress(String value, String label) {
    String normalized = value.trim()
    if (!(normalized ==~ /^[^@\s]+@[^@\s]+\.[^@\s]+$/)) {
        error "Invalid ${label}: ${value}"
    }

    return normalized
}
