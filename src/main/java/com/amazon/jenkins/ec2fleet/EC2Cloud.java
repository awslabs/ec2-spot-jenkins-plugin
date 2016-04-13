package com.amazon.jenkins.ec2fleet;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.internal.StaticCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeRegionsResult;
import com.amazonaws.services.ec2.model.DescribeSpotFleetInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeSpotFleetRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotFleetRequestsResult;
import com.amazonaws.services.ec2.model.ExcessCapacityTerminationPolicy;
import com.amazonaws.services.ec2.model.ModifySpotFleetRequestRequest;
import com.amazonaws.services.ec2.model.Region;
import com.amazonaws.services.ec2.model.SpotFleetRequestConfig;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.google.common.util.concurrent.SettableFuture;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.swarm.SwarmSlave;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.springframework.util.ObjectUtils;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * User: cyberax
 * Date: 12/14/15
 * Time: 22:22
 */
@SuppressWarnings("unused")
public class EC2Cloud extends Cloud
{
    public static final String FLEET_CLOUD_ID="FleetCloud";

    private final boolean useInstanceProfileForCredentials;
    private final String accessId;
    private final String secretKey;
    private final String region;
    private final String fleet;
    private final String label;
    private final Integer idleMinutes;
    private final Integer maxSize;
    private @Nonnull FleetStateStats status;

    private final Set<NodeProvisioner.PlannedNode> plannedNodes =
            new HashSet<NodeProvisioner.PlannedNode>();
    private final Set<String> instancesSeen = new HashSet<String>();
    private final Set<String> instancesDying = new HashSet<String>();

    private static final Logger LOGGER = Logger.getLogger(EC2Cloud.class.getName());

    @DataBoundConstructor
    public EC2Cloud(final boolean useInstanceProfileForCredentials, final String accessId,
                    final String secretKey, final String region, final String fleet,
                    final String label, final Integer idleMinutes, final Integer maxSize) {
        super(FLEET_CLOUD_ID);
        this.useInstanceProfileForCredentials = useInstanceProfileForCredentials;
        this.accessId = accessId;
        this.secretKey = secretKey;
        this.region = region;
        this.fleet = fleet;
        this.label = label;
        this.idleMinutes = idleMinutes;
        this.maxSize = maxSize;

        this.status = new FleetStateStats(fleet, 0, "Initializing", Collections.<String>emptySet());
    }

    public boolean isUseInstanceProfileForCredentials() {
        return useInstanceProfileForCredentials;
    }

