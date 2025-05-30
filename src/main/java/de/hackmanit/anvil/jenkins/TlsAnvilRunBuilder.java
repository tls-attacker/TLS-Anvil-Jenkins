package de.hackmanit.anvil.jenkins;

import hudson.*;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.docker.commons.tools.DockerTool;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class TlsAnvilRunBuilder extends Builder implements SimpleBuildStep {

    public static final String ANVIL_VERSION = "v1.3.1";
    public static final String EXPECTED_DEFAULT = "{\n  \"STRICTLY_SUCCEEDED\": [],\n  \"CONCEPTUALLY_SUCCEEDED\": [],\n  \"FULLY_FAILED\": [],\n  \"DISABLED\": [],\n  \"PARTIALLY_FAILED\": []\n}";

    public final String endpointMode;
    // server
    public final String serverScript;
    public final String host;
    public final Boolean runOnce;
    public final Boolean useSni;
    public final String sniName;
    // client
    public final Integer port;
    public final String clientScript;
    // general
    public final Integer strength;
    public final Integer parallelTestcase;
    // advanced
    public final Integer timeout;
    public final boolean disableTcpDump;
    public final boolean useDtls;
    public final boolean ignoreCache;
    public final Integer restartAfter;
    public final String tags;
    public final String testPackage;
    public final String profileFolder;
    public final String profiles;
    // compare results
    public final String expectedResults;
    public Boolean isCompareResults() { return null; }
    public String getExpectedDefault() { return EXPECTED_DEFAULT; }

    private List<String> args;

    @DataBoundConstructor
    public TlsAnvilRunBuilder(String endpointMode, String serverScript, boolean runOnce, String host, Integer port,
                              String clientScript, boolean useSni, String sniName,
                              Integer strength, Integer parallelTestcase, Integer timeout, boolean disableTcpDump,
                              boolean useDtls, boolean ignoreCache, Integer restartAfter, String tags,
                              String testPackage, String expectedResults, boolean compareResults, String profileFolder,
                              String profiles) {

        this.endpointMode = endpointMode;
        this.serverScript = endpointMode.equalsIgnoreCase("server") ? serverScript : null;
        this.runOnce = endpointMode.equalsIgnoreCase("server") ? runOnce : null;
        this.host = endpointMode.equalsIgnoreCase("server") ? host : null;
        this.useSni = endpointMode.equalsIgnoreCase("server") ? useSni : null;
        this.sniName = endpointMode.equalsIgnoreCase("server") ? sniName : null;
        this.port = endpointMode.equalsIgnoreCase("client") ? port : null;
        this.clientScript = endpointMode.equalsIgnoreCase("client") ? clientScript : null;
        this.strength = strength;
        this.parallelTestcase = parallelTestcase;
        this.timeout = timeout;
        this.disableTcpDump = disableTcpDump;
        this.useDtls = useDtls;
        this.ignoreCache = ignoreCache;
        this.restartAfter = restartAfter;
        this.tags = tags;
        this.testPackage = testPackage;
        this.profiles = profiles;
        this.profileFolder = profileFolder;
        this.expectedResults = compareResults ? expectedResults : null;

        buildTlsAnvilArguments();
    }

    /**
     * Takes the parameters given by the builder and translates them to TLS-Anvil command line arguments.
     */
    private void buildTlsAnvilArguments() {

        ArgumentListBuilder argB = new ArgumentListBuilder();
        argB.add("-strength", String.valueOf(strength));
        argB.add("-parallelTestCases", String.valueOf(parallelTestcase));
        argB.add("-connectionTimeout", String.valueOf(timeout));
        if (disableTcpDump) {
            argB.add("-disableTcpDump");
        }
        if (useDtls) {
            argB.add("-dtls");
        }
        if (ignoreCache) {
            argB.add("-ignoreCache");
        }
        argB.add("-restartTargetAfter", String.valueOf(restartAfter));
        if (!tags.trim().isEmpty()) {
            argB.add("-tags", tags.trim());
        }
        argB.add("-testPackage", testPackage.trim());
        if (expectedResults != null && !expectedResults.trim().isEmpty()) {
            argB.add("-expectedResults");
            argB.add("/data/anvil-expected.json");
        }
        if (profileFolder != null && !profileFolder.trim().isEmpty()) {
            argB.add("-profileFolder", "/data/profiles/");
        }
        if (profiles != null && !profiles.trim().isEmpty()) {
            argB.add("-profiles", profiles);
        }
        argB.add(endpointMode);
        if (endpointMode.equalsIgnoreCase("server")) {
            argB.add("-connect", host);
            if (useSni) {
                argB.add("-server_name", sniName);
            } else {
                argB.add("-doNotSendSNIExtension");
            }
        } else {
            argB.add("-port", String.valueOf(port));
            argB.add("-triggerScript", clientScript);
        }

        args = argB.toList();
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {

        ProcessManager targetProcess = new ProcessManager(launcher, listener);

        try {
            // build directory
            FilePath anvilOut = workspace.child("anvil-out");

            // clean up former builds
            anvilOut.deleteContents();

            // create target process
            if (endpointMode.equalsIgnoreCase("server")
                    && !serverScript.isEmpty()) {
                ArgumentListBuilder targetArguments = new ArgumentListBuilder();
                targetArguments.addTokenized(serverScript);
                if (runOnce) {
                    targetProcess.runProcessOnce(targetArguments.toCommandArray());
                } else {
                    targetProcess.startProcessLoop(targetArguments.toCommandArray());
                }
            }

            // write expected results
            if (expectedResults != null && !expectedResults.trim().isEmpty()) {
                if (expectedResults.trim().startsWith("{")) {
                    workspace.child("anvil-expected.json").write(expectedResults, "UTF-8");
                } else {
                    workspace.child(expectedResults).copyTo(workspace.child("anvil-expected.json"));
                }
            }
            // start TLS-Anvil
            Launcher.ProcStarter anvilProcStarter = launcher.launch();
            ArgumentListBuilder dockerAnvilArgs = new ArgumentListBuilder();
            dockerAnvilArgs.add(DockerTool.getExecutable(null, null, listener, null));
            dockerAnvilArgs.add("run", "--rm", "-t", "--name", "tls-anvil-jenkins", "--network", "host");
            dockerAnvilArgs.add("-v", anvilOut.getRemote() + ":/output/");
            if (expectedResults != null && !expectedResults.isEmpty()) {
                dockerAnvilArgs.add("-v", workspace.child("anvil-expected.json").getRemote() + ":/data/anvil-expected.json");
            }
            if (profiles != null && !profiles.trim().isEmpty()) {
                dockerAnvilArgs.add("-v", workspace.child(profileFolder).getRemote() + ":/data/profiles/");
            }
            dockerAnvilArgs.add("ghcr.io/tls-attacker/tlsanvil:" + ANVIL_VERSION);
            dockerAnvilArgs.add("-outputFolder", "./");
            dockerAnvilArgs.add(args);
            OutputStream anvilErrStream = new ByteArrayOutputStream();
            int anvilResult = anvilProcStarter.cmds(dockerAnvilArgs)
                    .stdout(listener.getLogger())
                    .stderr(anvilErrStream)
                    .start()
                    .join();
            if (!anvilErrStream.toString().trim().isEmpty()) {
                listener.error(anvilErrStream.toString());
            }

            // stop the target process
            if (targetProcess.isProcessRunning()) {
                targetProcess.stopProcess();
            }

            // archive results
            workspace.child("tls-anvil-report.zip").delete();
            try (OutputStream zipOut = workspace.child("tls-anvil-report.zip").write()) {
                anvilOut.zip(zipOut);
            }
            run.getArtifactManager().archive(anvilOut, launcher, new StreamBuildListener(listener.getLogger(), StandardCharsets.UTF_8), Map.of("report.json", "report.json"));
            run.getArtifactManager().archive(workspace, launcher, new StreamBuildListener(listener.getLogger(), StandardCharsets.UTF_8), Map.of("report.zip", "tls-anvil-report.zip"));

            // run AnvilWeb report generator
            ArgumentListBuilder dockerReportGenArgs = new ArgumentListBuilder();
            dockerReportGenArgs.add(DockerTool.getExecutable(null, null, listener, null));
            dockerReportGenArgs.add("run", "--rm", "-t", "--name", "anvil-web-jenkins");
            dockerReportGenArgs.add("-v", anvilOut + ":/input/");
            dockerReportGenArgs.add("-v", anvilOut + "/reportOut/:/output/");
            dockerReportGenArgs.add("ghcr.io/tls-attacker/anvil-web:latest-reportgen");
            OutputStream reportGenErrStream = new ByteArrayOutputStream();
            int reportGenResult = anvilProcStarter.cmds(dockerReportGenArgs)
                    .stdout(listener.getLogger())
                    .stderr(reportGenErrStream)
                    .start()
                    .join();
            if (!reportGenErrStream.toString().trim().isEmpty()) {
                listener.error(reportGenErrStream.toString());
            }
            if (reportGenResult != 0) {
                throw new AbortException("TLS-Anvil: Report generation failed.");
            }

            run.getArtifactManager().archive(anvilOut, launcher, new StreamBuildListener(listener.getLogger(), StandardCharsets.UTF_8), Map.of("report.html", "reportOut/static_report.html"));

            run.addAction(new AnvilReportAction());

            if (anvilResult != 0) {
                throw new AbortException("TLS-Anvil: TLS-Anvil execution returned an error.");
            }

        } catch (InterruptedException e) {
            // stop the target process
            if (targetProcess.isProcessRunning()) {
                targetProcess.stopProcess();
            }
        }

    }

    @Symbol("runTlsAnvil")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public FormValidation doCheckStrength(@QueryParameter int value) {
            if (value < 1 || value > 5) {
                return FormValidation.error("Strength must be between 1 and 5");
            }
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.TlsAnvilRunBuilder_DescriptorImpl_DisplayName();
        }
    }
}

