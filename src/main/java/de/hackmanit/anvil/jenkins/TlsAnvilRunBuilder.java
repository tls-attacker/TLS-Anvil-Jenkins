package de.hackmanit.anvil.jenkins;

import hudson.*;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.DirScanner;
import hudson.util.FormValidation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletException;

import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.docker.commons.tools.DockerTool;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class TlsAnvilRunBuilder extends Builder implements SimpleBuildStep {

    private final EndpointConfig endpointConfig;
    private final String strength;
    private final String parallelTestcase;
    private final String timeout;
    private final boolean disableTcpDump;
    private final boolean useDtls;
    private final boolean ignoreCache;
    private final String restartAfter;
    private final String tags;
    private final String testPackage;
    private List<String> args;

    @DataBoundConstructor
    public TlsAnvilRunBuilder(EndpointConfig endpointMode, String strength,
                              String parallelTestcase, String timeout, boolean disableTcpDump, boolean useDtls,
                              boolean ignoreCache, String restartAfter, String tags, String testPackage) {

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

        ArgumentListBuilder argB = new ArgumentListBuilder();
        argB.add("-strength", strength);
        argB.add("-parallelTestCases", parallelTestcase);
        argB.add("-connectionTimeout", timeout);
        if (disableTcpDump) {
            argB.add("-disableTcpDump");
        }
        if (useDtls) {
            argB.add("-dtls");
        }
        if (ignoreCache) {
            argB.add("-ignoreCache");
        }
        argB.add("-restartTargetAfter", restartAfter);
        if (!tags.trim().isEmpty()) {
            argB.add("-tags", tags.trim());
        }
        argB.add("-testPackage", testPackage.trim());
        argB.add(endpointConfig.endpointMode);
        if (endpointConfig.endpointMode.equalsIgnoreCase("server")) {
            argB.add("-connect", endpointConfig.host);
            if (endpointConfig.sniConfig.value.equalsIgnoreCase("true")) {
                argB.add("-doNotSendSNIExtension");
            } else {
                //argB.add("-server_name", endpointConfig.sniConfig.server_name);
            }
        } else {
            //argB.add("-port", endpointConfig.port);
            //argB.add("-triggerScript", endpointConfig.triggerScript);
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

        // start server
        Launcher.ProcStarter procStarter = launcher.launch();


        // create docker process
        Launcher.ProcStarter procStarter = launcher.launch();
        ArgumentListBuilder cmds = new ArgumentListBuilder();
        cmds.add(DockerTool.getExecutable(null, null, listener, null));
        cmds.add("run", "--rm", "-t", "--name", "tls-anvil-jenkins", "--network", "host");
        cmds.add("-v", anvilOut.getRemote()+":/output/");
        cmds.add("ghcr.io/tls-attacker/tlsanvil:latest");
        cmds.add("-outputFolder", "./");
        cmds.add(args);
        OutputStream err = new ByteArrayOutputStream();
        int result = procStarter.cmds(cmds)
                .stdout(listener.getLogger())
                .stderr(err)
                .start()
                .join();
        if (!err.toString().trim().isEmpty()) {
            listener.error(err.toString());
        }
        if (result != 0) {
            throw new AbortException("TLS-Anvil: Report generation failed.");
        }

        // archive results
        workspace.child("tls-anvil-report.zip").delete();
        try (OutputStream zipOut = workspace.child("tls-anvil-report.zip").write()) {
            anvilOut.zip(zipOut);
        }
        run.getArtifactManager().archive(anvilOut, launcher, new StreamBuildListener(listener.getLogger(), StandardCharsets.UTF_8), Map.of("report.json", "report.json"));
        run.getArtifactManager().archive(workspace, launcher, new StreamBuildListener(listener.getLogger(), StandardCharsets.UTF_8), Map.of("report.zip", "tls-anvil-report.zip"));
        // run AnvilWeb report generator
        ArgumentListBuilder cmds2 = new ArgumentListBuilder();
        cmds2.add(DockerTool.getExecutable(null, null, listener, null));
        cmds2.add("run", "--rm", "-t", "--name", "anvil-web-jenkins");
        cmds2.add("-v", anvilOut+":/input/");
        cmds2.add("-v", anvilOut+"/reportOut/:/output/");
        cmds2.add("ghcr.io/tls-attacker/anvil-web:latest-reportgen");
        OutputStream err2 = new ByteArrayOutputStream();
        int result2 = procStarter.cmds(cmds2)
                .stdout(listener.getLogger())
                .stderr(err2)
                .start()
                .join();
        if (!err2.toString().trim().isEmpty()) {
            listener.error(err2.toString());
        }
        if (result2 != 0) {
            throw new AbortException("TLS-Anvil: Report generation failed.");
        }

        run.getArtifactManager().archive(anvilOut, launcher, new StreamBuildListener(listener.getLogger(), StandardCharsets.UTF_8), Map.of("report.html", "reportOut/static_report.html"));

        run.addAction(new AnvilReportAction());

    }

    public class EndpointConfig {

        public final String endpointMode;
        public final String serverScript;
        public final String host;
        public final boolean runOnce;
        public final SniConfig sniConfig;


        @DataBoundConstructor
        public EndpointConfig(String value, String serverScript, boolean runOnce, String host, SniConfig sniExtension) {
            this.endpointMode = value;
            this.serverScript = serverScript;
            this.runOnce = runOnce;
            this.host = host;
            this.sniConfig = sniExtension;
        }

        public String getValue() {
            return endpointMode;
        }

        public String getServerScript() {
            return serverScript;
        }

        public String getHost() {
            return host;
        }

        public boolean getRunOnce() {
            return runOnce;
        }

        public SniConfig getSniExtension() {
            return sniConfig;
        }

        public class SniConfig {

            public String value;
            @DataBoundConstructor
            public SniConfig(String value) {
                this.value = value;
            }

            public String getValue() {
                return value;
            }
        }
    }

    public EndpointConfig getEndpointMode() {
        return endpointConfig;
    }

    public String getStrength() {
        return strength;
    }

    public String getParallelTestcase() {
        return parallelTestcase;
    }

    public String getTimeout() {
        return timeout;
    }

    public boolean isDisableTcpDump() {
        return disableTcpDump;
    }

    public boolean isUseDtls() {
        return useDtls;
    }

    public boolean isIgnoreCache() {
        return ignoreCache;
    }

    public String getRestartAfter() {
        return restartAfter;
    }

    public String getTags() {
        return tags;
    }

    public String getTestPackage() {
        return testPackage;
    }

    public List<String> getArgs() {
        return args;
    }

    @Symbol("runTlsAnvil")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public FormValidation doCheckName(@QueryParameter String value, @QueryParameter boolean useFrench)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error(Messages.HelloWorldBuilder_DescriptorImpl_errors_missingName());
            if (value.length() < 4)
                return FormValidation.warning(Messages.HelloWorldBuilder_DescriptorImpl_warnings_tooShort());
            if (!useFrench && value.matches(".*[éáàç].*")) {
                return FormValidation.warning(Messages.HelloWorldBuilder_DescriptorImpl_warnings_reallyFrench());
            }
            return FormValidation.ok();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            return super.configure(req, json);
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

