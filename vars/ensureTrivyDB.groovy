#!/usr/bin/env groovy

/**
 * Ensure the Trivy vulnerability database exists in the persistent cache.
 *
 * The Jenkins Kubernetes agent mounts the Trivy cache PVC into the `trivy`
 * container. This helper updates that persistent cache once, then scan helpers
 * copy it into isolated workspace-local cache directories before running.
 *
 * @param config Map containing:
 *   - forceUpdate: Force database update (default: false)
 *   - cacheDir: Persistent Trivy cache path (default: '/home/jenkins/.cache/trivy')
 *   - container: Jenkins Kubernetes container name (default: 'trivy')
 */
def call(Map config = [:]) {
    boolean forceUpdate = config.get('forceUpdate', false)
    String cacheDir = trivyCachePath((config.cacheDir ?: '/home/jenkins/.cache/trivy').toString(), 'Trivy cache directory')
    String containerName = config.container ?: 'trivy'

    container(containerName) {
        withEnv(["TRIVY_CACHE_DIR=${cacheDir}"]) {
            int dbExists = sh(
                label: 'Check Trivy DB cache',
                returnStatus: true,
                script: '''
                    set -eu
                    test -f "$TRIVY_CACHE_DIR/db/trivy.db" || test -f "$TRIVY_CACHE_DIR/db/metadata.json"
                '''
            )

            if (dbExists == 0 && !forceUpdate) {
                echo "Trivy vulnerability database already exists in ${cacheDir}."
                return
            }

            sh(
                label: 'Update Trivy DB cache',
                script: '''
                    set -eu
                    mkdir -p "$TRIVY_CACHE_DIR"
                    trivy image --download-db-only --quiet --cache-dir "$TRIVY_CACHE_DIR"
                '''
            )
        }
    }
}

private String trivyCachePath(String path, String label) {
    if (!path) {
        throw new IllegalArgumentException("${label} cannot be empty")
    }

    if (path.contains('..') || path.startsWith('-')) {
        throw new IllegalArgumentException("Invalid ${label}: ${path}")
    }

    if (!(path ==~ /^[A-Za-z0-9._\/-]+$/)) {
        throw new IllegalArgumentException("Invalid characters in ${label}: ${path}")
    }

    return path
}
