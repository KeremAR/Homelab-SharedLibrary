package com.company.jenkins

/**
 * Resolve Homelab release branch metadata from a Jenkins branch name.
 *
 * This class keeps release branch parsing in one place so pod templates and
 * release build steps cannot drift into different interpretations of the same
 * branch.
 *
 * SUPPORTED BRANCHES:
 * - release/<service>-v<version> for monorepos
 * - release/v<version> for single-image repositories
 */
class ReleaseResolver implements Serializable {
    static Map resolve(Map config = [:]) {
        List images = config.images ?: []
        String branchName = (config.branchName ?: '').toString()
        String releasePrefix = (config.releasePrefix ?: 'release/').toString()
        boolean required = config.containsKey('required') ? config.required as boolean : true

        if (images.isEmpty()) {
            return failOrEmpty(required, 'Release resolution requires images')
        }

        if (!branchName.startsWith(releasePrefix)) {
            return failOrEmpty(
                required,
                "Release branch must start with ${releasePrefix}. Got: ${branchName}"
            )
        }

        String releaseSpec = branchName
            .substring(releasePrefix.length())
            .replaceAll(/[^A-Za-z0-9_.-]/, '-')

        List imageNames = images.collect { image ->
            image.name?.toString()
        }.findAll { name ->
            name
        }

        if (imageNames.isEmpty()) {
            return failOrEmpty(required, 'Release resolution requires image names')
        }

        if (images.size() == 1 && releaseSpec.startsWith('v')) {
            return [
                releaseBranch: true,
                service: imageNames[0],
                version: releaseSpec,
                releaseSpec: releaseSpec
            ]
        }

        List matchingNames = imageNames.findAll { imageName ->
            releaseSpec.startsWith("${imageName}-")
        }

        if (matchingNames.size() != 1) {
            return failOrEmpty(
                required,
                "Release branch must match exactly one service as ${releasePrefix}<service>-v<version>. Branch: ${branchName}, matched services: ${matchingNames}"
            )
        }

        String serviceName = matchingNames[0]
        String version = releaseSpec.substring("${serviceName}-".length())
        if (!version.startsWith('v')) {
            return failOrEmpty(
                required,
                "Release branch version must start with v. Expected ${releasePrefix}${serviceName}-v<version>, got: ${branchName}"
            )
        }

        return [
            releaseBranch: true,
            service: serviceName,
            version: version,
            releaseSpec: releaseSpec
        ]
    }

    private static Map failOrEmpty(boolean required, String message) {
        if (required) {
            throw new IllegalArgumentException(message)
        }

        return [
            releaseBranch: false,
            service: '',
            version: '',
            releaseSpec: ''
        ]
    }
}
