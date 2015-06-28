package org.renjin.ci.releases;

import com.googlecode.objectify.*;
import org.renjin.ci.datastore.PackageDatabase;
import org.renjin.ci.datastore.RenjinCommit;
import org.renjin.ci.datastore.RenjinRelease;
import org.renjin.ci.model.RenjinVersionId;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Date;

@Path("/releases")
public class ReleasesResource {
  
  
  @GET
  @Path("latest")
  @Produces(MediaType.TEXT_PLAIN)
  public String getLatest() {
    return PackageDatabase.getLatestRelease().toString();
  }
  
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response postNewRelease(@FormParam("renjinVersion") final String renjinVersion, 
                                 @FormParam("buildNumber") final long buildNumber,
                                 @FormParam("sha1") final String commitId) {

    return ObjectifyService.ofy().transact(new Work<Response>() {
      @Override
      public Response run() {

        Key<RenjinRelease> releaseKey = Key.create(RenjinRelease.class, renjinVersion);
        RenjinRelease release = ObjectifyService.ofy().load().key(releaseKey).now();
        if(release != null) {
          return Response.status(Response.Status.BAD_REQUEST)
              .entity("Release exists already")
              .build();
        }

        release = new RenjinRelease();
        release.setVersion(renjinVersion);
        release.setBuildNumber(buildNumber);
        release.setDate(new Date());
        release.setRenjinCommit(Ref.create(Key.create(RenjinCommit.class, commitId)));
        
        ObjectifyService.ofy().save().entity(release);
        
        return Response.ok().build();
      }
    });
  }
  
}
