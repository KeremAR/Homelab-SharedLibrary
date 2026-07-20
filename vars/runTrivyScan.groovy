#!/usr/bin/env groovy

import com.company.jenkins.ImageArtifactManifest
import com.company.jenkins.TrivyValidation
import com.company.jenkins.Validation

/**
 * Run Trivy vulnerability scans against Docker image archive artifacts.
 *
 * This helper is designed to run after runBuildImages/runReleaseImages. It reads
 * the generated images.txt manifest or explicit archive paths, then scans each
 * Docker archive with `trivy image --input`.
 *
 * SCAN BEHAVIOR:
 * - Does not require images to be pushed to a registry
 * - Does not require Docker socket access
 * - Uses the `trivy` Kubernetes container directly
 * - Uses an isolated copy of the persistent Trivy DB cache per image
 * - Archives one JSON report and one human-readable table summary per image
 *
 * @param config Map containing:
 *   - imageManifest: images.txt path from runBuildImages (default: 'image-artifacts/images.txt')
 *   - imageArchives: Optional explicit list of Docker archive paths
 *   - imageRefs: Optional image references matching imageArchives
 *   - outputDir: Directory for Trivy image scan reports (default: 'trivy-image-reports')
 *   - severities: CSV severity list (default: 'HIGH,CRITICAL')
 *   - failOnVulnerabilities: Fail build when findings exist (default: true)
 *   - timeout: Trivy timeout (default: '15m')
 *   - skipDirs: Image-internal directories to skip
 *   - cacheDir: Persistent Trivy cache path (default: '/home/jenkins/.cache/trivy')
 *   - container: Jenkins Kubernetes container name (default: 'trivy')
 *   - failFast: Whether sibling scans stop after the first failure (default: true)
 */
