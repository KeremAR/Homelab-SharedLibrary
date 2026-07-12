#!/usr/bin/env groovy

import com.company.jenkins.Validation

/**
 * Run Node.js frontend linting for one or more package directories.
 *
 * This library is intended for React/Vite or similar Node-based frontends.
 * It installs dependencies with npm ci and then runs the configured npm lint
 * script inside the Jenkins Kubernetes agent's node container.
 *
 * LINT BEHAVIOR:
 * - Runs `npm ci --prefer-offline --no-audit`
 * - Runs `npm run <lintScript>`
 * - Uses the mounted npm cache PVC through NPM_CONFIG_CACHE
 * - Executes each package directory in parallel
 *
 * CACHE BEHAVIOR:
 * - node_modules is not cached
 * - npm tarball/cache data is cached by the agent pod
 *
 * SECURITY:
 * - Validates package directory paths
 * - Validates npm script names
 * - Does not execute arbitrary shell fragments from config
 *
 * @param config Map containing:
 *   - packageDirs: REQUIRED - List of repository-relative package directories
 *   - path: Single package directory alias
 *   - lintScript: npm script name to run (default: 'lint')
 *   - container: Container name to run in (default: 'node')
 *   - failFast: Whether parallel branches should fail fast (default: true)
 *
 * @example
 * runNodeLinting(
 *     packageDirs: ['frontend'],
 *     lintScript: 'lint'
 * )
 */
def call(Map config = [:]) {
    List packageDirs = config.packageDirs ?: (config.path ? [config.path] : [])
    if (packageDirs.isEmpty()) {
        error 'runNodeLinting requires packageDirs, for example: [packageDirs: ["frontend"]]'
    }

    String containerName = config.container ?: 'node'
    String lintScript = Validation.npmScriptName((config.lintScript ?: 'lint').toString())
    boolean failFast = config.get('failFast', true)

    List safePackageDirs = Validation.uniqueRelativePaths(packageDirs, 'Node package directory')

    Map branches = [:]
    safePackageDirs.each { safeDir ->

        branches["Node lint: ${safeDir}"] = {
            container(containerName) {
                if (!fileExists("${safeDir}/package.json")) {
                    error "package.json does not exist: ${safeDir}/package.json"
                }

                if (!fileExists("${safeDir}/package-lock.json")) {
                    error "package-lock.json does not exist: ${safeDir}/package-lock.json"
                }

                dir(safeDir) {
                    sh(
                        label: "Install frontend dependencies: ${safeDir}",
                        script: '''
                            set -eu
                            npm ci --prefer-offline --no-audit
                        '''
                    )
                    sh(
                        label: "ESLint: ${safeDir}",
                        script: "set -eu\nnpm run ${lintScript}"
                    )
                }
            }
        }
    }

    parallel branches + [failFast: failFast]
}
