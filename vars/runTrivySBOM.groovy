#!/usr/bin/env groovy

import com.company.jenkins.ImageArtifactManifest
import com.company.jenkins.TrivyValidation
import com.company.jenkins.Validation

/**
 * Generate SBOM files from Docker image archive artifacts with Trivy.
 *
 * This helper runs after image build and usually after image vulnerability
 * scanning. It reads the runBuildImages images.txt manifest, generates one SBOM
 * per Docker archive, archives those SBOMs in Jenkins, and can optionally upload
 * them to Dependency-Track.
 *
 * @param config Map containing:
 *   - imageManifest: images.txt path from runBuildImages (default: 'image-artifacts/images.txt')
 *   - imageArchives: Optional explicit list of Docker archive paths
 *   - imageRefs: Optional image references matching imageArchives
 *   - format: SBOM format: cyclonedx, spdx, spdx-json, json (default: 'cyclonedx')
 *   - outputDir: Directory for SBOM files (default: 'sbom-reports')
 *   - timeout: Trivy timeout (default: '15m')
 *   - skipDirs: Image-internal directories to skip
 *   - cacheDir: Persistent Trivy cache path (default: '/home/jenkins/.cache/trivy')
 *   - container: Jenkins Kubernetes container name (default: 'trivy')
 *   - failFast: Whether sibling SBOM jobs stop after the first failure (default: true)
 *   - uploadToDependencyTrack: Upload generated SBOMs (default: false)
 *   - dependencyTrackUrl: Dependency-Track API base URL
 *   - dependencyTrackCredentialsId: Jenkins Secret Text credential id
 *   - dependencyTrackProjectName: Optional fixed project name
 *   - dependencyTrackProjectVersion: Optional fixed project version
 *   - dependencyTrackAutoCreate: Auto-create projects on upload (default: true)
 */
def call(Map config = [:]) {
    String outputDir = Validation.relativePath((config.outputDir ?: 'sbom-reports').toString(), 'SBOM output directory')
    String format = sbomFormat((config.format ?: 'cyclonedx').toString())
    String timeout = TrivyValidation.timeout((config.timeout ?: '15m').toString(), 'Trivy SBOM timeout')
    List skipDirs = TrivyValidation.skipPaths(config.skipDirs ?: [], 'Trivy SBOM skip directory')
    String cacheDir = TrivyValidation.cachePath((config.cacheDir ?: '/home/jenkins/.cache/trivy').toString(), 'Trivy cache directory')
    String containerName = config.container ?: 'trivy'
    boolean failFast = config.get('failFast', true)
    boolean uploadEnabled = config.get('uploadToDependencyTrack', false)
    String skipDirFlags = skipDirs.collect { dir -> "--skip-dirs ${Validation.shellQuote(dir)}" }.join(' ')

    List images = resolveImages(config).collect { image ->
        image + [
            sbomFile: "${outputDir}/${safeFileBase(image.imageRef)}.${sbomExtension(format)}"
        ]
    }
    validateUniqueSboms(images)

    Map branches = [:]
    images.each { image ->
        branches["Generate SBOM: ${image.name}"] = {
            container(containerName) {
                if (!fileExists(image.archive)) {
                    error "Docker image archive does not exist for ${image.name}: ${image.archive}"
                }

                String isolatedCacheDir = "/tmp/trivy-sbom-${UUID.randomUUID().toString()}"

                withEnv([
                    "TRIVY_SOURCE_CACHE=${cacheDir}",
                    "TRIVY_ISOLATED_CACHE=${isolatedCacheDir}",
                    "TRIVY_IMAGE_ARCHIVE=${image.archive}",
                    "TRIVY_SBOM_FILE=${image.sbomFile}"
                ]) {
                    try {
                        int status = sh(
                            label: "Generate SBOM: ${image.name}",
                            returnStatus: true,
                            script: """
                                set -eu
                                mkdir -p "\$TRIVY_ISOLATED_CACHE" "\$WORKSPACE/${outputDir}"
                                if [ -d "\$TRIVY_SOURCE_CACHE" ]; then
                                    for ITEM in "\$TRIVY_SOURCE_CACHE"/* "\$TRIVY_SOURCE_CACHE"/.[!.]* "\$TRIVY_SOURCE_CACHE"/..?*; do
                                        [ -e "\$ITEM" ] || continue
                                        [ "\$(basename "\$ITEM")" = "lost+found" ] && continue
                                        cp -R "\$ITEM" "\$TRIVY_ISOLATED_CACHE"/
                                    done
                                fi

                                cd "\$WORKSPACE"
                                trivy image \\
                                    --input "\$TRIVY_IMAGE_ARCHIVE" \\
                                    --skip-db-update \\
                                    --cache-dir "\$TRIVY_ISOLATED_CACHE" \\
                                    ${skipDirFlags} \\
                                    --timeout ${Validation.shellQuote(timeout)} \\
                                    --format ${Validation.shellQuote(format)} \\
                                    --output "\$TRIVY_SBOM_FILE"
                            """
                        )

                        if (status != 0) {
                            error "Trivy SBOM generation failed for ${image.name}"
                        }

                        if (!fileExists(image.sbomFile)) {
                            error "SBOM file was not generated for ${image.name}: ${image.sbomFile}"
                        }
                    } finally {
                        int cleanupStatus = sh(
                            label: "Clean Trivy SBOM cache: ${image.name}",
                            returnStatus: true,
                            script: 'rm -rf "$TRIVY_ISOLATED_CACHE"'
                        )

                        if (cleanupStatus != 0) {
                            echo "WARNING: Trivy SBOM temporary cache cleanup failed for ${image.name}."
                        }
                    }
                }
            }
        }
    }

    parallel branches + [failFast: failFast]

    writeSbomManifest(images, outputDir)
    archiveArtifacts(
        allowEmptyArchive: false,
        artifacts: "${outputDir}/*,${outputDir}/sboms.txt",
        fingerprint: true
    )

    if (uploadEnabled) {
        uploadSBOMsToDependencyTrack(
            sboms: images.collect { image ->
                [
                    file: image.sbomFile,
                    projectName: config.dependencyTrackProjectName ?: image.name,
                    projectVersion: config.dependencyTrackProjectVersion ?: ImageArtifactManifest.tagFromImageRef(image.imageRef)
                ]
            },
            dependencyTrackUrl: config.dependencyTrackUrl,
            credentialsId: config.dependencyTrackCredentialsId ?: 'dependency-track-api-key',
            autoCreate: config.get('dependencyTrackAutoCreate', true),
            failOnUploadError: config.get('dependencyTrackFailOnUploadError', true),
            container: config.dependencyTrackUploadContainer ?: 'python'
        )
    }

    return images
}

