#!/usr/bin/env groovy

import com.company.jenkins.ReleaseResolver

/**
 * Build the image that belongs to the current release branch.
 *
 * This wrapper applies the Homelab release branch convention and then delegates
 * the actual Docker build/archive work to runBuildImages.
 *
 * RELEASE BEHAVIOR:
 * - Expects release branches in the form release/<service>-v<version>
 * - For single-image repositories, also accepts release/v<version>
 * - Builds only the image named by the release branch
 * - Tags the image as <commit>-v<version>-<environment>
 *
 * @param config Map containing:
 *   - images: REQUIRED - List of image maps passed to runBuildImages
 *   - branchName: Branch name (default: env.BRANCH_NAME)
 *   - environment: Tag suffix environment (default: 'staging')
 *   - releasePrefix: Release branch prefix (default: 'release/')
 *   - outputDir: Passed to runBuildImages
 *   - platform: Passed to runBuildImages
 *   - container: Passed to runBuildImages
 *   - archiveArtifacts: Passed to runBuildImages
 *   - failFast: Passed to runBuildImages
 *
 * @example
 * runReleaseImages(
 *     images: imageBuildConfig.images,
 *     outputDir: 'image-artifacts',
 *     platform: 'linux/amd64',
 *     environment: 'staging',
 *     failFast: false
 * )
 */
def call(Map config = [:]) {
    List rawImages = config.images ?: []
    if (rawImages.isEmpty()) {
        error 'runReleaseImages requires images'
    }

    String branchName = (config.branchName ?: env.BRANCH_NAME ?: '').toString()
    String releasePrefix = (config.releasePrefix ?: 'release/').toString()
    String environmentName = tagSegment((config.environment ?: 'staging').toString(), 'release environment')

    Map releaseInfo = ReleaseResolver.resolve(
        images: rawImages,
        branchName: branchName,
        releasePrefix: releasePrefix,
        required: true
    )
    String shortCommit = resolveShortCommit(config)
    String imageTag = "${shortCommit}-${releaseInfo.version}-${environmentName}"

    List images = rawImages
        .findAll { image -> image.name?.toString() == releaseInfo.service }
        .collect { image -> image + [tag: imageTag] }

    echo "Release branch parsed: service=${releaseInfo.service}, version=${releaseInfo.version}, environment=${environmentName}"
    echo "Building release image: ${releaseInfo.service}:${imageTag}"

    return runBuildImages(
        images: images,
        outputDir: config.outputDir ?: 'image-artifacts',
        platform: config.platform ?: 'linux/amd64',
        container: config.container ?: 'docker',
        archiveArtifacts: config.get('archiveArtifacts', true),
        failFast: config.get('failFast', true)
    )
}

private String resolveShortCommit(Map config) {
    String commit = config.commit ? config.commit.toString() : sh(
        label: 'Resolve short commit',
        returnStdout: true,
        script: 'git rev-parse --short=7 HEAD'
    ).trim()

    return tagSegment(commit, 'commit')
}

private String tagSegment(String value, String label) {
    if (!value) {
        throw new IllegalArgumentException("${label} cannot be empty")
    }

    if (value.startsWith('-')) {
        throw new IllegalArgumentException("${label} cannot start with '-': ${value}")
    }

    if (!(value ==~ /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}
