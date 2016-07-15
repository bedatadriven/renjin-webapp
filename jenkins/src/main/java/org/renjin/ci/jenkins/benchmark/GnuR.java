package org.renjin.ci.jenkins.benchmark;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Node;
import hudson.model.TaskListener;
import org.renjin.ci.model.PackageVersionId;

import java.io.IOException;
import java.util.List;

/**
 * Installs and runs a version of the GNU R interpreter
 */
public class GnuR extends Interpreter {
  

  protected final String version;

  private SourceInstallation installation;
  
  public GnuR(String version) {
    this.version = version;
  }

  @Override
  void ensureInstalled(Node node, Launcher launcher, TaskListener taskListener) throws IOException, InterruptedException {
    installation = new SourceInstallation();
    installation.setInstallPrefix("GNU_R");
    installation.setVersion(version);
    installation.setSourceDirectoryName("R-" + version);
    installation.setSourceUrl(getSourceUrl());
    installation.ensureInstalled(node, launcher, taskListener);
  }

  protected String getSourceUrl() throws AbortException {
    String versionParts[] = version.split("\\.");
    if(versionParts.length != 3) {
      throw new AbortException("Invalid GNU R version: " + version);
    }
    return String.format("https://cran.r-project.org/src/base/R-%s/R-%s.tar.gz", versionParts[0], version);
  }

  @Override
  public String getId() {
    return "GNU R";
  }

  @Override
  public String getVersion() {
    return version;
  }


  @Override
  public boolean execute(Launcher launcher, TaskListener listener,
                         Node node, FilePath runScript, List<PackageVersionId> dependencies, boolean dryRun) throws IOException, InterruptedException {


    RScript rscript = installation.getExecutor();

    LibraryDir libraryDir = new LibraryDir(getId(), version, dependencies);
    libraryDir.ensureInstalled(launcher, listener, node, rscript);
    
    if(dryRun) {
      return true;
    } else {
      int exitCode = rscript.runScript(launcher, libraryDir.getPath(), runScript)
          .start()
          .join();
      return exitCode == 0;
    }
  }

}
