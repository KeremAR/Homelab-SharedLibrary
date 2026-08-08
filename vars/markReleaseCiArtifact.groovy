#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Write and archive a release CI success marker.
 *
 * Release deployment jobs copy artifacts from the latest completed CI build.
 * This marker proves that the copied build reached the end of the release CI
 * pipeline successfully, so release jobs do not silently fall back to an older
 * successful build when the newest CI run failed.
 *
 * MARKER BEHAVIOR:
 * - Runs only on release/* branches by default
 * - Requires image-artifacts/images.txt to exist before writing the marker
 * - Writes branch, short commit, and Jenkins build number
 * - Archives the marker as a Jenkins artifact
 *
 * @param config Map containing:
 *   - outputDir: Directory containing image artifacts (default: 'image-artifacts')
 *   - imageManifest: Manifest path to require before marking success (default: '<outputDir>/images.txt')
 *   - markerFile: Marker file path (default: '<outputDir>/ci-success.txt')
 *   - branchName: Branch name (default: env.BRANCH_NAME)
 *   - releasePrefix: Release branch prefix (default: 'release/')
 *   - onlyReleaseBranches: Skip non-release branches when true (default: true)
 *
 * @example
 * markReleaseCiArtifact(
 *     outputDir: 'image-artifacts'
 * )
 */
def call(Map config = [:]) {
    String outputDir = Validation.relativePath((config.outputDir ?: 'image-artifacts').toString(), 'Release artifact output directory')
    String imageManifest = Validation.relativePath((config.imageManifest ?: "${outputDir}/images.txt").toString(), 'Image artifact manifest')
    String markerFile = Validation.relativePath((config.markerFile ?: "${outputDir}/ci-success.txt").toString(), 'Release CI success marker')
    String branchName = (config.branchName ?: env.BRANCH_NAME ?: '').toString()
    String releasePrefix = (config.releasePrefix ?: 'release/').toString()
    boolean onlyReleaseBranches = config.get('onlyReleaseBranches', true)

    if (onlyReleaseBranches && !branchName.startsWith(releasePrefix)) {
        echo "Skipping release CI marker for non-release branch: ${branchName ?: 'unknown'}"
        return
    }

    if (!fileExists(imageManifest)) {
        error "Cannot mark release CI artifact because image manifest does not exist: ${imageManifest}"
    }

    String commit = sh(
        label: 'Resolve release artifact commit',
        returnStdout: true,
        script: 'git rev-parse --short=7 HEAD'
    ).trim()

    writeFile(
        file: markerFile,
        text: [
            "branch=${branchName}",
            "commit=${commit}",
            "build=${env.BUILD_NUMBER ?: ''}"
        ].join('\n') + '\n'
    )

    archiveArtifacts(
        allowEmptyArchive: false,
        artifacts: markerFile,
        fingerprint: true
    )
}
