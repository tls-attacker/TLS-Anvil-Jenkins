package de.hackmanit.anvil.jenkins;

import hudson.Launcher;
import hudson.Proc;
import hudson.model.TaskListener;

import java.io.IOException;

public class ProcessManager {

    private final Launcher launcher;
    private final TaskListener taskListener;
    private Proc process;
    private volatile boolean running = true;

    public ProcessManager(Launcher launcher, TaskListener taskListener) {
        this.launcher = launcher;
        this.taskListener = taskListener;
    }

    public void startProcessLoop(String[] args) {
        new Thread(() -> {
            while (this.running) {
                try {
                    Launcher.ProcStarter ps = this.launcher.launch()
                            .cmds(args);

                    this.process = ps.start();
                    this.process.join(); // Wait for process to exit

                } catch (Exception e) {
                    this.taskListener.getLogger().println("Error starting process: " + e.getMessage());
                }
            }
        }).start();
        this.taskListener.getLogger().println("Started target process loop.");
    }

    public void runProcessOnce(String[] args) {
        try {
            Launcher.ProcStarter ps = this.launcher.launch()
                    .cmds(args);

            this.process = ps.start();
            this.taskListener.getLogger().println("Started target process.");

        } catch (Exception e) {
            this.taskListener.getLogger().println("Error starting target process: " + e.getMessage());
        }
    }

    public void stopProcess() {
        try {
            this.process.kill();
        } catch (IOException | InterruptedException e) {
            this.taskListener.getLogger().println("Error killing target process: " + e.getMessage());
        }
        running = false;
        this.taskListener.getLogger().println("Stopped target process.");
    }
}
