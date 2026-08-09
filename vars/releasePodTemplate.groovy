/**
 * Return the minimal Kubernetes pod template used by manual release pipelines.
 *
 * Release jobs need Jenkins remoting, Docker CLI/DinD for docker load, tag,
 * push, and optional fallback builds, plus Kubernetes tooling for optional
 * deploys. The kubernetes container uses the custom kubernetes-tools image,
 * which includes kubectl, Helm, the ArgoCD CLI, and Argo Rollouts plugin. The pod
 * does not mount a Kubernetes service account token; deploy helpers write a
 * kubeconfig from Jenkins credentials when kubectl is needed. Lint, test,
 * SonarQube, Hadolint, and Trivy containers intentionally stay out of this pod.
 *
 * @param config Map containing:
 *   - dockerCachePvc: Optional PVC mounted as docker-dind /var/lib/docker.
 *     When omitted, a pod-local emptyDir is used.
 *
 * @return String Kubernetes Pod YAML consumed by the Jenkins Kubernetes plugin
 *
 * @example
 * pipeline {
 *   agent {
 *     kubernetes {
 *       yaml releasePodTemplate(dockerCachePvc: 'jenkins-docker-cache-user-service-pvc')
 *       defaultContainer 'jnlp'
 *     }
 *   }
 * }
 */
def call(Map config = [:]) {
    String template = libraryResource('com/company/jenkins/pods/release-pod.yaml')
    return template.replace('__DOCKER_GRAPH_STORAGE_VOLUME__', dockerGraphStorageVolume(config))
}

private String dockerGraphStorageVolume(Map config) {
    String pvcName = config.dockerCachePvc ? validatePvcName(config.dockerCachePvc.toString()) : ''
    if (!pvcName) {
        return '''    - name: docker-graph-storage
      emptyDir:
        sizeLimit: 20Gi'''
    }

    return """    - name: docker-graph-storage
      persistentVolumeClaim:
        claimName: ${pvcName}"""
}

private String validatePvcName(String value) {
    if (!value) {
        return ''
    }

    if (!(value ==~ /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/)) {
        throw new IllegalArgumentException("Invalid Docker cache PVC name: ${value}")
    }

    return value
}
