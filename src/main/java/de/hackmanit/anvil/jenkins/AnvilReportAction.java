package de.hackmanit.anvil.jenkins;

import hudson.model.Action;
import hudson.model.Run;
import jenkins.model.RunAction2;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.verb.GET;

import javax.servlet.ServletException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

public class AnvilReportAction implements RunAction2 {

    private transient Run run;

    @Override
    public String getIconFileName() {
        return "document.png";
    }

    @Override
    public String getDisplayName() {
        return "TLS-Anvil Report";
    }

    @Override
    public String getUrlName() {
        return "tls-anvil";
    }

    @Override
    public void onAttached(Run<?, ?> run) {
        this.run = run;
    }

    @Override
    public void onLoad(Run<?, ?> run) {
        this.run = run;
    }

    public Run getRun() {
        return run;
    }

    @GET
    @WebMethod(name = "")
    public HttpResponse getReportHtml() {

        return new HttpResponse() {
            @Override
            public void generateResponse(StaplerRequest req, StaplerResponse rsp, Object node) throws IOException, ServletException {
                rsp.serveFile(
                        req,
                        Path.of(run.getRootDir().getAbsolutePath(), "archive", "report.html").toUri().toURL()
                );
            }
        };
    }
}
