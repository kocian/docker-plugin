package com.nirima.jenkins.plugins.docker;


import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.DockerException;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import javax.servlet.ServletException;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by magnayn on 08/01/2014.
 */
public class DockerCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(DockerCloud.class.getName());

    public static final String CLOUD_ID_PREFIX = "docker-";

    public final List<DockerTemplate> templates;
    public final String serverUrl;
    public final int containerCap;

    public final int connectTimeout;
    public final int readTimeout;


    private transient DockerClient dockerClient;

    /* Track the count per-AMI identifiers for AMIs currently being
     * provisioned, but not necessarily reported yet by docker.
     */
    private static HashMap<String, Integer> provisioningAmis = new HashMap<String, Integer>();

    @DataBoundConstructor
    public DockerCloud(String name, List<? extends DockerTemplate> templates, String serverUrl, String containerCapStr, int connectTimeout, int readTimeout) {
        super(name);

        Preconditions.checkNotNull(serverUrl);

        this.serverUrl = serverUrl;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.dockerClient = null;

        if( templates != null )
            this.templates = new ArrayList<DockerTemplate>(templates);
        else
            this.templates = new ArrayList<DockerTemplate>();

        if(containerCapStr.equals("")) {
            this.containerCap = Integer.MAX_VALUE;
        } else {
            this.containerCap = Integer.parseInt(containerCapStr);
        }

        readResolve();
    }

    public String getContainerCapStr() {
        if (containerCap==Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(containerCap);
        }
    }

    protected Object readResolve() {
        for (DockerTemplate template : templates)
            template.setDockerCloud(this);
        return this;
    }

    /**
     * Connects to Docker.
     *
     * @return Docker client.
     */

    public List<Container> listContainers() {
        return (List<Container>) dockerClient.listContainersCmd();
    }

    public void stopContainer(String stopId) {
        dockerClient.stopContainerCmd(stopId);
    }

    public List<Image> listImages() {
        return (List<Image>) dockerClient.listImagesCmd();
    }

    public InspectContainerCmd inspectContainer(String containerId) {
        return dockerClient.inspectContainerCmd(containerId);
    }

    public synchronized DockerClient getDockerClient() {
        if (dockerClient != null)
            return dockerClient;

        DockerClientConfig.DockerClientConfigBuilder builder = DockerClientConfig.createDefaultConfigBuilder();

        builder.withUri(serverUrl);

        if (readTimeout > 0)
            builder.withReadTimeout(readTimeout);

        DockerClientConfig config = builder.build();

        dockerClient = DockerClientImpl.getInstance(config);

        return dockerClient;

    }


    /**
     * Decrease the count of slaves being "provisioned".
     */
    private void decrementAmiSlaveProvision(String ami) {
        synchronized (provisioningAmis) {
            int currentProvisioning;
            try {
                currentProvisioning = provisioningAmis.get(ami);
            } catch(NullPointerException npe) {
                return;
            }
            provisioningAmis.put(ami, Math.max(currentProvisioning - 1, 0));
        }
    }

    @Override
    public synchronized Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        try {

            LOGGER.log(Level.INFO, "Excess workload after pending Spot instances: " + excessWorkload);

            List<NodeProvisioner.PlannedNode> r = new ArrayList<NodeProvisioner.PlannedNode>();

            final DockerTemplate t = getTemplate(label);

            while (excessWorkload>0) {

                if (!addProvisionedSlave(t.image, t.instanceCap)) {
                    break;
                }

                r.add(new NodeProvisioner.PlannedNode(t.getDisplayName(),
                        Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                            public Node call() throws Exception {
                                // TODO: record the output somewhere
                                DockerSlave slave = null;
                                try {
                                    slave = t.provision(new StreamTaskListener(System.out));
                                    Jenkins.getInstance().addNode(slave);
                                    // Docker instances may have a long init script. If we declare
                                    // the provisioning complete by returning without the connect
                                    // operation, NodeProvisioner may decide that it still wants
                                    // one more instance, because it sees that (1) all the slaves
                                    // are offline (because it's still being launched) and
                                    // (2) there's no capacity provisioned yet.
                                    //
                                    // deferring the completion of provisioning until the launch
                                    // goes successful prevents this problem.
                                    slave.toComputer().connect(false).get();
                                    return slave;
                                }
                                catch(Exception ex) {
                                    LOGGER.log(Level.SEVERE, "Error in provisioning; slave=" + slave + ", template=" + t);

                                    ex.printStackTrace();
                                    throw Throwables.propagate(ex);
                                }
                                finally {
                                    decrementAmiSlaveProvision(t.image);
                                }
                            }
                        })
                        ,t.getNumExecutors()));

                excessWorkload -= t.getNumExecutors();

            }
            return r;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,"Failed to count the # of live instances on Docker",e);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean canProvision(Label label) {
        return getTemplate(label)!=null;
    }

    public DockerTemplate getTemplate(String template) {
        for (DockerTemplate t : templates) {
            if(t.image.equals(template)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Gets {@link DockerTemplate} that has the matching {@link Label}.
     */
    public DockerTemplate getTemplate(Label label) {
        for (DockerTemplate t : templates) {
            if(label == null || label.matches(t.getLabelSet())) {
                return t;
            }
        }
        return null;
    }

    /**
     * Add a new template to the cloud
     */
    public void addTemplate(DockerTemplate template) {
        this.templates.add(template);
        template.setDockerCloud(this);
    }

    /**
     * Remove a
     * @param t
     */
    public void removeTemplate(DockerTemplate t) {
        this.templates.remove(t);
    }

    /**
     * Counts the number of instances in Docker currently running that are using the specifed image.
     *
     * @param ami If AMI is left null, then all instances are counted.
     * <p>
     * This includes those instances that may be started outside Hudson.
     */
    public int countCurrentDockerSlaves(String ami) throws Exception {

        List<Container> containers = ((List<Container>) dockerClient.listContainersCmd());

        if (ami == null)
            return containers.size();

        List<Image> images = (List<Image>) dockerClient.listImagesCmd();


        LOGGER.log(Level.INFO, "Images found: " + images);

        if (images.size() == 0) {
            // TODO implement that
            throw new NotImplementedException();
            /*
            LOGGER.log(Level.INFO, "Pulling image " + ami + " since one was not found.  This may take awhile...");
            //Identifier amiId = Identifier.fromCompoundString(ami);
            //InputStream imageStream = dockerClient.pullImageCmd(amiId.toString());
            //int streamValue = 0;
            //while (streamValue != -1) {
            //    streamValue = imageStream.read();
            //}
            //imageStream.close();
            LOGGER.log(Level.INFO, "Finished pulling image " + ami);
            */
        }

        //TODO see if its important
/*
        final SearchImagesCmd ir = dockerClient.searchImagesCmd(ami);
        Collection<Container> matching = Collections2.filter(containers, new Predicate<Container>() {
            public boolean apply(@Nullable Container container) {
                InspectContainerResponse cis = dockerClient.inspectContainerCmd(container.getId()).exec();
                return (cis.getImageId().equalsIgnoreCase(ir.getId()));
            }
        });*/
        return 0;
    }

    /**
     * Check not too many already running.
     *
     */
    private synchronized boolean addProvisionedSlave(String ami, int amiCap) throws Exception {
        if( amiCap == 0 )
            return true;

        int estimatedTotalSlaves = countCurrentDockerSlaves(null);
        int estimatedAmiSlaves = countCurrentDockerSlaves(ami);

        synchronized (provisioningAmis) {
            int currentProvisioning;

            for (int amiCount : provisioningAmis.values()) {
                estimatedTotalSlaves += amiCount;
            }
            try {
                currentProvisioning = provisioningAmis.get(ami);
            }
            catch (NullPointerException npe) {
                currentProvisioning = 0;
            }

            estimatedAmiSlaves += currentProvisioning;

            if(estimatedTotalSlaves >= containerCap) {
                LOGGER.log(Level.INFO, "Total container cap of " + containerCap +
                        " reached, not provisioning.");
                return false;      // maxed out
            }

            if (estimatedAmiSlaves >= amiCap) {
                LOGGER.log(Level.INFO, "AMI Instance cap of " + amiCap +
                        " reached for ami " + ami +
                        ", not provisioning.");
                return false;      // maxed out
            }

            LOGGER.log(Level.INFO,
                    "Provisioning for AMI " + ami + "; " +
                            "Estimated number of total slaves: "
                            + String.valueOf(estimatedTotalSlaves) + "; " +
                            "Estimated number of slaves for ami "
                            + ami + ": "
                            + String.valueOf(estimatedAmiSlaves)
            );

            provisioningAmis.put(ami, currentProvisioning + 1);
            return true;
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Docker";
        }

        public FormValidation doTestConnection(
                @QueryParameter URL serverUrl
                ) throws IOException, ServletException, DockerException {
            DockerClient dockerClient = DockerClientImpl.getInstance(serverUrl.toString());

            Version version = dockerClient.versionCmd().exec();

// TODO: check version support?
//            if( version.getVersion()getVersionComponents()[0] < 1 )
//                return FormValidation.error("Docker host is " + version.getVersion() + " which is not supported.");

            return FormValidation.ok("Version = " + version.getVersion());
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("serverUrl", serverUrl)
                .toString();
    }
}
