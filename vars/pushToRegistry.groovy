#!/usr/bin/env groovy

import com.company.jenkins.ImageArtifactManifest
import com.company.jenkins.Validation

/**
 * Load Docker image archive artifacts and push them to a registry.
 *
 * This helper is intended for manual release/deploy pipelines. The CI
 * multibranch job builds Docker archives with runBuildImages/runReleaseImages
 * and writes image-artifacts/images.txt. This step reads that manifest, loads
 * each archive into the pod-local Docker daemon, retags it for the configured
 * registry, and pushes the final image reference.
 *
 * PUSH BEHAVIOR:
 * - Reads image-artifacts/images.txt by default
 * - Loads Docker archives with docker load
 * - Keeps source image refs local unless they already match the target
 * - Rewrites the tag environment suffix when config.environment is set
 * - Applies environment suffixes only for release/* branches when config.branchName is set
 * - Archives pushed-images.txt for traceability
 *
 * SECURITY:
 * - Uses Jenkins credentials binding for docker login
 * - Does not allow dockerHost override
 * - Validates registry, repository, image, and artifact paths before shell use
 *
 * @param config Map containing:
 *   - imageManifest: images.txt path from runBuildImages (default: 'image-artifacts/images.txt')
 *   - registry: Registry host (default: 'ghcr.io')
 *   - registryNamespace: Optional registry namespace/owner, for example 'keremar'
 *   - repositories: Optional map from logical image name to registry repository name/path
 *   - repositoryPrefix: Optional prefix when repositories does not contain the image name
 *   - environment: Optional tag suffix rewrite, for example 'staging' or 'prod'
 *   - branchName: Optional source branch. When set, environment is used only for release/* branches
 *   - credentialsId: Jenkins username/password credentials id (default: 'github-token')
 *   - container: Jenkins Kubernetes container name (default: 'docker')
 *
 * @example
 * pushToRegistry(
 *     imageManifest: 'image-artifacts/images.txt',
 *     registry: 'ghcr.io',
 *     registryNamespace: 'keremar',
 *     repositories: [
 *         'user-service': 'todo-app-user-service'
 *     ],
 *     environment: 'staging',
 *     credentialsId: 'github-token'
 * )
 */
def call(Map config = [:]) {
    if (config.containsKey('dockerHost')) {
        error 'pushToRegistry does not allow dockerHost override; the pod-local Docker daemon is always used'
    }

    String manifest = Validation.relativePath((config.imageManifest ?: 'image-artifacts/images.txt').toString(), 'Image artifact manifest')
    if (!fileExists(manifest)) {
        error "Image artifact manifest does not exist: ${manifest}"
    }

    String registry = registryHost((config.registry ?: 'ghcr.io').toString(), 'Registry host')
    String pushedManifest = pushedManifestPath(manifest)
    String namespace = config.registryNamespace ? repositoryPath(config.registryNamespace.toString(), 'Registry namespace') : ''
    String repositoryPrefix = config.repositoryPrefix ? repositoryName(config.repositoryPrefix.toString(), 'Repository prefix') : ''
    Map repositories = normalizeRepositories(config.repositories ?: [:])
    String branchName = config.branchName ? normalizeBranchName(config.branchName.toString()) : ''
    String environmentName = resolveEnvironmentName(config.environment, branchName)
    String credentialsId = (config.credentialsId ?: 'github-token').toString()
    String containerName = config.container ?: 'docker'
    String dockerHost = 'tcp://localhost:2375'

    List images = ImageArtifactManifest.parse(readFile(manifest), manifest).collect { image ->
        String sourceTag = ImageArtifactManifest.tagFromImageRef(image.imageRef)
        String targetTag = environmentName ? rewriteEnvironmentTag(sourceTag, environmentName) : sourceTag
        String repository = repositories[image.name] ?: "${repositoryPrefix}${image.name}"
        String repositoryWithNamespace = namespace ? "${namespace}/${repository}" : repository
        String targetRef = imageReference("${registry}/${repositoryWithNamespace}:${targetTag}", "Target image reference for ${image.name}")

        return image + [
            sourceTag: sourceTag,
            targetTag: targetTag,
            targetRef: targetRef
        ]
    }

    validateUniqueTargets(images)

    container(containerName) {
        withCredentials([
            usernamePassword(
                credentialsId: credentialsId,
                usernameVariable: 'REGISTRY_USERNAME',
                passwordVariable: 'REGISTRY_PASSWORD'
            )
        ]) {
            withEnv([
                "DOCKER_HOST=${dockerHost}",
                "DOCKER_TLS_CERTDIR=",
                "REGISTRY_HOST=${registry}"
            ]) {
                sh(
                    label: "Docker login: ${registry}",
                    script: '''
                        set -eu

                        export HOME="$WORKSPACE"
                        export DOCKER_CONFIG="$WORKSPACE/.docker"
                        mkdir -p "$DOCKER_CONFIG"

                        for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
                            if docker info >/dev/null 2>&1; then
                                break
                            fi

                            if [ "$attempt" = "12" ]; then
                                echo "Docker daemon is not ready at $DOCKER_HOST" >&2
                                docker version || true
                                exit 1
                            fi

                            sleep 5
                        done

                        echo "$REGISTRY_PASSWORD" | docker login "$REGISTRY_HOST" \
                            -u "$REGISTRY_USERNAME" \
                            --password-stdin
                    '''
                )

                images.each { image ->
                    if (!fileExists(image.archive)) {
                        error "Docker image archive does not exist for ${image.name}: ${image.archive}"
                    }

                    withEnv([
                        "SOURCE_IMAGE_REF=${image.imageRef}",
                        "TARGET_IMAGE_REF=${image.targetRef}",
                        "IMAGE_ARCHIVE=${image.archive}"
                    ]) {
                        sh(
                            label: "Push image: ${image.name}",
                            script: '''
                                set -eu

                                export HOME="$WORKSPACE"
                                export DOCKER_CONFIG="$WORKSPACE/.docker"

                                docker load -i "$WORKSPACE/$IMAGE_ARCHIVE"

                                if [ "$SOURCE_IMAGE_REF" != "$TARGET_IMAGE_REF" ]; then
                                    docker tag "$SOURCE_IMAGE_REF" "$TARGET_IMAGE_REF"
                                fi

                                docker push "$TARGET_IMAGE_REF"
                            '''
                        )
                    }
                }
            }
        }
    }

    writePushedManifest(images, pushedManifest)
    archiveArtifacts(
        allowEmptyArchive: false,
        artifacts: pushedManifest,
        fingerprint: true
    )

    env.PUSHED_IMAGE_REFS = images.collect { it.targetRef }.join(',')
    return images
}

