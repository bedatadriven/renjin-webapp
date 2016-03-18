package org.renjin.ci.jenkins.benchmark;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;

import java.io.IOException;

/**
 * Common interface to R Interpreters
 */
public abstract class Interpreter {
  abstract void ensureInstalled(Node node, Launcher launcher, TaskListener taskListener) throws IOException, InterruptedException;

  public abstract String getId();
  
  public abstract String getVersion();
  
  public abstract boolean execute(Launcher launcher, TaskListener listener, FilePath runScript) throws IOException, InterruptedException;
  
  protected final boolean execute(Launcher launcher, TaskListener listener, FilePath exeFile, FilePath scriptPath) 
      throws IOException, InterruptedException {

    ArgumentListBuilder args = new ArgumentListBuilder();
    args.add(exeFile.getRemote());
    args.add("-f");
    args.add(scriptPath.getName());

    Launcher.ProcStarter ps = launcher.new ProcStarter();
    ps = ps.cmds(args).pwd(scriptPath.getParent()).stdout(listener);

    Proc proc = launcher.launch(ps);
    int exitCode = proc.join();

    listener.getLogger().println("Exit code : " + exitCode);

    return exitCode == 0;
  }
}