def call(Map config = [:]) {
    String outputDir = Validation.relativePath((config.outputDir ?: 'trivy-image-reports').toString(), 'Trivy image report directory')
    String severities = TrivyValidation.severities((config.severities ?: 'HIGH,CRITICAL').toString(), 'Trivy image severities')
    boolean failBuild = config.get('failOnVulnerabilities', true)
    String timeout = TrivyValidation.timeout((config.timeout ?: '15m').toString(), 'Trivy image timeout')
    List skipDirs = TrivyValidation.skipPaths(config.skipDirs ?: [], 'Trivy image skip directory')
    String cacheDir = TrivyValidation.cachePath((config.cacheDir ?: '/home/jenkins/.cache/trivy').toString(), 'Trivy cache directory')
    String containerName = config.container ?: 'trivy'
    boolean failFast = config.get('failFast', true)
    int exitCode = failBuild ? 1 : 0
    String skipDirFlags = skipDirs.collect { dir -> "--skip-dirs ${Validation.shellQuote(dir)}" }.join(' ')

    List images = resolveImages(config)
    validateUniqueReports(images, outputDir, '.trivy.json')

    Map branches = [:]
    images.each { image ->
        String reportBase = "${outputDir}/${safeFileBase(image.imageRef)}"
        String reportFile = "${reportBase}.trivy.json"
        String summaryFile = "${reportBase}.trivy.txt"
        String summaryTemplateFile = ".trivy-image-summary-${safeFileBase(image.imageRef)}.tpl"

        branches["Trivy image scan: ${image.name}"] = {
            container(containerName) {
                if (!fileExists(image.archive)) {
                    error "Docker image archive does not exist for ${image.name}: ${image.archive}"
                }

                writeFile file: summaryTemplateFile, text: trivySummaryTemplate()
                String isolatedCacheDir = "/tmp/trivy-image-${UUID.randomUUID().toString()}"

                withEnv([
                    "TRIVY_SOURCE_CACHE=${cacheDir}",
                    "TRIVY_ISOLATED_CACHE=${isolatedCacheDir}",
                    "TRIVY_IMAGE_ARCHIVE=${image.archive}",
                    "TRIVY_REPORT_FILE=${reportFile}",
                    "TRIVY_SUMMARY_FILE=${summaryFile}",
                    "TRIVY_SUMMARY_TEMPLATE=${summaryTemplateFile}"
                ]) {
                    try {
                        int status = sh(
                            label: "Trivy image scan: ${image.name}",
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
                                    --exit-code 0 \\
                                    --severity ${Validation.shellQuote(severities)} \\
                                    --scanners vuln \\
                                    --timeout ${Validation.shellQuote(timeout)} \\
                                    --format template \\
                                    --template "@\$TRIVY_SUMMARY_TEMPLATE" \\
                                    --output "\$TRIVY_SUMMARY_FILE"

                                echo "----- Trivy image scan summary: ${image.name} -----"
                                if [ -s "\$TRIVY_SUMMARY_FILE" ]; then
                                    sed -n '1,220p' "\$TRIVY_SUMMARY_FILE"
                                    SUMMARY_LINES=\$(wc -l < "\$TRIVY_SUMMARY_FILE" | tr -d ' ')
                                    if [ "\$SUMMARY_LINES" -gt 220 ]; then
                                        echo "... summary truncated in console; full report is archived at \$TRIVY_SUMMARY_FILE"
                                    fi
                                else
                                    echo "Trivy summary report is empty: \$TRIVY_SUMMARY_FILE"
                                fi

                                TRIVY_STATUS=0
                                trivy image \\
                                    --input "\$TRIVY_IMAGE_ARCHIVE" \\
                                    --skip-db-update \\
                                    --cache-dir "\$TRIVY_ISOLATED_CACHE" \\
                                    ${skipDirFlags} \\
                                    --exit-code ${exitCode} \\
                                    --severity ${Validation.shellQuote(severities)} \\
                                    --scanners vuln \\
                                    --timeout ${Validation.shellQuote(timeout)} \\
                                    --format json \\
                                    --output "\$TRIVY_REPORT_FILE" || TRIVY_STATUS=\$?

                                exit "\$TRIVY_STATUS"
                            """
                        )

                        if (!fileExists(summaryFile)) {
                            error "Trivy image summary was not generated for ${image.name}: ${summaryFile}"
                        }

                        if (!fileExists(reportFile)) {
                            error "Trivy image report was not generated for ${image.name}: ${reportFile}"
                        }

                        archiveArtifacts(
                            allowEmptyArchive: false,
                            artifacts: "${summaryFile},${reportFile}",
                            fingerprint: true
                        )

                        if (status != 0) {
                            error "Trivy image scan failed for ${image.name}. See ${summaryFile} and ${reportFile}"
                        }
                    } finally {
                        int cleanupStatus = sh(
                            label: "Clean Trivy image cache: ${image.name}",
                            returnStatus: true,
                            script: 'rm -rf "$TRIVY_ISOLATED_CACHE"'
                        )

                        if (cleanupStatus != 0) {
                            echo "WARNING: Trivy image temporary cache cleanup failed for ${image.name}."
                        }
                    }
                }
            }
        }
    }

    parallel branches + [failFast: failFast]
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

private void validateUniqueReports(List images, String outputDir, String extension) {
    List reports = images.collect { image ->
        "${outputDir}/${safeFileBase(image.imageRef)}${extension}"
    }

    if (reports.size() != reports.unique().size()) {
        throw new IllegalArgumentException("Duplicate Trivy image report files are not allowed: ${reports}")
    }
}

private String trivySummaryTemplate() {
    return '''{{- range .Results }}
Target: {{ .Target }} ({{ .Type }})
{{- if .Vulnerabilities }}

ID | Severity | Package | Installed | Fixed | Title
---|----------|---------|-----------|-------|------
{{- range .Vulnerabilities }}
{{ .VulnerabilityID }} | {{ .Severity }} | {{ .PkgName }} | {{ .InstalledVersion }} | {{ if .FixedVersion }}{{ .FixedVersion }}{{ else }}-{{ end }} | {{ .Title }}
{{- end }}
{{- else }}

No vulnerabilities found.
{{- end }}

{{ end -}}
'''
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
