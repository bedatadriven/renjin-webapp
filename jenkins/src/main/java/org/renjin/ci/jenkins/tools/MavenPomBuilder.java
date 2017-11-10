package org.renjin.ci.jenkins.tools;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import hudson.FilePath;
import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.oro.io.GlobFilenameFilter;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.renjin.ci.RenjinCiClient;
import org.renjin.ci.build.PackageBuild;
import org.renjin.ci.jenkins.graph.PackageNode;
import org.renjin.ci.model.*;
import org.renjin.ci.model.PackageDescription.Person;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Constructs a Maven Project Object Model (POM) from a GNU-R style
 * package folder and DESCRIPTION file.
 *
 */
public class MavenPomBuilder {

  public static final String[] DEFAULT_PACKAGES = new String[]{
      "methods", "stats", "utils", "grDevices", "graphics", "datasets"};

  private final PackageDescription description;
  private PackageNode packageNode;

  private RenjinVersionId renjinVersionId;
  private String buildVersion;
  private FilePath buildDir;

  public MavenPomBuilder(PackageBuild build, PackageDescription packageDescription, PackageNode packageNode, FilePath buildDir) {
    this.description = packageDescription;
    this.packageNode = packageNode;
    this.renjinVersionId = build.getRenjinVersionId();
    this.buildVersion = build.getBuildVersion();
    this.buildDir = buildDir;
  }

  public MavenPomBuilder(RenjinVersionId renjinVersionId, PackageDescription packageDescription, PackageNode packageNode, FilePath buildDir) {
    this.description = packageDescription;
    this.packageNode = packageNode;
    this.renjinVersionId = renjinVersionId;
    this.buildVersion = packageDescription.getVersion();
    this.buildDir = buildDir;
  }


  private Model buildPom() throws IOException {

    Model model = new Model();
    model.setModelVersion("4.0.0");
    model.setArtifactId(description.getPackage());
    model.setGroupId(packageNode.getId().getGroupId());
    model.setVersion(buildVersion);
    model.setDescription(description.getDescription());
    model.setUrl(description.getUrl());

    if(!Strings.isNullOrEmpty(description.getLicense())) {
      License license = new License();
      license.setName(description.getLicense());
      model.addLicense(license);
    }

    for(Person author : description.getAuthors()) {
      Developer developer = new Developer();
      developer.setName(author.getName());
      developer.setEmail(author.getEmail());
      model.addDeveloper(developer);
    }
    
    Set<String> linkingTo = Sets.newHashSet();
    for (PackageDependency linkTimeDependency : description.getLinkingTo()) {
      linkingTo.add(linkTimeDependency.getName());
    }

    Set<String> runtimeDependencies = dependencies();
    
    for(String dependencyName : runtimeDependencies) {
      if(!CorePackages.IGNORED_PACKAGES.contains(dependencyName)) {
        
        if(CorePackages.isPartOfRenjin(dependencyName)) {
          addCoreModule(model, dependencyName);
        
        } else {
          PackageNode dependencyNode = packageNode.getDependency(dependencyName);
          if(dependencyNode.getBuildResult().getOutcome() != BuildOutcome.SUCCESS) {
            throw new RuntimeException("Cannot build due to upstream failure of " + dependencyName);
          }

          Dependency mavenDep = new Dependency();
          mavenDep.setGroupId(dependencyNode.getId().getGroupId());
          mavenDep.setArtifactId(dependencyNode.getId().getPackageName());
          mavenDep.setVersion(dependencyNode.getBuildResult().getBuildVersion());
          model.addDependency(mavenDep);

          if (linkingTo.contains(dependencyName)) {
            Dependency headerDep = new Dependency();
            headerDep.setGroupId(dependencyNode.getId().getGroupId());
            headerDep.setArtifactId(dependencyNode.getId().getPackageName());
            headerDep.setVersion(dependencyNode.getBuildResult().getBuildVersion());
            headerDep.setClassifier("headers");
            headerDep.setScope("provided");
            model.addDependency(headerDep);
          }
        }
      }
    }

    // Compiler package may needed during namespace evaluation,
    // but unless explicitly imported, not at runtime
    if(RenjinCapabilities.hasCompiler(renjinVersionId)) {
      if (!runtimeDependencies.contains("compiler")) {
        addCoreModule(model, "compiler", "provided");
      }
    }

    if(hasCxxSources()) {
      Dependency dependency = new Dependency();
      dependency.setGroupId("org.renjin");
      dependency.setArtifactId("libstdcxx");
      dependency.setVersion(RenjinCiClient.getSystemRequirementVersion("libstdcxx").get());
      model.addDependency(dependency);
    }

    // If this package uses testthat, add the package to the test scope
    if(usesTestThat()) {
      model.addDependency(testThatDependency());
    }

    Plugin renjinPlugin = new Plugin();
    renjinPlugin.setGroupId("org.renjin");
    renjinPlugin.setArtifactId("renjin-maven-plugin");
    renjinPlugin.setVersion(renjinVersionId.toString());

    if(RenjinCapabilities.hasGnurBuild(renjinVersionId)) {
      renjinPlugin.addExecution(gnurBuildExecution());
    } else {
      PluginExecution compileExecution = compileExecution();
      if (RenjinCapabilities.hasUnpackJars(renjinVersionId) && hasJava()) {
        renjinPlugin.addExecution(javaExecution());
      }
      renjinPlugin.addExecution(compileExecution);
      renjinPlugin.addExecution(legacyCompileExecution());
    }
    
    // Run package tests
    renjinPlugin.addExecution(testExecution());

    Build build = new Build();
    build.addPlugin(renjinPlugin);

    DeploymentRepository deploymentRepo = new DeploymentRepository();
    deploymentRepo.setId("renjin-packages");
    deploymentRepo.setUrl("https://nexus.bedatadriven.com/content/repositories/renjin-packages");
    deploymentRepo.setName("Renjin CI Repository");

    DistributionManagement distributionManagement = new DistributionManagement();
    distributionManagement.setRepository(deploymentRepo);

    List<Repository> repos = new ArrayList<Repository>();

    Repository publicRepo = new Repository();
    publicRepo.setId("bedatadriven-public");
    publicRepo.setUrl("https://nexus.bedatadriven.com/content/groups/public/");
    repos.add(publicRepo);

    if(renjinVersionId.isPullRequest()) {
      Repository pullRepo = new Repository();
      pullRepo.setId("renjin-pr");
      pullRepo.setUrl("https://nexus.bedatadriven.com/content/repositories/renjin-pull-requests/");
      repos.add(pullRepo);
    }

    model.setDistributionManagement(distributionManagement);
    model.setBuild(build);
    model.setRepositories(Lists.newArrayList(repos));
    model.setPluginRepositories(Lists.newArrayList(repos));

    return model;
  }