private Map normalizeRepositories(Map repositories) {
    Map normalized = [:]
    repositories.each { key, value ->
        String name = imageName(key.toString(), 'Repository image name')
        normalized[name] = repositoryPath(value.toString(), "Repository path for ${name}")
    }
    return normalized
}

private void validateUniqueTargets(List images) {
    List targets = images.collect { it.targetRef }
    if (targets.size() != targets.unique().size()) {
        throw new IllegalArgumentException("Duplicate target image references are not allowed: ${targets}")
    }
}

private String pushedManifestPath(String imageManifest) {
    int slash = imageManifest.lastIndexOf('/')
    if (slash < 0) {
        return 'pushed-images.txt'
    }

    return "${imageManifest.substring(0, slash)}/pushed-images.txt"
}

private void writePushedManifest(List images, String pushedManifest) {
    String content = images.collect { image ->
        "${image.name}\t${image.imageRef}\t${image.targetRef}\t${image.archive}"
    }.join('\n') + '\n'

    writeFile file: pushedManifest, text: content
}

private String rewriteEnvironmentTag(String sourceTag, String environmentName) {
    String suffixPattern = /-(staging|prod|production|candidate)$/
    if (sourceTag ==~ /.*${suffixPattern}/) {
        return sourceTag.replaceFirst(suffixPattern, "-${environmentName}")
    }

    return "${sourceTag}-${environmentName}"
}

private String resolveEnvironmentName(Object environment, String branchName) {
    if (!environment) {
        return ''
    }

    String environmentName = tagSegment(environment.toString(), 'Registry tag environment')
    if (branchName && !isReleaseBranch(branchName)) {
        return ''
    }

    return environmentName
}

private String normalizeBranchName(String value) {
    String branch = (value ?: '').trim()
    branch = branch.replaceFirst(/^refs\/heads\//, '')
    branch = branch.replaceFirst(/^origin\//, '')

    if (!branch) {
        throw new IllegalArgumentException('Branch name cannot be empty')
    }

    if (branch.startsWith('-') || branch.contains('..')) {
        throw new IllegalArgumentException("Invalid branch name: ${value}")
    }

    return branch
}

private boolean isReleaseBranch(String branch) {
    return branch == 'release' || branch.startsWith('release/')
}

private String registryHost(String value, String label) {
    if (!value || value.startsWith('-') || value.contains('/') || value.contains('..')) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    if (!(value ==~ /^[A-Za-z0-9][A-Za-z0-9_.:-]*$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}

private String repositoryPath(String value, String label) {
    if (!value || value.startsWith('/') || value.startsWith('-') || value.contains('..')) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    if (!(value ==~ /^[A-Za-z0-9][A-Za-z0-9_.\/-]*$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}

private String repositoryName(String value, String label) {
    if (!value || value.startsWith('/') || value.startsWith('-') || value.contains('..')) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    if (!(value ==~ /^[A-Za-z0-9][A-Za-z0-9_.-]*$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}

private String imageReference(String value, String label) {
    if (!value || value.startsWith('-') || value.contains('..')) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    if (!(value ==~ /^[A-Za-z0-9][A-Za-z0-9._:\/@-]*$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}

private String imageName(String value, String label) {
    if (!value || value.startsWith('-') || value.contains('..')) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    if (!(value ==~ /^[A-Za-z0-9][A-Za-z0-9_.\/-]*$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}

private String tagSegment(String value, String label) {
    if (!value || value.startsWith('-')) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    if (!(value ==~ /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}
