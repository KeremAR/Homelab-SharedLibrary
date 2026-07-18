#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Build one or more container images with Docker-in-Docker and export archives.
 *
 * This library builds Dockerfiles through a Docker CLI container connected to a
 * Docker-in-Docker sidecar over DOCKER_HOST=tcp://localhost:2375. It expects a
 * Jenkins Kubernetes agent container named `docker` by default.
 *
 * BUILD BEHAVIOR:
 * - Builds every configured image in parallel
 * - Uses docker build
 * - Exports Docker image tar archives into the workspace with docker save
 * - Optionally archives the Docker image tar files as Jenkins artifacts
 * - Does not push images to a registry
 *
 * SECURITY:
 * - Does not mount the host Docker socket
 * - Requires a privileged docker:dind sidecar in the Jenkins agent pod
 * - Repository paths are validated before use
 * - Build args are validated and shell-quoted
 *
 * @param config Map containing:
 *   - images: REQUIRED - List of image maps
 *   - outputDir: Directory for Docker archives (default: 'image-artifacts')
 *   - platform: Default platform (default: 'linux/amd64')
 *   - container: Jenkins Kubernetes container name (default: 'docker')
 *   - dockerHost: Docker daemon endpoint (default: 'tcp://localhost:2375')
 *   - archiveArtifacts: Archive Docker tar files in Jenkins (default: true)
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
 *             context: 'user-service',
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

    String containerName = config.container ?: 'docker'
    String dockerHost = config.dockerHost ?: 'tcp://localhost:2375'
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
                String targetFlag = image.target ? "--target ${Validation.shellQuote(image.target)}" : ''

                withEnv([
                    "DOCKER_HOST=${dockerHost}",
                    "DOCKER_TLS_CERTDIR=",
                    "BUILD_CONTEXT=${image.context}",
                    "BUILD_DOCKERFILE=${image.dockerfile}",
                    "BUILD_IMAGE_REF=${image.imageRef}",
                    "BUILD_PLATFORM=${image.platform}",
                    "BUILD_OUTPUT_FILE=${image.outputFile}"
                ]) {
                    sh(
                        label: "Build Docker image: ${image.name}",
                        script: """
                            set -eu

                            mkdir -p "\$WORKSPACE/${outputDir}"

                            for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
                                if docker info >/dev/null 2>&1; then
                                    break
                                fi

                                if [ "\$attempt" = "12" ]; then
                                    echo "Docker daemon is not ready at \$DOCKER_HOST" >&2
                                    docker version || true
                                    exit 1
                                fi

                                sleep 5
                            done

                            docker build \\
                                --pull=false \\
                                --platform "\$BUILD_PLATFORM" \\
                                -f "\$WORKSPACE/\$BUILD_DOCKERFILE" \\
                                -t "\$BUILD_IMAGE_REF" \\
                                ${targetFlag} \\
                                ${buildArgFlags} \\
                                "\$WORKSPACE/\$BUILD_CONTEXT"

                            docker save -o "\$WORKSPACE/\$BUILD_OUTPUT_FILE" "\$BUILD_IMAGE_REF"
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
            artifacts: "${outputDir}/*.docker.tar,${outputDir}/images.txt",
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
        String outputName = "${sanitizeForFilename(name)}.docker.tar"
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
        "--build-arg ${Validation.shellQuote("${key}=${value}")}"
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
