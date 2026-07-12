/**
 * Return the Kubernetes pod template used by lint-oriented Jenkins pipelines.
 *
 * This is the preferred name for new Jenkinsfiles. The older
 * ciPythonPodTemplate() helper remains available as a backward-compatible
 * alias because the lint pod still contains the Python tooling container.
 *
 * @return String Kubernetes Pod YAML consumed by the Jenkins Kubernetes plugin
 *
 * @example
 * pipeline {
 *   agent {
 *     kubernetes {
 *       yaml ciLintPodTemplate()
 *       defaultContainer 'jnlp'
 *     }
 *   }
 * }
 */
def call() {
    return libraryResource('com/company/jenkins/pods/lint-pod.yaml')
}