  private boolean hasCxxSources() throws IOException {
    try {
      return !buildDir.child("src").list(new GlobFilenameFilter("*.cpp")).isEmpty();
    } catch (InterruptedException e) {
      throw new IOException("Interrupted");
    }
  }


  public static Dependency testThatDependency() {
    Dependency dependency = new Dependency();
    dependency.setGroupId("org.renjin.cran");
    dependency.setArtifactId("testthat");
    dependency.setVersion("1.0.2-renjin-14");
    dependency.setScope("test");
    return dependency;
  }

  private boolean usesTestThat() {
    for (PackageDependency packageDependency : description.getSuggests()) {
      if(packageDependency.getName().equals("testthat")) {
        return true;
      }
    }
    return false;
  }


  private boolean hasJava() {
    Iterable<PackageDependency> deps = Iterables.concat(
        description.getDepends(), 
        description.getImports(), 
        description.getSuggests());

    for (PackageDependency dep : deps) {
      if(dep.getName().equals("rJava")) {
        return true;
      }
    }
    return false;
  }

  private Set<String> dependencies() {
    Set<String> included = new HashSet<String>();

    // Add all "core" packages, it seems to be legal to import from these packages
    // without explicitly declaring them in the DESCRIPTION file
    included.addAll(CorePackages.CORE_PACKAGES);

    // Add the packages specified in the Imports and Depends fields of the DESCRIPTION file
    for (PackageDependency packageDep : Iterables.concat(
        description.getDepends(),
        description.getImports(),
        description.getLinkingTo())) {
      
      included.add(packageDep.getName());
    }
    return included;
  }


  private PluginExecution compileExecution() {
    PluginExecution compileExecution = new PluginExecution();
    compileExecution.setId("renjin-compile");
    compileExecution.addGoal("namespace-compile");
    compileExecution.setPhase("process-classes");

    Xpp3Dom sourceDirectory = new Xpp3Dom("sourceDirectory");
    sourceDirectory.setValue("${basedir}/R");

    Xpp3Dom dataDirectory = new Xpp3Dom("dataDirectory");
    dataDirectory.setValue("${basedir}/data");

    Xpp3Dom defaultPackages = new Xpp3Dom("defaultPackages");

    for(String name : DEFAULT_PACKAGES) {
      Xpp3Dom pkg = new Xpp3Dom("package");
      pkg.setValue(name);
      defaultPackages.addChild(pkg);
    }
    
    for(PackageDependency dep : description.getDepends()) {
      if(!dep.getName().equals("R") && !CorePackages.DEFAULT_PACKAGES.contains(dep.getName())) {
        Xpp3Dom pkg = new Xpp3Dom("pkg");
        pkg.setValue(dep.getName());
        defaultPackages.addChild(pkg);
      }
    }

    Xpp3Dom configuration = new Xpp3Dom("configuration");
    configuration.addChild(sourceDirectory);
    if(description.getCollate().isPresent()) {
      configuration.addChild(sourceFiles());
    }
    configuration.addChild(dataDirectory);
    configuration.addChild(defaultPackages);
    compileExecution.setConfiguration(configuration);

    return compileExecution;
  }

