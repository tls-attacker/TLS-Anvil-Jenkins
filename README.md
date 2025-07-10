# TLS-Anvil Jenkins Plugin

## Overview

The **TLS-Anvil Jenkins Plugin** allows you to integrate [TLS-Anvil](https://github.com/tls-attacker/tls-anvil) into your Jenkins pipeline.
TLS-Anvil is a powerful tool for testing the security of TLS implementations. This plugin enables you to run TLS-Anvil tests as part of your continuous integration and deployment (CI/CD) process, ensuring that your applications are secure against various TLS vulnerabilities.

## Features

- Run TLS-Anvil tests directly from your Jenkins pipeline.
- Configure test parameters and options through the Jenkins UI.
- View test results and logs in Jenkins.
- Supports manual installation via HPI file.

## Installation

This plugin needs Docker installed on your worker as well as the [Docker Commons](https://plugins.jenkins.io/docker-commons/) plugin.

Since this plugin is not published in the Jenkins Plugin Hub, you will need to install it manually. Follow these steps:

1. Download the latest HPI file from the [Releases](https://github.com/tls-attacker/TLS-Anvil-Jenkins/releases) page.
2. Open your Jenkins instance.
3. Navigate to **Manage Jenkins** > **Manage Plugins**.
4. Go to the **Advanced** tab.
5. Under the **Upload Plugin** section, click on **Choose File** and select the downloaded HPI file.
6. Click on **Upload** to install the plugin.
7. Restart Jenkins if prompted.

## Usage

To use the TLS-Anvil Plugin in your Jenkins pipeline, you can add the `runTlsAnvil` step to your `Jenkinsfile`:

```groovy
pipeline {
    agent any

    stages {
        stage('Run TLS-Anvil Tests') {
            steps {
                runTlsAnvil(
                        endpointMode: 'server',
                        serverScript: './my-tls-server --port 4433',
                        host: 'localhost:4433',
                        expectedResults: 'expected.json'
                )
            }
        }
    }
}
```

### Parameters

- `endpointMode`: (String) If we want to test a server or a client, either 'server' or 'client'.
- Server
  - `host`: (String) The hostname and port of the target server, when using server mode.
  - `serverScript`: (String) Path to the server executable or script that starts the server (e.g., './my-tls-server --port 4433').
  - `runOnce`: (Boolean) Whether to run the server script only once or in a loop.
  - `useSni`: (Boolean) Whether to send the SNI extension (default is false).
- Client
  - `port`: (Integer) The port, TLS-Anvil will use for the client test.
  - `clientScript`: (String) The script, that will trigger your TLS client to connect to TLS-Anvil (e.g. './my-tls-client --connect localhost:8443')
- `expectedResults`: (String) Path to the expected results file (e.g., 'expected.json'). This can be useful for continuous testing.
- There are various other settings specific to TLS-Anvil, that you can also see on the Jenkins pipeline syntax page.
  - `disableTcpDump`: (Boolean) Whether to disable logging network traffic (default is `false`).
  - `ignoreCache`: (Boolean) Whether to ignore the cache when scanning for features (default is `false`).
  - `parallelHandshakes`: (Integer) Number of parallel handshakes to run (default is `1`).
  - `sniName`: (String) The SNI name to use, when useSni is true.
  - `strength`: (Integer) The strength of the test (default is `3`).
  - `tags`: (String) If set, TLS-Anvil only runs tests with these specific tags.
  - `testPackage`: (String) The test package to use (default is 'de.rub.nds.tlstest.suite.tests').
  - `timeout`: (Integer) Timeout for the test in seconds (default is `200`).
  - `useDtls`: (Boolean) Whether to test DTLS (default is `false`, experimental feature).
  - ...
  
## Viewing Results

After the TLS-Anvil tests are executed, you can view the results directly inside the Jenkins UI using the TLS-Anvil tab on the left navigation of the pipeline job.
This will only display a static report file and won't let you see details. For a more detailed view, download the `report.zip` artifact that gets generated automatically and import in into [Anvil-Web](https://github.com/tls-attacker/Anvil-Web)

## Support

For any issues or feature requests, please open an issue in the [GitHub repository](https://github.com/tls-attacker/TLS-Anvil-Jenkins/issues).