#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Upload CycloneDX SBOM files to Dependency-Track through its REST API.
 *
 * This helper does not require the Jenkins Dependency-Track plugin. It uses a
 * Jenkins Secret Text credential for the API key and uploads each SBOM with the
 * `/api/v1/bom` endpoint.
 *
 * @param config Map containing:
 *   - sboms: REQUIRED list of maps: [file, projectName, projectVersion]
 *   - dependencyTrackUrl: API server base URL
 *   - credentialsId: Jenkins Secret Text credential id (default: 'dependency-track-api-key')
 *   - autoCreate: Auto-create projects (default: true)
 *   - failOnUploadError: Fail build if upload fails (default: true)
 *   - container: Jenkins Kubernetes container name (default: 'python')
 */
def call(Map config = [:]) {
    List sboms = normalizeSboms(config.sboms ?: [])
    if (sboms.isEmpty()) {
        error 'uploadSBOMsToDependencyTrack requires sboms'
    }

    String dependencyTrackUrl = apiUrl((config.dependencyTrackUrl ?: 'http://dtrack-dependency-track-api-server.dependency-track.svc.cluster.local:8080').toString())
    String credentialsId = credentialId((config.credentialsId ?: 'dependency-track-api-key').toString())
    boolean autoCreate = config.get('autoCreate', true)
    boolean failOnUploadError = config.get('failOnUploadError', true)
    String containerName = config.container ?: 'python'

    container(containerName) {
        withCredentials([string(credentialsId: credentialsId, variable: 'DEPENDENCY_TRACK_API_KEY')]) {
            sboms.each { sbom ->
                if (!fileExists(sbom.file)) {
                    error "SBOM file does not exist: ${sbom.file}"
                }

                withEnv([
                    "DT_URL=${dependencyTrackUrl}",
                    "DT_SBOM_FILE=${sbom.file}",
                    "DT_PROJECT_NAME=${sbom.projectName}",
                    "DT_PROJECT_VERSION=${sbom.projectVersion}",
                    "DT_AUTO_CREATE=${autoCreate.toString()}"
                ]) {
                    int status = sh(
                        label: "Upload SBOM: ${sbom.projectName}:${sbom.projectVersion}",
                        returnStatus: true,
                        script: '''
                            set -eu
                            cd "$WORKSPACE"
                            python - <<'PY'
import json
import mimetypes
import os
import sys
import uuid
import urllib.error
import urllib.request

url = os.environ["DT_URL"].rstrip("/") + "/api/v1/bom"
api_key = os.environ["DEPENDENCY_TRACK_API_KEY"]
sbom_file = os.environ["DT_SBOM_FILE"]
project_name = os.environ["DT_PROJECT_NAME"]
project_version = os.environ["DT_PROJECT_VERSION"]
auto_create = os.environ["DT_AUTO_CREATE"].lower()

boundary = "----jenkins-dtrack-%s" % uuid.uuid4().hex

def field(name, value):
    return (
        "--%s\r\n"
        "Content-Disposition: form-data; name=\"%s\"\r\n\r\n"
        "%s\r\n"
    ) % (boundary, name, value)

body = bytearray()
for key, value in {
    "autoCreate": auto_create,
    "projectName": project_name,
    "projectVersion": project_version,
}.items():
    body.extend(field(key, value).encode("utf-8"))

filename = os.path.basename(sbom_file)
content_type = mimetypes.guess_type(filename)[0] or "application/json"
body.extend((
    "--%s\r\n"
    "Content-Disposition: form-data; name=\"bom\"; filename=\"%s\"\r\n"
    "Content-Type: %s\r\n\r\n"
) % (boundary, filename, content_type)).encode("utf-8")
with open(sbom_file, "rb") as fh:
    body.extend(fh.read())
body.extend(b"\r\n")
body.extend(("--%s--\r\n" % boundary).encode("utf-8"))

request = urllib.request.Request(
    url,
    data=bytes(body),
    method="POST",
    headers={
        "X-Api-Key": api_key,
        "Content-Type": "multipart/form-data; boundary=%s" % boundary,
        "Accept": "application/json",
    },
)

try:
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = response.read().decode("utf-8", errors="replace")
        print("Dependency-Track upload accepted: HTTP %s" % response.status)
        if payload:
            try:
                print(json.dumps(json.loads(payload), indent=2))
            except json.JSONDecodeError:
                print(payload)
except urllib.error.HTTPError as exc:
    sys.stderr.write("Dependency-Track upload failed: HTTP %s\n" % exc.code)
    sys.stderr.write(exc.read().decode("utf-8", errors="replace") + "\n")
    sys.exit(1)
except urllib.error.URLError as exc:
    sys.stderr.write("Dependency-Track upload failed: %s\n" % exc.reason)
    sys.exit(1)
PY
                        '''
                    )

                    if (status != 0) {
                        String message = "Dependency-Track upload failed for ${sbom.projectName}:${sbom.projectVersion}"
                        if (failOnUploadError) {
                            error message
                        }
                        unstable message
                    }
                }
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