    public String getAccessId() {
        return accessId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getRegion() {
        return region;
    }

    public String getFleet() {
        return fleet;
    }

    public String getLabel(){
        return label;
    }

    public Integer getIdleMinutes() {
        return idleMinutes;
    }

    public Integer getMaxSize() {
        return maxSize;
    }

    public @Nonnull FleetStateStats getStatus() {
        return status;
    }

    @Override public synchronized Collection<NodeProvisioner.PlannedNode> provision(
            final Label label, final int excessWorkload) {

        final FleetStateStats stats=updateStatus();
        final int maxAllowed = this.getMaxSize();

        if (stats.getNumDesired() >= maxAllowed || !"active".equals(stats.getState()))
            return Collections.emptyList();

        final ModifySpotFleetRequestRequest request=new ModifySpotFleetRequestRequest();
        request.setSpotFleetRequestId(fleet);
        request.setTargetCapacity(stats.getNumDesired() + excessWorkload);

        final AmazonEC2 ec2=connect(useInstanceProfileForCredentials, accessId, secretKey, region);
        ec2.modifySpotFleetRequest(request);

        final List<NodeProvisioner.PlannedNode> resultList =
                new ArrayList<NodeProvisioner.PlannedNode>();
        for(int f=0;f<excessWorkload; ++f)
        {
            final SettableFuture<Node> futureNode=SettableFuture.create();
            final NodeProvisioner.PlannedNode plannedNode=
                    new NodeProvisioner.PlannedNode("FleetNode-"+f, futureNode, 1);
            resultList.add(plannedNode);
            this.plannedNodes.add(plannedNode);
        }
        return resultList;
    }

    public synchronized FleetStateStats updateStatus() {
        final AmazonEC2 ec2=connect(useInstanceProfileForCredentials, accessId, secretKey, region);
        final FleetStateStats curStatus=FleetStateStats.readClusterState(ec2, getFleet());
        status = curStatus;
        LOGGER.log(Level.FINE, "Fleet Update Status called");
        LOGGER.log(Level.FINE, "# of nodes:" + Jenkins.getInstance().getNodes().size());

        // Now update the possible new Swarm nodes
        for(final Node node : Jenkins.getInstance().getNodes()) {
            if (!(node instanceof SwarmSlave)){
                LOGGER.log(Level.FINE, "Node was not a swarm slave, is type: " + node.getClass().getName());
                continue;
            }

            // Split labels and find instance ID
            final String[] labels = node.getLabelString().split(" ");
            String nodeId = null;
            for(String str: labels){
                if(str.startsWith("i-")){
                    nodeId = str;
                }
            }
            
            // Check to make sure instanceID is part of label
            if (nodeId.equals(null)){
                LOGGER.log(Level.INFO, "Node " + node.getDisplayName(), " does not have proper labels");
                continue;
            }
            
            // Check if this is a node from our cluster
            if (!curStatus.getInstances().contains(nodeId)){
                LOGGER.log(Level.INFO, "Node was not part of our cluster:");
                continue;
            }
            // Check for old nodes
            if (instancesSeen.contains(nodeId) || instancesDying.contains(nodeId)){
                LOGGER.log(Level.INFO, "Node was too old or dying");
                continue;
            }

            //A new node, wheee!
            instancesSeen.add(nodeId);
            if (!plannedNodes.isEmpty())
            {
                //If we're waiting for a new node - mark it as ready
                final NodeProvisioner.PlannedNode curNode=plannedNodes.iterator().next();
                plannedNodes.remove(curNode);
                ((SettableFuture<Node>)curNode.future).set(node);
            }

            // Initialize our retention strategy
            if (getIdleMinutes() != null) {
                LOGGER.log(Level.FINE, "Adding policy to node");
                ((SwarmSlave) node).setRetentionStrategy(
                        new IdleRetentionStrategy(getIdleMinutes(), this));
            }
        }

        return curStatus;
    }

    public synchronized void terminateInstance(final String instanceId) {
        LOGGER.log(Level.INFO, "Attempting to terminate instance: " + instanceId);
        if (!instancesSeen.contains(instanceId) || instancesDying.contains(instanceId))
            throw new IllegalStateException("Unknown instance terminated: " + instanceId);

        final FleetStateStats stats=updateStatus();
        //We can't remove the last instance
        if (stats.getNumDesired() == 1 || !"active".equals(stats.getState()))
            return;

        final AmazonEC2 ec2=connect(useInstanceProfileForCredentials, accessId, secretKey, region);

        final ModifySpotFleetRequestRequest request=new ModifySpotFleetRequestRequest();
        request.setSpotFleetRequestId(fleet);
        request.setTargetCapacity(stats.getNumDesired() - 1);
        request.setExcessCapacityTerminationPolicy("NoTermination");
        ec2.modifySpotFleetRequest(request);

        ec2.terminateInstances(new TerminateInstancesRequest(Collections.singletonList(instanceId)));

        //And remove the instance
        instancesSeen.remove(instanceId);
        instancesDying.add(instanceId);
    }

    @Override public boolean canProvision(final Label label) {
        LOGGER.log(Level.FINE, "CanProvision called on fleet:" + this.label + " wanting:" + label.getName());
        return fleet != null && this.label.equals(label.getName());
    }

    private static AmazonEC2 connect(final boolean useInstanceProfileForCredentials,
            final String accessId, final String secretKey, final String region) {

        final AWSCredentialsProvider provider;
        if (useInstanceProfileForCredentials) {
            provider = new InstanceProfileCredentialsProvider();
        } else {
            provider=new StaticCredentialsProvider(new BasicAWSCredentials(accessId, secretKey));
        }

        final AmazonEC2Client client=new AmazonEC2Client(provider);
        if (region != null)
            client.setEndpoint("https://ec2." + region + ".amazonaws.com/");
        return client;
    }


    @Extension
    @SuppressWarnings("unused")
    public static class DescriptorImpl extends Descriptor<Cloud> {
        public boolean useInstanceProfileForCredentials;
        public String accessId;
        public String secretKey;
        public String region;
        public String fleet;

        public DescriptorImpl() {
            super();
            load();
        }

        @Override
        public String getDisplayName() {
            return "Amazon SpotFleet";
        }

        public ListBoxModel doFillRegionItems(@QueryParameter final boolean useInstanceProfileForCredentials,
                                              @QueryParameter final String accessId,
                                              @QueryParameter final String secretKey,
                                              @QueryParameter final String region)
                throws IOException, ServletException {
            final List<Region> regionList;

            try {
                final AmazonEC2 client=connect(useInstanceProfileForCredentials,
                        accessId, secretKey, null);
                final DescribeRegionsResult regions=client.describeRegions();
                regionList=regions.getRegions();
            } catch(final Exception ex)
            {
                //Ignore bad exceptions
                return new ListBoxModel();
            }

            final ListBoxModel model = new ListBoxModel();
            for(final Region reg : regionList) {
                model.add(new ListBoxModel.Option(reg.getRegionName(), reg.getRegionName()));
            }
            return model;
        }

        public ListBoxModel doFillFleetItems(@QueryParameter final String region,
                                             @QueryParameter final boolean useInstanceProfileForCredentials,
                                             @QueryParameter final String accessId,
                                             @QueryParameter final String secretKey,
                                             @QueryParameter final String fleet)
                throws IOException, ServletException {

            final ListBoxModel model = new ListBoxModel();
            try {
                final AmazonEC2 client=connect(useInstanceProfileForCredentials,
                        accessId, secretKey, region);
                String token = null;
                do {
                    final DescribeSpotFleetRequestsRequest req=new DescribeSpotFleetRequestsRequest();
                    req.withNextToken(token);
                    final DescribeSpotFleetRequestsResult result=client.describeSpotFleetRequests(req);
                    for(final SpotFleetRequestConfig config : result.getSpotFleetRequestConfigs()) {
                        final String curFleetId=config.getSpotFleetRequestId();
                        final String displayStr=curFleetId+
                                " ("+config.getSpotFleetRequestState()+")";
                        model.add(new ListBoxModel.Option(displayStr, curFleetId,
                                ObjectUtils.nullSafeEquals(fleet, curFleetId)));
                    }
                    token = result.getNextToken();
                } while(token != null);

            } catch(final Exception ex)
            {
                //Ignore bad exceptions
                return model;
            }

            return model;
        }

        public FormValidation doTestConnection(
                @QueryParameter final boolean useInstanceProfileForCredentials,
                @QueryParameter final String accessId,
                @QueryParameter final String secretKey,
                @QueryParameter final String region,
                @QueryParameter final String fleet)
        {
            try {
                final AmazonEC2 client=connect(useInstanceProfileForCredentials,
                        accessId, secretKey, region);
                client.describeSpotFleetInstances(
                        new DescribeSpotFleetInstancesRequest().withSpotFleetRequestId(fleet));
            } catch(final Exception ex)
            {
                return FormValidation.error(ex.getMessage());
            }
            return FormValidation.ok("Success");
        }

        @Override
        public boolean configure(final StaplerRequest req, final JSONObject formData) throws FormException {
            req.bindJSON(this, formData);
            save();
            return super.configure(req,formData);
        }

        public boolean isUseInstanceProfileForCredentials() {
            return useInstanceProfileForCredentials;
        }

        public String getAccessId() {
            return accessId;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public String getRegion() {
            return region;
        }

        public String getFleet() {
            return fleet;
        }
    }

}
