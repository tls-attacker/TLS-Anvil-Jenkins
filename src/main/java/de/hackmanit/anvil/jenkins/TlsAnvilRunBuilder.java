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
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.docker.commons.tools.DockerTool;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class TlsAnvilRunBuilder extends Builder implements SimpleBuildStep {

    private final EndpointConfig endpointConfig;
    private final Integer strength;
    private final Integer parallelTestcase;
    private final Integer timeout;
    private final boolean disableTcpDump;
    private final boolean useDtls;
    private final boolean ignoreCache;
    private final Integer restartAfter;
    private final String tags;
    private final String testPackage;
    private List<String> args;

    @DataBoundConstructor
    public TlsAnvilRunBuilder(EndpointConfig endpointMode, Integer strength,
                              Integer parallelTestcase, Integer timeout, boolean disableTcpDump, boolean useDtls,
                                boolean ignoreCache, Integer restartAfter, String tags, String testPackage) {

        this.endpointConfig = endpointMode;
        this.strength = strength;
        this.parallelTestcase = parallelTestcase;
        this.timeout = timeout;
        this.disableTcpDump = disableTcpDump;
        this.useDtls = useDtls;
        this.restartAfter = restartAfter;
        this.ignoreCache = ignoreCache;
        this.tags = tags;
        this.testPackage = testPackage;

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
        argB.add(endpointConfig.endpointMode);
        if (endpointConfig.endpointMode.equalsIgnoreCase("server")) {
            argB.add("-connect", endpointConfig.host);
            if (endpointConfig.sniConfig.useSni.equalsIgnoreCase("false")) {
                argB.add("-doNotSendSNIExtension");
            } else {
                argB.add("-server_name", endpointConfig.sniConfig.sniName);
            }
        } else {
            argB.add("-port", String.valueOf(endpointConfig.port));
            argB.add("-triggerScript", endpointConfig.clientScript);
        }

        args = argB.toList();
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {

        // build directory
        FilePath anvilOut = workspace.child("anvil-out");

        // clean up former builds
        anvilOut.deleteContents();

        // create target process
        ProcessManager targetProcess = new ProcessManager(launcher, listener);
        if (endpointConfig.endpointMode.equalsIgnoreCase("server")
                && !endpointConfig.serverScript.isEmpty()) {
            ArgumentListBuilder targetArguments = new ArgumentListBuilder();
            targetArguments.addTokenized(endpointConfig.serverScript);
            if (endpointConfig.runOnce) {
                targetProcess.runProcessOnce(targetArguments.toCommandArray());
            } else {
                targetProcess.startProcessLoop(targetArguments.toCommandArray());
            }
        }

        // start TLS-Anvil
        Launcher.ProcStarter anvilProcStarter = launcher.launch();
        ArgumentListBuilder dockerAnvilArgs = new ArgumentListBuilder();
        dockerAnvilArgs.add(DockerTool.getExecutable(null, null, listener, null));
        dockerAnvilArgs.add("run", "--rm", "-t", "--name", "tls-anvil-jenkins", "--network", "host");
        dockerAnvilArgs.add("-v", anvilOut.getRemote()+":/output/");
        dockerAnvilArgs.add("ghcr.io/tls-attacker/tlsanvil:latest");
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
        if (endpointConfig.endpointMode.equalsIgnoreCase("server")
                && !endpointConfig.serverScript.isEmpty()) {
            targetProcess.stopProcess();
        }

        if (anvilResult != 0) {
            throw new AbortException("TLS-Anvil: TLS-Anvil execution failed.");
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
        dockerReportGenArgs.add("-v", anvilOut+":/input/");
        dockerReportGenArgs.add("-v", anvilOut+"/reportOut/:/output/");
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

    }

    // boilerplate ...

    public static class EndpointConfig {

        public final String endpointMode;
        public final String serverScript;
        public final String host;
        public final boolean runOnce;
        public final SniConfig sniConfig;
        public final Integer port;
        public final String clientScript;


        @DataBoundConstructor
        public EndpointConfig(String value, String serverScript, boolean runOnce, String host, Integer port, String clientScript, SniConfig sniExtension) {
            this.endpointMode = value;
            this.serverScript = serverScript;
            this.runOnce = runOnce;
            this.host = host;
            this.port = port;
            this.clientScript = clientScript;
            this.sniConfig = sniExtension;
        }

        public static class SniConfig {

            public String useSni;
            public String sniName;
            @DataBoundConstructor
            public SniConfig(String value, String sniName) {
                this.useSni = value;
                this.sniName = sniName;
            }
        }
    }

    public String getEndpointMode() { return endpointConfig.endpointMode; }
    public String getServerScript() { return endpointConfig.serverScript; }
    public boolean isRunOnce() { return endpointConfig.runOnce; }
    public String getHost() { return endpointConfig.host; }
    public String isSniExtension() { return endpointConfig.sniConfig != null ? endpointConfig.sniConfig.useSni : "false"; }
    public String getSniName() { return endpointConfig.sniConfig != null ? endpointConfig.sniConfig.sniName : ""; }
    public Integer getPort() { return endpointConfig.port; }
    public String getClientScript() { return endpointConfig.clientScript; }
    public Integer getStrength() { return strength; }
    public Integer getParallelTestcase() { return parallelTestcase; }
    public Integer getTimeout() { return timeout; }
    public boolean isDisableTcpDump() { return disableTcpDump; }
    public boolean isUseDtls() { return useDtls; }
    public boolean isIgnoreCache() { return ignoreCache; }
    public Integer getRestartAfter() { return restartAfter; }
    public String getTags() { return tags; }
    public String getTestPackage() { return testPackage; }

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
            return Messages.HelloWorldBuilder_DescriptorImpl_DisplayName();
        }
    }
}

