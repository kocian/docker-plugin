package com.nirima.jenkins.plugins.docker;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserListBoxModel;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.github.dockerjava.api.DockerException;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.nirima.docker.client.model.ContainerInspectResponse;
import com.trilead.ssh2.Connection;
import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.model.labels.LabelAtom;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.util.ListBoxModel;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;


public class DockerTemplate extends DockerTemplateBase implements Describable<DockerTemplate> {
    private static final Logger LOGGER = Logger.getLogger(DockerTemplate.class.getName());


    public final String labelString;

    // SSH settings
    /**
     * The id of the credentials to use.
     */
    public final String credentialsId;

    /**
     * Minutes before terminating an idle slave
     */
    public final String idleTerminationMinutes;

    /**
     * Field jvmOptions.
     */
    public final String jvmOptions;

    /**
     * Field javaPath.
     */
    public final String javaPath;

    /**
     * Field prefixStartSlaveCmd.
     */
    public final String prefixStartSlaveCmd;

    /**
     *  Field suffixStartSlaveCmd.
     */
    public final String suffixStartSlaveCmd;

    /**
     *  Field remoteFSMapping.
     */
    public final String remoteFsMapping;

    public final String remoteFs; // = "/home/jenkins";

    public final int instanceCap;

    private transient /*almost final*/ Set<LabelAtom> labelSet;

    private transient DockerCloud dockerCloud;


    @DataBoundConstructor
    public DockerTemplate(String image, String labelString,
                          String remoteFs,
                          String remoteFsMapping,
                          String credentialsId, String idleTerminationMinutes,
                          String jvmOptions, String javaPath,
                          String prefixStartSlaveCmd, String suffixStartSlaveCmd,
                          String instanceCapStr, String dnsString,
                          String dockerCommand,
                          String volumesString, String volumesFrom,
                          String lxcConfString,
                          String hostname,
                          String bindPorts,
                          boolean bindAllPorts,
                          boolean privileged

    ) {
        super(image, dnsString,dockerCommand,volumesString,volumesFrom,lxcConfString,hostname,
                Objects.firstNonNull(bindPorts, "0.0.0.0:22"), bindAllPorts,
                privileged);


        this.labelString = Util.fixNull(labelString);
        this.credentialsId = credentialsId;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.jvmOptions = jvmOptions;
        this.javaPath = javaPath;
        this.prefixStartSlaveCmd = prefixStartSlaveCmd;
        this.suffixStartSlaveCmd = suffixStartSlaveCmd;
        this.remoteFs =  Strings.isNullOrEmpty(remoteFs)?"/home/jenkins":remoteFs;
        this.remoteFsMapping = remoteFsMapping;

        if (instanceCapStr.equals("")) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = Integer.parseInt(instanceCapStr);
        }

        readResolve();
    }

    private String[] splitAndFilterEmpty(String s) {
        List<String> temp = new ArrayList<String>();
        for (String item : s.split(" ")) {
            if (!item.isEmpty())
                temp.add(item);
        }

        return temp.toArray(new String[temp.size()]);

    }

    public String getInstanceCapStr() {
        if (instanceCap==Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(instanceCap);
        }
    }

    public String getDnsString() {
        return Joiner.on(" ").join(dnsHosts);
    }

    public String getVolumesString() {
	return Joiner.on(" ").join(volumes);
    }

    public String getVolumesFrom() {
        return volumesFrom;
    }

    public String getRemoteFsMapping() {
        return remoteFsMapping;
    }

    public Descriptor<DockerTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public Set<LabelAtom> getLabelSet(){
        return labelSet;
    }

    /**
     * Initializes data structure that we don't persist.
     */
    protected Object readResolve() {
        labelSet = Label.parse(labelString);
        return this;
    }

    public String getDisplayName() {
        return "Image of " + image;
    }

    public DockerCloud getDockerCloud() {
        return dockerCloud;
    }

    public void setDockerCloud(DockerCloud cloud) {
        dockerCloud = cloud;
    }

    private int idleTerminationMinutes() {
        if (idleTerminationMinutes == null || idleTerminationMinutes.trim().isEmpty()) {
            return 0;
        } else {
            try {
                return Integer.parseInt(idleTerminationMinutes);
            } catch (NumberFormatException nfe) {
                LOGGER.log(Level.INFO, "Malformed idleTermination value: {0}", idleTerminationMinutes);
                return 30;
            }
        }
    }

    public DockerSlave provision(StreamTaskListener listener) throws IOException, Descriptor.FormException, DockerException {
            PrintStream logger = listener.getLogger();


        logger.println("Launching " + image );

        int numExecutors = 1;
        Node.Mode mode = Node.Mode.NORMAL;

        RetentionStrategy retentionStrategy = new OnceRetentionStrategy(idleTerminationMinutes());

        List<? extends NodeProperty<?>> nodeProperties = new ArrayList();

        ContainerInspectResponse containerInspectResponse = provisionNew();
        String containerId = containerInspectResponse.getId();

        ComputerLauncher launcher = new DockerComputerLauncher(this, containerInspectResponse);

        // Build a description up:
        String nodeDescription = "Docker Node [" + image + " on ";
        try {
            nodeDescription += dockerCloud.getDisplayName();
        } catch(Exception ex)
        {
            nodeDescription += "???";
        }
        nodeDescription += "]";

        String slaveName = containerId.substring(0,12);

        try
        {
            slaveName = slaveName + "@" + dockerCloud.getDisplayName();
        }
        catch(Exception ex) {
            LOGGER.warning("Error fetching name of cloud");
        }

        return new DockerSlave(this, containerId,
                slaveName,
                nodeDescription,
                remoteFs, numExecutors, mode, labelString,
                launcher, retentionStrategy, nodeProperties);

    }

    public ContainerInspectResponse provisionNew() throws DockerException {
        throw new NotImplementedException();

        //DockerClient dockerClient = dockerCloud.getDockerClient();
//        return provisionNew(dockerClient);
        //return null;
    }

    public int getNumExecutors() {
        return 1;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<DockerTemplate> {

        @Override
        public String getDisplayName() {
            return "Docker Template";
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {

            return new SSHUserListBoxModel().withMatching(SSHAuthenticator.matcher(Connection.class),
                    CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class, context,
                            ACL.SYSTEM, SSHLauncher.SSH_SCHEME));
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("image", image)
                .add("parent", dockerCloud)
                .toString();
    }
}
