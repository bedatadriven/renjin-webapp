package org.renjin.ci.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.template.TemplateException;
import org.junit.Test;
import org.renjin.ci.AbstractDatastoreTest;
import org.renjin.ci.datastore.PackageBuild;
import org.renjin.ci.datastore.PackageVersion;
import org.renjin.ci.datastore.PackageVersionDescription;
import org.renjin.ci.model.PackageVersionId;
import org.renjin.ci.model.ResolvedDependencySet;
import org.renjin.ci.tasks.Fixtures;

import java.io.IOException;

import static com.googlecode.objectify.ObjectifyService.ofy;

public class PackageVersionResourceTest  extends AbstractDatastoreTest {

  @Test
  public void test() throws IOException, TemplateException {

    PackageVersionId surveyId = new PackageVersionId("org.renjin.cran", "survey", "3.29-5");
    PackageVersion survey = new PackageVersion(surveyId);

    PackageVersionDescription surveyDescription = 
        new PackageVersionDescription(surveyId, Fixtures.getSurveyPackageDescriptionSource());
    
    ofy().save().entities(survey, surveyDescription).now();

    PackageResource resource = new PackageResource("org.renjin.cran", "survey");
    PackageVersionResource version = resource.getVersion("3.29-5");

    PackageBuild packageBuild = version.startBuild("0.7.1510");

    ObjectMapper objectMapper = new ObjectMapper();
    String json = objectMapper.writeValueAsString(packageBuild);
    
    System.out.println(json);
  }


  @Test
  public void dependencyResolution() throws IOException, TemplateException {
    
    PackageVersionId surveyId = new PackageVersionId("org.renjin.cran", "survey", "3.29-5");
    PackageVersion survey = new PackageVersion(surveyId);

    PackageVersionId ppsId = new PackageVersionId("org.renjin.cran", "pps", "0.94");
    PackageVersion pps = new PackageVersion(ppsId);
    
    PackageVersionDescription ppsDescription =
        new PackageVersionDescription(ppsId, Fixtures.getPpsDescriptionSource());

    ofy().save().entities(survey, pps, ppsDescription).now();

    PackageResource resource = new PackageResource("org.renjin.cran", "pps");
    PackageVersionResource version = resource.getVersion("0.94");


    ResolvedDependencySet resolution = version.resolveDependencies().resolve();
    
    ObjectMapper objectMapper = new ObjectMapper();
    String json = objectMapper.writeValueAsString(resolution);


    System.out.println(json);


  }

}