  private Xpp3Dom sourceFiles() {
    Xpp3Dom sourceFiles = new Xpp3Dom("sourceFiles");
    for (String filename : description.getCollate().get()) {
      Xpp3Dom sourceFile = new Xpp3Dom("sourceFile");
      sourceFile.setValue(filename);
      sourceFiles.addChild(sourceFile);
    }
    return sourceFiles;
  }

  private PluginExecution gnurBuildExecution() {
    PluginExecution buildExecution = new PluginExecution();
    buildExecution.setId("compile");
    buildExecution.addGoal("gnur-compile");
    buildExecution.setPhase("compile");
    
    return buildExecution;
  }
  
  private PluginExecution legacyCompileExecution() {
    if(RenjinCapabilities.hasMake(renjinVersionId)) {
      PluginExecution compileExecution = new PluginExecution();
      compileExecution.setId("gnur-compile");
      compileExecution.addGoal("make-gnur-sources");
      compileExecution.setPhase("compile");

      return compileExecution;
    
    } else {
      PluginExecution compileExecution = new PluginExecution();
      compileExecution.setId("gnur-compile");
      compileExecution.addGoal("gnur-sources-compile");
      compileExecution.setPhase("compile");

      Xpp3Dom sourceDirectory = new Xpp3Dom("sourceDirectory");
      sourceDirectory.setValue("${basedir}/src");

      Xpp3Dom sourceDirectories = new Xpp3Dom("sourceDirectories");
      sourceDirectories.addChild(sourceDirectory);

      Xpp3Dom configuration = new Xpp3Dom("configuration");
      configuration.addChild(sourceDirectories);

      compileExecution.setConfiguration(configuration);

      return compileExecution;
    }
  }

  public static PluginExecution testExecution() {
    PluginExecution testExecution = new PluginExecution();
    testExecution.setId("renjin-test");
    testExecution.addGoal("test");
    testExecution.setPhase("test");

    Xpp3Dom testSourceDirectory = new Xpp3Dom("testSourceDirectory");
    testSourceDirectory.setValue("${basedir}/tests");
    
    Xpp3Dom timeout = new Xpp3Dom("timeoutInSeconds");
    timeout.setValue("30");

    Xpp3Dom defaultPackages = new Xpp3Dom("defaultPackages");
    for(String defaultPackage : DEFAULT_PACKAGES) {
      Xpp3Dom pkg = new Xpp3Dom("package");
      pkg.setValue(defaultPackage);
      defaultPackages.addChild(pkg);
    }

    Xpp3Dom configuration = new Xpp3Dom("configuration");
    configuration.addChild(timeout);
    configuration.addChild(testSourceDirectory);
    configuration.addChild(defaultPackages);

    testExecution.setConfiguration(configuration);

    return testExecution;
  }


  private PluginExecution javaExecution() {
    PluginExecution execution = new PluginExecution();
    execution.setId("renjin-jars");
    execution.addGoal("merge-gnur-jars");
    execution.setPhase("process-classes");
    
    return execution;
  }

  private void addCoreModule(Model model, String name) {
    addCoreModule(model, name, null);
  }

  private void addCoreModule(Model model, String name, String scope) {
    Dependency mavenDep = new Dependency();
    mavenDep.setGroupId("org.renjin");
    mavenDep.setArtifactId(name);
    mavenDep.setVersion(renjinVersionId.toString());
    mavenDep.setScope(scope);
    model.addDependency(mavenDep);
  }

  public static String toXml(Model pom) {
    try {
      StringWriter fileWriter = new StringWriter();
      MavenXpp3Writer writer = new MavenXpp3Writer();
      writer.write(fileWriter, pom);
      fileWriter.close();
      return fileWriter.toString();
    } catch(Exception e) {
      throw new RuntimeException(e);
    }
  }

  public String getXml() throws IOException {
    return toXml(buildPom());
  }
}
