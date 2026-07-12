/**
 * Backward-compatible alias for ciLintPodTemplate().
 *
 * Prefer ciLintPodTemplate() in new Jenkinsfiles. This helper remains so older
 * pipelines can keep working while they migrate to the clearer lint pod name.
 *
 * @return String Kubernetes Pod YAML consumed by the Jenkins Kubernetes plugin
 *
 * @example
 * pipeline {
 *   agent {
 *     kubernetes {
 *       yaml ciPythonPodTemplate()
 *       defaultContainer 'jnlp'
 *     }
 *   }
 * }
 */
def call() {
    return ciLintPodTemplate()
}
