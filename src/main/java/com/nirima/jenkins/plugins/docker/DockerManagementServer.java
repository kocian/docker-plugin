package com.nirima.jenkins.plugins.docker;

import com.github.dockerjava.api.model.Container;
import com.nirima.jenkins.plugins.docker.utils.Consts;
import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * Created by magnayn on 22/02/2014.
 */
public class DockerManagementServer  implements Describable<DockerManagementServer> {
    final String name;
    final DockerCloud theCloud;

    public Descriptor<DockerManagementServer> getDescriptor() {
        return Hudson.getInstance().getDescriptorByType(DescriptorImpl.class);
    }

    public String getUrl() {
        return DockerManagement.get().getUrlName() + "/server/" + name;
    }

    public DockerManagementServer(String name) {
        this.name = name;
        theCloud = PluginImpl.getInstance().getServer(name);
    }

    public Collection getImages(){
        return theCloud.listImages();
    }

    public Collection getProcesses() {
        return (List<Container>)theCloud.listContainers();
    }

    public String asTime(Long time) {
        if( time == null )
            return "";

        long when = System.currentTimeMillis() - time;

        Date dt = new Date(when);
        return dt.toString();
    }

    public String getJsUrl(String jsName) {
        return Consts.PLUGIN_JS_URL + jsName;
    }

    public void doControlSubmit(@QueryParameter("stopId") String stopId, StaplerRequest req, StaplerResponse rsp) throws ServletException,
            IOException,
            InterruptedException {

        theCloud.stopContainer(stopId);

        rsp.sendRedirect(".");
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<DockerManagementServer> {

        @Override
        public String getDisplayName() {
            return "server ";
        }


    }
}