private List resolveImages(Map config) {
    if (config.imageArchives) {
        List archives = config.imageArchives.collect { value ->
            Validation.relativePath(value.toString(), 'Docker image archive')
        }
        List refs = (config.imageRefs ?: archives).collect { value ->
            imageReference(value.toString(), 'Docker image reference')
        }

        if (refs.size() != archives.size()) {
            throw new IllegalArgumentException("imageRefs size must match imageArchives size")
        }

        return archives.withIndex().collect { archive, index ->
            [
                name: imageNameFromArchive(archive),
                imageRef: refs[index],
                archive: archive,
                platform: ''
            ]
        }
    }

    String manifest = Validation.relativePath((config.imageManifest ?: 'image-artifacts/images.txt').toString(), 'Image artifact manifest')
    if (!fileExists(manifest)) {
        error "Image artifact manifest does not exist: ${manifest}"
    }

    return ImageArtifactManifest.parse(readFile(manifest), manifest)
}

private void writeSbomManifest(List images, String outputDir) {
    String content = images.collect { image ->
        "${image.name}\t${image.imageRef}\t${image.sbomFile}"
    }.join('\n') + '\n'

    writeFile file: "${outputDir}/sboms.txt", text: content
}

private void validateUniqueSboms(List images) {
    List sbomFiles = images.collect { image -> image.sbomFile }
    if (sbomFiles.size() != sbomFiles.unique().size()) {
        throw new IllegalArgumentException("Duplicate SBOM output files are not allowed: ${sbomFiles}")
    }
}

private String sbomFormat(String value) {
    List allowed = ['cyclonedx', 'spdx', 'spdx-json', 'json']
    if (!allowed.contains(value)) {
        throw new IllegalArgumentException("Invalid SBOM format: ${value}. Allowed values: ${allowed}")
    }
    return value
}

private String sbomExtension(String format) {
    Map extensions = [
        'cyclonedx': 'cyclonedx.json',
        'spdx': 'spdx',
        'spdx-json': 'spdx.json',
        'json': 'json'
    ]

    return extensions[format]
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

private String imageNameFromArchive(String archive) {
    return archive.tokenize('/').last().replaceFirst(/\.docker\.tar$/, '')
}

private String safeFileBase(String value) {
    return value.replace(':', '_').replaceAll(/[^A-Za-z0-9_.-]/, '-')
}
