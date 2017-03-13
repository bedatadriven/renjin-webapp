package org.renjin.ci.jenkins.benchmark;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.JDK;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.renjin.ci.jenkins.tools.Maven;
import org.renjin.ci.model.PackageVersionId;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes Renjin
 */
public class Renjin extends Interpreter {

  private final String version;
  private String requestedJdk;

  private BlasLibrary blasLibrary;
  private String jdkVersion;

  private JDK jdk;
  private File mavenBinary;

  public Renjin(JDK jdk, BlasLibrary blasLibrary, String renjinVersion) {
    this.version = renjinVersion;
    this.jdk = jdk;
    this.blasLibrary = blasLibrary;
  }

  @Override
  public Map<String, String> getRunVariables() {
    Map<String, String> variables = new HashMap<String, String>();
    variables.put(RunVariables.BLAS, blasLibrary.getName());
    variables.put(RunVariables.BLAS_VERSION, blasLibrary.getNameAndVersion());
    variables.put(RunVariables.JDK, jdkVersion);
    return variables;
  }


  @Override
  public void ensureInstalled(Node node, EnvVars envVars, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {

    blasLibrary.ensureInstalled(node, launcher, listener);

    this.mavenBinary = Maven.findMavenBinary(node, listener, envVars);

    verifyBlasVersion(node, launcher, listener);
    jdkVersion = VersionDetectors.detectJavaVersion(launcher, jdk);
  }

  private boolean atLeast(String version) {
    ArtifactVersion thisVersion = new DefaultArtifactVersion(this.version);
    ArtifactVersion thatVersion = new DefaultArtifactVersion(version);
    return thisVersion.compareTo(thatVersion) >= 0; 
  }

  private void verifyBlasVersion(Node node, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {

    FilePath scriptFile = node.getRootPath().createTempFile("detect-blas", ".R");
    FilePath scriptOutput = node.getRootPath().createTempFile("detect-blas", ".txt");

    StringBuilder script = new StringBuilder();
    if(atLeast("0.8.2142")) {
      script.append("import(com.github.fommil.netlib.LAPACK)\n");
    } else {
      script.append("import(org.netlib.lapack.LAPACK)\n");
    }
    script.append("cat(LAPACK$getInstance()$class$name)\n");
    script.append(BlasDetection.detectionScript(scriptOutput));

    scriptFile.write(script.toString(), Charsets.UTF_8.name());


    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();

      Launcher.ProcStarter ps = runScript(launcher, scriptFile, Collections.<PackageVersionId>emptyList())
              .stdout(baos);

      Proc proc = launcher.launch(ps);
      int exitCode = proc.join();

      String output = new String(baos.toByteArray());

      if (exitCode != 0) {
        listener.getLogger().println(output);
        throw new RuntimeException("Failed to detect BLAS version");
      }

      String loadedBlas = null;

      if (output.contains("Falling back to pure JVM BLAS libraries.") ||
              output.contains("org.netlib.lapack.JLAPACK") ||
              output.contains("com.github.fommil.netlib.F2jBLAS")) {

        loadedBlas = "f2jblas";

      } else if (output.contains("com.github.fommil.netlib.NativeRefBLAS") ||
              output.contains("Using native reference BLAS libraries.")) {

        loadedBlas = "reference";

      } else if (
              output.contains("com.github.fommil.netlib.NativeSystemBLAS") ||
                      output.contains("Using system BLAS libraries.")) {

        loadedBlas = BlasDetection.findSystemBlas(scriptOutput);

      } else {
        loadedBlas = "<unknown>";
      }

      if(!blasLibrary.getName().equals(loadedBlas)) {
        listener.getLogger().println("Failed to detect BLAS library used. Output:");
        listener.getLogger().print(output);
        listener.getLogger().println("Loaded files:");
        listener.getLogger().print(scriptOutput.readToString());
        throw new RuntimeException(blasLibrary.getName() + " was requested, but " + loadedBlas + " was loaded.");
      }

    } finally {
      scriptFile.delete();
      scriptOutput.delete();
    }
  }

  @Override
  public String getId() {
    return "Renjin";
  }

  @Override
  public String getVersion() {
    return version;
  }


  @Override
  public boolean execute(Launcher launcher, TaskListener listener, Node node, FilePath scriptPath, 
                         List<PackageVersionId> dependencies, 
                         boolean dryRun, 
                         long timeoutMillis) throws IOException, InterruptedException {



    Launcher.ProcStarter ps = runScript(launcher, scriptPath, dependencies)
            .stdout(listener);

    Proc proc = launcher.launch(ps);

    int exitCode;
    if (timeoutMillis > 0) {
      exitCode = proc.joinWithTimeout(timeoutMillis, TimeUnit.MILLISECONDS, listener);
    } else {
      exitCode = proc.join();
    }

    listener.getLogger().println("Exit code : " + exitCode);

    return exitCode == 0;

  }


  private Launcher.ProcStarter runScript(Launcher launcher, FilePath scriptPath, List<PackageVersionId> dependencies) throws IOException, InterruptedException {
    Preconditions.checkState(mavenBinary != null);

    BenchmarkPomBuilder pom = new BenchmarkPomBuilder(version, blasLibrary, dependencies);
    FilePath pomFile = scriptPath.getParent().child(scriptPath + ".xml");
    pomFile.write(pom.getXml(), Charsets.UTF_8.name());


    ArgumentListBuilder args = new ArgumentListBuilder();
    args.add(mavenBinary.getAbsolutePath());
    args.add("-B");
    args.add("-q");
    args.add("-f");
    args.add(pomFile.getRemote());
    args.add("exec:java");
    args.add("-Dexec.mainClass=org.renjin.cli.Main");
    args.add("-Dexec.args=-f " + scriptPath.getName());

    Launcher.ProcStarter ps = launcher.new ProcStarter();
    ps = ps.cmds(args).pwd(scriptPath.getParent());

    EnvVars environmentOverrides = new EnvVars();

    if(blasLibrary.getLibraryPath() != null) {
      environmentOverrides.put("LD_LIBRARY_PATH", blasLibrary.getLibraryPath());
    }

    if (jdk != null) {
      jdk.buildEnvVars(environmentOverrides);
    }

    ps = ps.envs(environmentOverrides);

    return ps;
  }
}
