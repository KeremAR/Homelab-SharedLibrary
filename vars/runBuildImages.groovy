#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Build one or more container images with BuildKit and export OCI archives.
 *
 * This library builds Dockerfiles without Docker-in-Docker and without mounting
 * /var/run/docker.sock. It expects a Jenkins Kubernetes agent container named
 * `buildkit` by default, usually based on `moby/buildkit:rootless`.
 *
 * BUILD BEHAVIOR:
 * - Builds every configured image in parallel
 * - Uses BuildKit daemonless mode when available
 * - Exports OCI tar archives into the workspace
 * - Optionally archives the OCI tar files as Jenkins artifacts
 * - Does not push images to a registry
 *
 * SECURITY:
 * - No Docker daemon is required
 * - No Docker socket is mounted
 * - Repository paths are validated before use
 * - Build args are validated and shell-quoted
 *
 * @param config Map containing:
 *   - images: REQUIRED - List of image maps
 *   - outputDir: Directory for OCI archives (default: 'image-artifacts')
 *   - platform: Default platform (default: 'linux/amd64')
 *   - container: Jenkins Kubernetes container name (default: 'buildkit')
 *   - archiveArtifacts: Archive OCI tar files in Jenkins (default: true)
 *   - failFast: Whether parallel image builds should fail fast (default: true)
 *
 * Image map keys:
 *   - name: REQUIRED - Logical image name, also used for output filename
 *   - context: Build context directory (default: '.')
 *   - dockerfile: Dockerfile path (default: '<context>/Dockerfile')
 *   - image: Image reference written to images.txt (default: '<name>:<tag>')
 *   - tag: Tag used when image is omitted (default: config.tag or 'local')
 *   - platform: Image-specific platform override
 *   - target: Optional Dockerfile target stage
 *   - buildArgs: Optional map of Docker build args
 *
 * @example
 * runBuildImages(
 *     images: [
 *         [
 *             name: 'user-service',
 *             context: '.',
 *             dockerfile: 'user-service/Dockerfile',
 *             image: 'user-service:abc1234-v1.0-candidate'
 *         ],
 *         [
 *             name: 'frontend',
 *             context: 'frontend',
 *             dockerfile: 'frontend/Dockerfile',
 *             tag: 'abc1234-v1.0-candidate'
 *         ]
 *     ],
 *     failFast: false
 * )
 */
def call(Map config = [:]) {
    List rawImages = config.images ?: []
    if (rawImages.isEmpty()) {
        error 'runBuildImages requires images, for example: [images: [[name: "user-service"]]]'
    }

    String containerName = config.container ?: 'buildkit'
    String outputDir = Validation.relativePath((config.outputDir ?: 'image-artifacts').toString(), 'Image artifact output directory')
    String defaultPlatform = platform((config.platform ?: 'linux/amd64').toString(), 'Default image platform')
    String defaultTag = dockerTag((config.tag ?: 'local').toString(), 'Default image tag')
    boolean archiveEnabled = config.get('archiveArtifacts', true)
    boolean failFast = config.get('failFast', true)

    List images = normalizeImages(rawImages, outputDir, defaultPlatform, defaultTag)
    validateUniqueOutputFiles(images)

    Map branches = [:]
    images.each { image ->
        branches["Build image: ${image.name}"] = {
            container(containerName) {
                validateBuildFiles(image)

                String buildArgFlags = buildArgsToFlags(image.buildArgs)
                String targetFlag = image.target ? "--opt target=${Validation.shellQuote(image.target)}" : ''
                String outputSpec = "type=oci,dest=\$WORKSPACE/${image.outputFile}"

                withEnv([
                    "BUILD_CONTEXT=${image.context}",
                    "BUILD_DOCKERFILE=${image.dockerfile}",
                    "BUILD_PLATFORM=${image.platform}",
                    "BUILD_OUTPUT_FILE=${image.outputFile}",
                    "BUILD_OUTPUT_SPEC=${outputSpec}"
                ]) {
                    sh(
                        label: "Build OCI image: ${image.name}",
                        script: """
                            set -eu

                            mkdir -p "\$WORKSPACE/${outputDir}"

                            if command -v buildctl-daemonless.sh >/dev/null 2>&1; then
                                BUILDKIT_CMD=buildctl-daemonless.sh
                            elif command -v buildctl >/dev/null 2>&1; then
                                BUILDKIT_CMD=buildctl
                            else
                                echo "buildctl or buildctl-daemonless.sh is required in the ${containerName} container" >&2
                                exit 1
                            fi

                            "\$BUILDKIT_CMD" build \\
                                --frontend dockerfile.v0 \\
                                --local context="\$WORKSPACE/\$BUILD_CONTEXT" \\
                                --local dockerfile="\$WORKSPACE" \\
                                --opt filename="\$BUILD_DOCKERFILE" \\
                                --opt platform="\$BUILD_PLATFORM" \\
                                ${targetFlag} \\
                                ${buildArgFlags} \\
                                --output "\$BUILD_OUTPUT_SPEC" \\
                                --progress=plain

                            test -s "\$WORKSPACE/\$BUILD_OUTPUT_FILE"
                        """
                    )
                }
            }
        }
    }

    parallel branches + [failFast: failFast]

    writeImageManifest(images, outputDir)

    env.BUILT_IMAGE_ARCHIVES = images.collect { it.outputFile }.join(',')
    env.BUILT_IMAGE_REFS = images.collect { it.imageRef }.join(',')

    if (archiveEnabled) {
        archiveArtifacts(
            allowEmptyArchive: false,
            artifacts: "${outputDir}/*.oci.tar,${outputDir}/images.txt",
            fingerprint: true
        )
    }

    return images
}

