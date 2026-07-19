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
 *       yaml ciLintPodTemplate(images: imageBuildConfig.images)
 *       defaultContainer 'jnlp'
 *     }
 *   }
 * }
 */
def call(Map config = [:]) {
    String template = libraryResource('com/company/jenkins/pods/lint-pod.yaml')
    return template.replace('__DOCKER_GRAPH_STORAGE_VOLUME__', dockerGraphStorageVolume(config))
}

private String dockerGraphStorageVolume(Map config) {
    String pvcName = resolveDockerCachePvc(config)
    if (!pvcName) {
        return '''    - name: docker-graph-storage
      emptyDir:
        sizeLimit: 20Gi'''
    }

    return """    - name: docker-graph-storage
      persistentVolumeClaim:
        claimName: ${pvcName}"""
}

private String resolveDockerCachePvc(Map config) {
    if (config.dockerCachePvc) {
        return validatePvcName(config.dockerCachePvc.toString())
    }

    String branchName = (config.branchName ?: env.BRANCH_NAME ?: '').toString()
    String releasePrefix = (config.releasePrefix ?: 'release/').toString()
    if (!branchName.startsWith(releasePrefix)) {
        return ''
    }

    List images = config.images ?: []
    if (images.isEmpty()) {
        return ''
    }

    String releaseSpec = branchName
        .substring(releasePrefix.length())
        .replaceAll(/[^A-Za-z0-9_.-]/, '-')

    String serviceName = resolveServiceName(images, releaseSpec)
    if (!serviceName) {
        return ''
    }

    return validatePvcName("jenkins-docker-cache-${serviceName}-pvc")
}

private String resolveServiceName(List images, String releaseSpec) {
    List imageNames = images.collect { image ->
        image.name?.toString()
    }.findAll { name ->
        name
    }

    if (images.size() == 1 && releaseSpec.startsWith('v')) {
        return imageNames[0]
    }

    List matchingNames = imageNames.findAll { imageName ->
        releaseSpec.startsWith("${imageName}-")
    }

    return matchingNames.size() == 1 ? matchingNames[0] : ''
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
