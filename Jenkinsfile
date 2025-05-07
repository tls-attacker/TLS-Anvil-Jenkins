pipeline {
    agent any

    environment {
            JDK_TOOL_NAME = 'JDK 21'
            MAVEN_TOOL_NAME = 'Maven 3.9.9'
        }

        options {
            skipStagesAfterUnstable()
            disableConcurrentBuilds abortPrevious: true
        }

    stages {
        stage('Clean') {
            steps {
                withMaven(jdk: env.JDK_TOOL_NAME, maven: env.MAVEN_TOOL_NAME) {
                    sh 'mvn clean'
                }
            }
        }

        stage('Build') {
            steps {
                withMaven(jdk: env.JDK_TOOL_NAME, maven: env.MAVEN_TOOL_NAME) {
                    sh 'mvn package hpi:hpi'
                }
            }

            post {
                success {
                    archiveArtifacts artifacts: 'target/*.hpi', fingerprint: true
                }
            }
        }

        stage('Make Github Release') {
            when {
                tag 'v*'
            }
            steps {
                writeFile file: 'release_description.md', text: 'A new version of the TLS-Anvil Jenkins plugin was released. You can download the pluginfile (.hpi) below. \n\n## Changelog:\n  - TODO'
                sh "cp target/*.hpi TLS-Anvil-Jenkins-${TAG_NAME}.hpi"
                script {
                    def draftRelease = createGitHubRelease(
                        credentialId: '1522a497-e78a-47ee-aac5-70f071fa6714',
                        repository: GIT_URL.tokenize("/.")[-3,-2].join("/"),
                        draft: true,
                        tag: TAG_NAME,
                        name: TAG_NAME,
                        bodyFile: 'release_description.md',
                        commitish: GIT_COMMIT)
                    uploadGithubReleaseAsset(
                        credentialId: '1522a497-e78a-47ee-aac5-70f071fa6714',
                        repository: GIT_URL.tokenize("/.")[-3,-2].join("/"),
                        tagName: draftRelease.htmlUrl.tokenize("/")[-1],
                        uploadAssets: [
                            [filePath: "${env.WORKSPACE}/TLS-Anvil-Jenkins-${TAG_NAME}.hpi"]
                        ]
                    )
                }
            }
        }
    }
}