private List normalizeImages(
    List rawImages,
    String outputDir,
    String defaultPlatform,
    String defaultTag
) {
    return rawImages.collect { rawImage ->
        if (!(rawImage instanceof Map)) {
            throw new IllegalArgumentException("Image config must be a Map: ${rawImage}")
        }

        Map image = rawImage + [:]

        String name = imageName(image.name?.toString(), 'Image name')
        String context = Validation.relativePath((image.context ?: '.').toString(), "Build context for ${name}")
        String dockerfile = Validation.relativePath((image.dockerfile ?: defaultDockerfile(context)).toString(), "Dockerfile for ${name}")
        String tag = dockerTag((image.tag ?: defaultTag).toString(), "Image tag for ${name}")
        String imageRef = image.image ? imageReference(image.image.toString(), "Image reference for ${name}") : imageReference("${name}:${tag}", "Image reference for ${name}")
        String resolvedPlatform = platform((image.platform ?: defaultPlatform).toString(), "Platform for ${name}")
        String outputName = "${sanitizeForFilename(name)}.oci.tar"
        String outputFile = "${outputDir}/${outputName}"
        String target = image.target ? Validation.relativePath(image.target.toString(), "Dockerfile target for ${name}") : ''
        Map buildArgs = normalizeBuildArgs(image.buildArgs ?: [:], name)

        return [
            name: name,
            context: context,
            dockerfile: dockerfile,
            imageRef: imageRef,
            platform: resolvedPlatform,
            outputFile: outputFile,
            target: target,
            buildArgs: buildArgs
        ]
    }
}

private String defaultDockerfile(String context) {
    return context == '.' ? 'Dockerfile' : "${context}/Dockerfile"
}

private void validateBuildFiles(Map image) {
    if (!fileExists(image.context)) {
        error "Build context does not exist for ${image.name}: ${image.context}"
    }

    if (!fileExists(image.dockerfile)) {
        error "Dockerfile does not exist for ${image.name}: ${image.dockerfile}"
    }
}

private void validateUniqueOutputFiles(List images) {
    List outputFiles = images.collect { it.outputFile }
    if (outputFiles.size() != outputFiles.unique().size()) {
        throw new IllegalArgumentException("Duplicate image artifact output files are not allowed: ${outputFiles}")
    }
}

private Map normalizeBuildArgs(Map buildArgs, String imageName) {
    Map normalized = [:]
    buildArgs.each { key, value ->
        String safeKey = buildArgName(key.toString(), "Build arg name for ${imageName}")
        normalized[safeKey] = value == null ? '' : value.toString()
    }
    return normalized
}

private String buildArgsToFlags(Map buildArgs) {
    return buildArgs.collect { key, value ->
        "--opt build-arg:${key}=${Validation.shellQuote(value)}"
    }.join(' ')
}

private void writeImageManifest(List images, String outputDir) {
    String content = images.collect { image ->
        "${image.name}\t${image.imageRef}\t${image.outputFile}\t${image.platform}"
    }.join('\n') + '\n'

    writeFile file: "${outputDir}/images.txt", text: content
}

private String imageName(String value, String label) {
    if (!value) {
        throw new IllegalArgumentException("${label} cannot be empty")
    }

    if (value.startsWith('-')) {
        throw new IllegalArgumentException("${label} cannot start with '-': ${value}")
    }

    if (!(value ==~ /^[A-Za-z0-9][A-Za-z0-9_.\/-]*$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}

private String imageReference(String value, String label) {
    if (!value) {
        throw new IllegalArgumentException("${label} cannot be empty")
    }

    if (value.startsWith('-') || value.contains('..')) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    if (!(value ==~ /^[A-Za-z0-9][A-Za-z0-9._:\/@-]*$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}

private String dockerTag(String value, String label) {
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

private String platform(String value, String label) {
    if (!value) {
        throw new IllegalArgumentException("${label} cannot be empty")
    }

    if (!(value ==~ /^[A-Za-z0-9_]+\/[A-Za-z0-9_.-]+(\/[A-Za-z0-9_.-]+)?$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}

private String buildArgName(String value, String label) {
    if (!value) {
        throw new IllegalArgumentException("${label} cannot be empty")
    }

    if (!(value ==~ /^[A-Za-z_][A-Za-z0-9_]*$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}

private String sanitizeForFilename(String value) {
    return value.replaceAll(/[^A-Za-z0-9_.-]/, '-')
}
