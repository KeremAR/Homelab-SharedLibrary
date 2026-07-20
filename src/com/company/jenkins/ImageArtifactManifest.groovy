package com.company.jenkins

/**
 * Parse the image manifest written by runBuildImages.
 *
 * The manifest is a tab-separated file with one image per line:
 *
 * name<TAB>imageRef<TAB>archivePath<TAB>platform
 */
class ImageArtifactManifest implements Serializable {
    static List<Map> parse(String content, String label = 'image artifact manifest') {
        if (!content || !content.trim()) {
            throw new IllegalArgumentException("${label} is empty")
        }

        List<Map> images = []
        content.readLines().eachWithIndex { line, index ->
            if (!line.trim()) {
                return
            }

            List<String> fields = line.split('\t') as List<String>
            if (fields.size() != 4) {
                throw new IllegalArgumentException(
                    "Invalid ${label} line ${index + 1}. Expected 4 tab-separated fields, got ${fields.size()}: ${line}"
                )
            }

            images << [
                name: imageName(fields[0], "image name on line ${index + 1}"),
                imageRef: imageReference(fields[1], "image reference on line ${index + 1}"),
                archive: Validation.relativePath(fields[2], "image archive on line ${index + 1}"),
                platform: platform(fields[3], "platform on line ${index + 1}")
            ]
        }

        if (images.isEmpty()) {
            throw new IllegalArgumentException("${label} does not contain any image rows")
        }

        return images
    }

    static String tagFromImageRef(String imageRef) {
        int slash = imageRef.lastIndexOf('/')
        int colon = imageRef.lastIndexOf(':')

        if (colon > slash && colon < imageRef.length() - 1) {
            return imageRef.substring(colon + 1)
        }

        return 'untagged'
    }

    private static String imageName(String value, String label) {
        if (!value || value.startsWith('-') || value.contains('..')) {
            throw new IllegalArgumentException("Invalid ${label}: ${value}")
        }

        if (!(value ==~ /^[A-Za-z0-9][A-Za-z0-9_.\/-]*$/)) {
            throw new IllegalArgumentException("Invalid ${label}: ${value}")
        }

        return value
    }

    private static String imageReference(String value, String label) {
        if (!value || value.startsWith('-') || value.contains('..')) {
            throw new IllegalArgumentException("Invalid ${label}: ${value}")
        }

        if (!(value ==~ /^[A-Za-z0-9][A-Za-z0-9._:\/@-]*$/)) {
            throw new IllegalArgumentException("Invalid ${label}: ${value}")
        }

        return value
    }

    private static String platform(String value, String label) {
        if (!value || !(value ==~ /^[A-Za-z0-9_]+\/[A-Za-z0-9_.-]+(\/[A-Za-z0-9_.-]+)?$/)) {
            throw new IllegalArgumentException("Invalid ${label}: ${value}")
        }

        return value
    }
}
