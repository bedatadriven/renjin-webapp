package org.renjin.ci.workflow.tools;


import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson.JacksonFactory;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.StorageObject;
import com.google.common.io.Closer;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotPrivateKeyCredentials;
import com.google.jenkins.plugins.credentials.oauth.JsonServiceAccountConfig;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.renjin.ci.storage.StorageKeys;
import org.renjin.ci.workflow.ConfigException;
import org.renjin.ci.workflow.PackageBuildContext;

import java.io.*;
import java.security.GeneralSecurityException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static java.lang.String.format;

public class GoogleCloudStorage {

    public static final String PROJECT_ID = "renjinci";

    private static final Logger LOGGER = Logger.getLogger(GoogleCloudStorage.class.getName());

    /**
     * Fetches credentials from the Google OAuth Plugin.
     * @param context
     */
    public static Credential fetchCredentials(PackageBuildContext context) throws IOException {
        GoogleRobotCredentials credentials = GoogleRobotCredentials.getById(PROJECT_ID);
        if(credentials == null) {
            throw new ConfigException(format("No service key credential available for project %s", PROJECT_ID));
        }
        checkCredentials(credentials);
        
        Credential googleCredential;
        try {
            googleCredential = credentials.getGoogleCredential(new GoogleCloudStorageRequirements());
        } catch (GeneralSecurityException e) {
            context.getLogger().println("ERROR: Exception obtaining credentials for package source repo: " + e.getMessage());
            throw new IOException(e);
        }
        return googleCredential;
    }

    private static void checkCredentials(GoogleRobotCredentials credentials) {
        if(!(credentials instanceof GoogleRobotPrivateKeyCredentials)) {
            throw new ConfigException(format("Expected credentials for project %s [id: %s] to be of type %s, found %s",
                PROJECT_ID, 
                credentials.getId(), 
                GoogleRobotCredentials.class.getName(), 
                credentials.getClass().getName()));
        }
        GoogleRobotPrivateKeyCredentials privateKeyCredentials = (GoogleRobotPrivateKeyCredentials) credentials;
        if(!(privateKeyCredentials.getServiceAccountConfig() instanceof JsonServiceAccountConfig)) {
            throw new ConfigException(format("Expected credentials for project %s [id: %s] to include JSON Key," +
                "found: %s", PROJECT_ID, credentials.getId(), 
                privateKeyCredentials.getServiceAccountConfig().getClass().getName()));
        }
    }

    public static Storage newClient(PackageBuildContext context) throws IOException {
        return new Storage.Builder(new NetHttpTransport(), new JacksonFactory(),
                fetchCredentials(context))
                .setApplicationName("Renjin CI")
                .build();
    }


    private static TarArchiveInputStream fetchSource(PackageBuildContext context) throws IOException {
        Storage storage = GoogleCloudStorage.newClient(context);
        Storage.Objects.Get request = storage.objects().get(
                StorageKeys.PACKAGE_SOURCE_BUCKET,
                StorageKeys.packageSource(context.getPackageVersionId()));

        context.getLogger().println(format("Retrieving sources from gs://%s/%s...", request.getBucket(), request.getObject()));

        try {
            InputStream inputStream = request.executeMediaAsInputStream();
            return new TarArchiveInputStream(new GZIPInputStream(inputStream));
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error downloading sources", e);

            context.getLogger().println(format("ERROR: IOException downloading sources: %s", e.getMessage()));
            throw e;
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error downloading sources", e);

            context.getLogger().println(format("ERROR: %s downloading sources: %s",
                e.getClass().getName(), e.getMessage()));

            throw new IOException(e);
        }
    }


    private static String stripPackageDir(String name) {
        int slash = name.indexOf('/');
        return name.substring(slash+1);
    }


    public static void downloadAndUnpackSources(PackageBuildContext context) throws IOException, InterruptedException {

        Closer closer = Closer.create();
        TarArchiveInputStream tarIn = closer.register(fetchSource(context));
        try {

            TarArchiveEntry entry;
            while((entry=tarIn.getNextTarEntry())!=null) {
                if(entry.isFile()) {
                    context.workspaceChild(stripPackageDir(entry.getName())).copyFrom(tarIn);
                }
            }
        } catch(Exception e) {
            context.getLogger().println("ERROR: Failed to fetch package sources: " + e.getMessage());
            throw closer.rethrow(e);
        } finally {
            closer.close();
        }
    }


    public static void archiveLogFile(PackageBuildContext build) throws IOException, InterruptedException {

        Storage storage = GoogleCloudStorage.newClient(build);

        String objectName = StorageKeys.buildLog(build.getPackageVersionId(), build.getBuildNumber());
        StorageObject objectMetadata = new StorageObject()
                .setName(objectName)
                .setContentType("text/plain")
                .setContentEncoding("gzip");

        LogAction logAction = build.getFlowNode().getAction(LogAction.class);
        File tempFile = File.createTempFile("build", ".log");
        try {
            build.getListener().getLogger().printf("Writing log to temp file: " + tempFile);

            OutputStream out = new GZIPOutputStream(new FileOutputStream(tempFile));
            logAction.getLogText().writeLogTo(0, out);
            out.close();

            Storage.Objects.Insert request = storage.objects().insert(
                    StorageKeys.BUILD_LOG_BUCKET,
                    objectMetadata,
                    new FileContent("text/plain", tempFile));

            request.setPredefinedAcl("publicread");
            request.setContentEncoding("gzip");

            request.execute();
        } finally {
            try {
                boolean deleted = tempFile.delete();
                if(!deleted) {
                    build.getListener().getLogger().println("Failed to remove temporary log file");
                }
            } catch (Exception e) {
                build.getListener().getLogger().println("Exception removing temporary log file: " + e.getMessage());
            }
        }

        build.getListener().getLogger().print("Archived build log to ");
        build.getListener().hyperlink(StorageKeys.buildLogUrl(build.getPackageBuild().getId()), StorageKeys.PACKAGE_SOURCE_BUCKET);
        build.getListener().getLogger().println();

    }

}
