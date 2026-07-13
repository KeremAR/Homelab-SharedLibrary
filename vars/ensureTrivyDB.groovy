#!/usr/bin/env groovy

import com.company.jenkins.TrivyValidation

/**
 * Ensure the Trivy vulnerability database in the persistent cache is current.
 *
 * The helper always lets Trivy perform its own DB freshness check. If the DB is
 * current, Trivy reuses it. If it is stale or missing, Trivy updates it.
 *
 * @param config Map containing:
 *   - cacheDir: Persistent Trivy cache path (default: '/home/jenkins/.cache/trivy')
 *   - container: Jenkins Kubernetes container name (default: 'trivy')
 *   - lockResource: Jenkins lockable resource name (default: 'trivy-db-cache')
 */
def call(Map config = [:]) {
    String cacheDir = TrivyValidation.cachePath((config.cacheDir ?: '/home/jenkins/.cache/trivy').toString(), 'Trivy cache directory')
    String containerName = config.container ?: 'trivy'
    String lockResource = lockResourceName((config.lockResource ?: 'trivy-db-cache').toString())

    lock(resource: lockResource) {
        container(containerName) {
            withEnv(["TRIVY_CACHE_DIR=${cacheDir}"]) {
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
}

private String lockResourceName(String value) {
    if (!value || !(value ==~ /^[A-Za-z0-9_.-]+$/)) {
        throw new IllegalArgumentException("Invalid Jenkins lock resource name: ${value}")
    }

    return value
}
