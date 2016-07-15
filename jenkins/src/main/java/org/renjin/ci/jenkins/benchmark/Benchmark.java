package org.renjin.ci.jenkins.benchmark;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import hudson.FilePath;
import org.renjin.ci.RenjinCiClient;
import org.renjin.ci.model.PackageDependency;
import org.renjin.ci.model.PackageVersionId;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Identifies a single benchmark
 */
public class Benchmark {
  private String name;
  private FilePath filePath;
  
  private List<BenchmarkDataset> datasets = new ArrayList<BenchmarkDataset>();
  private List<PackageDependency> dependencies = new ArrayList<PackageDependency>();
  private List<PackageVersionId> resolvedDependencies = null;

  private Benchmark(String name, FilePath filePath) {
    this.name = name;
    this.filePath = filePath;
  }

  public String getName() {
    return name;
  }
  
  public FilePath getDirectory() {
    return filePath;
  }
  
  public String getLocalName() {
    int lastSlash = name.lastIndexOf('/');
    if(lastSlash == -1) {
      return name;
    } else {
      return name.substring(lastSlash+1);
    }
  }
  
  private static FilePath getDescriptorPath(FilePath filePath) throws IOException, InterruptedException {
    if(filePath.child("BENCHMARK.dcf").exists()) {
      return filePath.child("BENCHMARK.dcf");
    } else {
      return filePath.child("BENCHMARK");
    }
  }
  
  public FilePath getScript() {
    return filePath.child(getLocalName() + ".R");
  }

  public List<PackageVersionId> getDependencies() {
    if(resolvedDependencies == null) {
      resolvedDependencies = RenjinCiClient.resolveDependencies(dependencies);
    }
    return resolvedDependencies;
  }
  
  public static Benchmark read(String namePrefix, FilePath filePath) throws IOException, InterruptedException {
    
    Benchmark benchmark = new Benchmark(namePrefix + filePath.getName(), filePath);
    
    BufferedReader in = new BufferedReader(new InputStreamReader(getDescriptorPath(filePath).read()));
    String line;
    
    List<String> files = Lists.newArrayList();
    List<String> sources = Lists.newArrayList();
    List<String> hash = Lists.newArrayList();
    
    while((line=in.readLine())!=null) {
      if(!line.trim().isEmpty()) {
        String[] keyValue = line.trim().split(":", 2);
        if (keyValue.length != 2) {
          throw new DcfFormatException("Malformed line: " + line);
        }
        String key = keyValue[0].trim();
        String value = keyValue[1].trim();

        if (key.equals("Depends")) {
          Iterables.addAll(benchmark.dependencies, PackageDependency.parseList(value));
          
        } else if (key.equals("File")) {
          files.add(value);
          
        } else if(key.equals("Source")) {
          sources.add(value);
        
        } else if(key.equals("Hash")) {
          hash.add(value);
        }
      }
    }

    for (int i = 0; i < files.size(); i++) {
      benchmark.datasets.add(new BenchmarkDataset(getOrNull(files, i), getOrNull(sources, i), getOrNull(hash, i)));
    }
    
    return benchmark;
  }

  private static String getOrNull(List<String> values, int i) {
    if(i < values.size()) {
      return values.get(i);
    } else {
      return null;
    }
  }


  public List<BenchmarkDataset> getDatasets() {
    return datasets;
  }

  public boolean hasDependencies() {
    return !dependencies.isEmpty();
  }
}
