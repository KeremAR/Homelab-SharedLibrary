#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Upload CycloneDX SBOM files to Dependency-Track with the Jenkins plugin.
 *
 * This helper wraps the OWASP Dependency-Track Jenkins plugin's
 * `dependencyTrackPublisher` Pipeline step. It keeps project/version validation
 * in the shared library while leaving BOM publishing to the plugin instead of a
 * custom REST client.
 *
 * @param config Map containing:
 *   - sboms: REQUIRED list of maps: [file, projectName, projectVersion]
 *   - dependencyTrackUrl: API server base URL
 *   - credentialsId: Jenkins Secret Text credential id (default: 'dependency-track-api-key')
 *   - autoCreate: Auto-create projects (default: true)
 *   - synchronous: Wait for Dependency-Track processing result (default: false)
 *   - failOnUploadError: Fail build if upload fails (default: true)
 */
def call(Map config = [:]) {
    List sboms = normalizeSboms(config.sboms ?: [])
    if (sboms.isEmpty()) {
        error 'uploadSBOMsToDependencyTrack requires sboms'
    }

    String dependencyTrackUrl = apiUrl((config.dependencyTrackUrl ?: 'http://dtrack-dependency-track-api-server.dependency-track.svc.cluster.local:8080').toString())
    String credentialsId = credentialId((config.credentialsId ?: 'dependency-track-api-key').toString())
    boolean autoCreate = config.get('autoCreate', true)
    boolean synchronous = config.get('synchronous', false)
    boolean failOnUploadError = config.get('failOnUploadError', true)

    withCredentials([string(credentialsId: credentialsId, variable: 'DEPENDENCY_TRACK_API_KEY')]) {
        sboms.each { sbom ->
            if (!fileExists(sbom.file)) {
                error "SBOM file does not exist: ${sbom.file}"
            }

            try {
                dependencyTrackPublisher(
                    artifact: sbom.file,
                    projectName: sbom.projectName,
                    projectVersion: sbom.projectVersion,
                    dependencyTrackUrl: dependencyTrackUrl,
                    dependencyTrackApiKey: env.DEPENDENCY_TRACK_API_KEY,
                    autoCreateProjects: autoCreate,
                    synchronous: synchronous
                )
            } catch (uploadError) {
                String message = "Dependency-Track upload failed for ${sbom.projectName}:${sbom.projectVersion}: ${uploadError.message}"
                if (failOnUploadError) {
                    error message
                }
                unstable message
            }
        }
    }
}

private List normalizeSboms(List values) {
    return values.collect { value ->
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("SBOM config must be a Map: ${value}")
        }

        [
            file: Validation.relativePath(value.file?.toString(), 'SBOM file'),
            projectName: projectName(value.projectName?.toString(), 'Dependency-Track project name'),
            projectVersion: projectVersion(value.projectVersion?.toString(), 'Dependency-Track project version')
        ]
    }
}

private String apiUrl(String value) {
    if (!value || value.startsWith('-') || value.contains('..')) {
        throw new IllegalArgumentException("Invalid Dependency-Track URL: ${value}")
    }

    if (!(value ==~ /^https?:\/\/[A-Za-z0-9._:\/-]+$/)) {
        throw new IllegalArgumentException("Invalid Dependency-Track URL: ${value}")
    }

    return value
}

private String credentialId(String value) {
    if (!value || value.startsWith('-') || !(value ==~ /^[A-Za-z0-9_.-]+$/)) {
        throw new IllegalArgumentException("Invalid Jenkins credential id: ${value}")
    }

    return value
}

private String projectName(String value, String label) {
    if (!value || value.startsWith('-') || value.contains('..')) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    if (!(value ==~ /^[A-Za-z0-9][A-Za-z0-9_.\/-]*$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}

private String projectVersion(String value, String label) {
    if (!value || value.startsWith('-') || value.contains('..')) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    if (!(value ==~ /^[A-Za-z0-9][A-Za-z0-9_.\/:-]*$/)) {
        throw new IllegalArgumentException("Invalid ${label}: ${value}")
    }

    return value
}
