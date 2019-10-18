package com.amazon.jenkins.ec2fleet;

import com.amazon.jenkins.ec2fleet.fleet.EC2Fleet;
import com.amazon.jenkins.ec2fleet.fleet.EC2Fleets;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeRegionsResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Region;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsHelper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.SettableFuture;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerConnector;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * @see CloudNanny
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class EC2FleetCloud extends Cloud {

    public static final String EC2_INSTANCE_TAG_NAMESPACE = "ec2-fleet-plugin";
    public static final String EC2_INSTANCE_CLOUD_NAME_TAG = EC2_INSTANCE_TAG_NAMESPACE + ":cloud-name";

    public static final String FLEET_CLOUD_ID = "FleetCloud";

    public static final int DEFAULT_CLOUD_STATUS_INTERVAL_SEC = 10;

    private static final int DEFAULT_INIT_ONLINE_TIMEOUT_SEC = 3 * 60;
    private static final int DEFAULT_INIT_ONLINE_CHECK_INTERVAL_SEC = 15;

    private static final SimpleFormatter sf = new SimpleFormatter();
    private static final Logger LOGGER = Logger.getLogger(EC2FleetCloud.class.getName());

    /**
     * Provide unique identifier for this instance of {@link EC2FleetCloud}, <code>transient</code>
     * will not be stored. Not available for customer, instead use {@link EC2FleetCloud#name}
     * will be used only during Jenkins configuration update <code>config.jelly</code>,
     * when new instance of same cloud is created and we need to find old instance and
     * repoint resources like {@link Computer} {@link Node} etc.
     * <p>
     * It's lazy to support old versions which don't have this field at all.
     * <p>
     * However it's stable, as soon as it will be created and called first uuid will be same
     * for all future calls to the same instances of lazy uuid.
     *
     * @see EC2FleetCloudAware
     */
    private transient LazyUuid id;

    /**
     * Replaced with {@link EC2FleetCloud#awsCredentialsId}
     * <p>
     * Plugin is using {@link EC2FleetCloud#computerConnector} for node connection credentials
     * on UI it's introduced same field <code>credentialsId</code> as we, so it's undefined
     * which field will be use for operations from UI.
     * <p>
     * As result we need to rename this field.
     * <p>
     * However we keep old one to be able load stored data for already installed plugins.
     * https://wiki.jenkins.io/display/JENKINS/Hint+on+retaining+backward+compatibility
     * <p>
     * Will be deleted in future when usage for old version <= 1.1.9 will be totally dropped.
     */
    @Deprecated
    private final String credentialsId;

    private final String awsCredentialsId;
    private final String region;
    private final String endpoint;

    /**
     * In fact fleet ID, not name or anything else
     */
    private final String fleet;
    private final String fsRoot;
    private final ComputerConnector computerConnector;
    private final boolean privateIpUsed;
    private final boolean alwaysReconnect;
    private final String labelString;
    private final Integer idleMinutes;
    private final Integer minSize;
    private final Integer maxSize;
    private final Integer numExecutors;
    private final boolean restrictUsage;
    private final boolean scaleExecutorsByWeight;
    private final Integer initOnlineTimeoutSec;
    private final Integer initOnlineCheckIntervalSec;
    private final Integer cloudStatusIntervalSec;

    /**
     * @see EC2FleetAutoResubmitComputerLauncher
     */
    private final boolean disableTaskResubmit;

    /**
     * @see NoDelayProvisionStrategy
     */
    private final boolean noDelayProvision;

    /**
     * {@link EC2FleetCloud#update()} updating this field, this is one thread
     * related to {@link CloudNanny}. At the same time {@link IdleRetentionStrategy}
     * call {@link EC2FleetCloud#scheduleToTerminate(String)} to stop instance when it free
     * and use this field to know what capacity is current one.
     * <p>
     * It could be situation that <code>stats</code> is outdated and plugin will make wrong decision,
     * however refresh time is low and probability of this event is low. We preferred to reduce amount of calls
     * to API EC2 and increase plugin performance versus be precise. Any way outdated will be fixed after next update.
     */
    private transient FleetStateStats stats;

    private transient int toAdd;

    private transient Set<String> instanceIdsToTerminate;

    private transient Set<NodeProvisioner.PlannedNode> plannedNodesCache;

    @DataBoundConstructor
    public EC2FleetCloud(final String name,
                         final String oldId,
                         final String awsCredentialsId,
                         final @Deprecated String credentialsId,
                         final String region,
                         final String endpoint,
                         final String fleet,
                         final String labelString,
                         final String fsRoot,
                         final ComputerConnector computerConnector,
                         final boolean privateIpUsed,
                         final boolean alwaysReconnect,
                         final Integer idleMinutes,
                         final Integer minSize,
                         final Integer maxSize,
                         final Integer numExecutors,
                         final boolean restrictUsage,
                         final boolean disableTaskResubmit,
                         final Integer initOnlineTimeoutSec,
                         final Integer initOnlineCheckIntervalSec,
                         final boolean scaleExecutorsByWeight,
                         final Integer cloudStatusIntervalSec,
                         final boolean noDelayProvision) {
        super(StringUtils.isBlank(name) ? FLEET_CLOUD_ID : name);
        init();
        this.credentialsId = credentialsId;
        this.awsCredentialsId = awsCredentialsId;
        this.region = region;
        this.endpoint = endpoint;
        this.fleet = fleet;
        this.fsRoot = fsRoot;
        this.computerConnector = computerConnector;
        this.labelString = labelString;
        this.idleMinutes = idleMinutes;
        this.privateIpUsed = privateIpUsed;
        this.alwaysReconnect = alwaysReconnect;
        this.minSize = minSize;
        this.maxSize = maxSize;
        this.numExecutors = numExecutors;
        this.restrictUsage = restrictUsage;
        this.scaleExecutorsByWeight = scaleExecutorsByWeight;
        this.disableTaskResubmit = disableTaskResubmit;
        this.initOnlineTimeoutSec = initOnlineTimeoutSec;
        this.initOnlineCheckIntervalSec = initOnlineCheckIntervalSec;
        this.cloudStatusIntervalSec = cloudStatusIntervalSec;
        this.noDelayProvision = noDelayProvision;

        if (StringUtils.isNotEmpty(oldId)) {
            // existent cloud was modified, let's re-assign all dependencies of old cloud instance
            // to new one
            EC2FleetCloudAwareUtils.reassign(oldId, this);
        }
    }

    public boolean isNoDelayProvision() {
        return noDelayProvision;
    }

    /**
     * See {@link EC2FleetCloud#awsCredentialsId} documentation. Don't use fields directly to be able
     * get old version of plugin and for new.
     *
     * @return credentials ID
     */
    public String getAwsCredentialsId() {
        return StringUtils.isNotBlank(awsCredentialsId) ? awsCredentialsId : credentialsId;
    }

    /**
     * Called old as will be used by new instance of cloud, for
     * which this id is old (not current)
     *
     * @return id of current cloud
     */
    public String getOldId() {
        return id.getValue();
    }

    public boolean isDisableTaskResubmit() {
        return disableTaskResubmit;
    }

    public int getInitOnlineTimeoutSec() {
        return initOnlineTimeoutSec == null ? DEFAULT_INIT_ONLINE_TIMEOUT_SEC : initOnlineTimeoutSec;
    }

    public int getCloudStatusIntervalSec() {
        return cloudStatusIntervalSec == null ? DEFAULT_CLOUD_STATUS_INTERVAL_SEC : cloudStatusIntervalSec;
    }

    public int getInitOnlineCheckIntervalSec() {
        return initOnlineCheckIntervalSec == null ? DEFAULT_INIT_ONLINE_CHECK_INTERVAL_SEC : initOnlineCheckIntervalSec;
    }

    public String getRegion() {
        return region;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getFleet() {
        return fleet;
    }

    public String getFsRoot() {
        return fsRoot;
    }

    public ComputerConnector getComputerConnector() {
        return computerConnector;
    }

    public boolean isPrivateIpUsed() {
        return privateIpUsed;
    }

    public boolean isAlwaysReconnect() {
        return alwaysReconnect;
    }

    public String getLabelString() {
        return labelString;
    }

    public int getIdleMinutes() {
        return (idleMinutes != null) ? idleMinutes : 0;
    }

    public Integer getMaxSize() {
        return maxSize;
    }

    public Integer getMinSize() {
        return minSize;
    }

    public Integer getNumExecutors() {
        return numExecutors;
    }

    public boolean isScaleExecutorsByWeight() {
        return scaleExecutorsByWeight;
    }

    public String getJvmSettings() {
        return "";
    }

    public boolean isRestrictUsage() {
        return restrictUsage;
    }

    @VisibleForTesting
    synchronized Set<String> getInstanceIdsToTerminate() {
        return instanceIdsToTerminate;
    }

    @VisibleForTesting
    synchronized int getToAdd() {
        return toAdd;
    }

    @VisibleForTesting
    synchronized FleetStateStats getStats() {
        return stats;
    }

    @VisibleForTesting
    synchronized void setStats(final FleetStateStats stats) {
        this.stats = stats;
    }

    @Override
    public synchronized Collection<NodeProvisioner.PlannedNode> provision(final Label label, final int excessWorkload) {
        info("excessWorkload %s", excessWorkload);

        if (stats == null) {
            info("No first update, skip provision");
            return Collections.emptyList();
        }

        final int cap = stats.getNumDesired() + toAdd;

        if (cap >= getMaxSize()) {
            info("max %s reached, no more provision", getMaxSize());
            return Collections.emptyList();
        }

        if (!"active".equals(stats.getState())) {
            info("fleet in %s not active state", stats.getState());
            return Collections.emptyList();
        }

        // if the planned node has 0 executors configured force it to 1 so we end up doing an unweighted check
        final int numExecutors1 = this.numExecutors == 0 ? 1 : this.numExecutors;

        // Calculate the ceiling, without having to work with doubles from Math.ceil
        // https://stackoverflow.com/a/21830188/877024
        final int weightedExcessWorkload = (excessWorkload + numExecutors1 - 1) / numExecutors1;
        int targetCapacity = Math.min(cap + weightedExcessWorkload, getMaxSize());

        int toProvision = targetCapacity - cap;
        info("to provision = %s", toProvision);

        if (toProvision < 1) return Collections.emptyList();

        toAdd += toProvision;

        final List<NodeProvisioner.PlannedNode> resultList = new ArrayList<>();
        for (int f = 0; f < toProvision; ++f) {
            // todo make name unique per fleet
            final NodeProvisioner.PlannedNode plannedNode = new NodeProvisioner.PlannedNode(
                    "FleetNode-" + f, SettableFuture.<Node>create(), this.numExecutors);
            resultList.add(plannedNode);
            plannedNodesCache.add(plannedNode);
        }
        return resultList;
    }

    /**
     * Perform sync of plugin data with EC2 Spot Fleet state.
     *
     * @return current state
     */
    public FleetStateStats update() {
        info("start");

        final int currentToAdd;
        final Set<String> currentInstanceIdsToTerminate;

        // make snapshot of current state to work with
        // this method should always work with snapshot
        // as data could be modified
        synchronized (this) {
            currentToAdd = toAdd;
            currentInstanceIdsToTerminate = new HashSet<>(instanceIdsToTerminate);
        }

        final Jenkins jenkins = Jenkins.getInstance();

        final AmazonEC2 ec2 = Registry.getEc2Api().connect(getAwsCredentialsId(), region, endpoint);

        if (currentToAdd > 0 || currentInstanceIdsToTerminate.size() > 0) {
            final int targetCapacity = stats.getNumDesired() - currentInstanceIdsToTerminate.size() + toAdd;
            // we do update any time even real capacity was not update like remove one add one to
            // update fleet settings with NoTermination so we can terminate instances on our own
            EC2Fleets.get(fleet).modify(
                    getAwsCredentialsId(), region, endpoint, fleet, targetCapacity);
            info("Update fleet target capacity to %s", targetCapacity);
        }

        if (currentInstanceIdsToTerminate.size() > 0) {
            // internally removeNode lock on queue to correctly update node list
            // we do big block for all removal to avoid delay on lock waiting
            // for each node
            Queue.withLock(new Runnable() {
                @Override
                public void run() {
                    for (final String instanceId : currentInstanceIdsToTerminate) {
                        final Node node = jenkins.getNode(instanceId);
                        if (node != null) {
                            try {
                                jenkins.removeNode(node);
                            } catch (IOException e) {
                                warning("unable remove node %s from Jenkins, skip, just terminate EC2 instance", instanceId);
                            }
                        }
                    }
                }
            });
            info("Delete terminating nodes from Jenkins %s", currentInstanceIdsToTerminate);

            Registry.getEc2Api().terminateInstances(ec2, currentInstanceIdsToTerminate);
            info("Instances %s were terminated with result", currentInstanceIdsToTerminate);
        }

        final FleetStateStats currentStats = EC2Fleets.get(fleet).getState(
                getAwsCredentialsId(), region, endpoint, getFleet());
        info("fleet instances %s", currentStats.getInstances());

        // Set up the lists of Jenkins nodes and fleet instances
        // currentFleetInstances contains instances currently in the fleet
        final Set<String> fleetInstances = new HashSet<>(currentStats.getInstances());

        final Map<String, Instance> described = Registry.getEc2Api().describeInstances(ec2, fleetInstances);
        info("described instances %s", described.keySet());

        // currentJenkinsNodes contains all registered Jenkins nodes related to this cloud
        final Set<String> jenkinsInstances = new HashSet<>();
        for (final Node node : jenkins.getNodes()) {
            if (node instanceof EC2FleetNode && ((EC2FleetNode) node).getCloud() == this) {
                jenkinsInstances.add(node.getNodeName());
            }
        }
        info("jenkins nodes %s", jenkinsInstances);

        // contains Jenkins nodes that were once fleet instances but are no longer in the fleet
        final Set<String> jenkinsNodesWithInstance = new HashSet<>(jenkinsInstances);
        jenkinsNodesWithInstance.removeAll(fleetInstances);
        info("jenkins nodes without instance %s", jenkinsNodesWithInstance);

        // terminatedFleetInstances contains fleet instances that are terminated, stopped, stopping, or shutting down
        final Set<String> terminatedFleetInstances = new HashSet<>(fleetInstances);
        // terminated are any current which cannot be described
        terminatedFleetInstances.removeAll(described.keySet());
        info("terminated instances " + terminatedFleetInstances);

        // newFleetInstances contains running fleet instances that are not already Jenkins nodes
        final Map<String, Instance> newFleetInstances = new HashMap<>(described);
        for (final String instanceId : jenkinsInstances) newFleetInstances.remove(instanceId);
        info("new instances " + newFleetInstances.keySet());

        // update caches
        final List<String> jenkinsNodesToRemove = new ArrayList<>();
        jenkinsNodesToRemove.addAll(terminatedFleetInstances);
        jenkinsNodesToRemove.addAll(jenkinsNodesWithInstance);
        // Remove dying fleet instances from Jenkins
        for (final String instance : jenkinsNodesToRemove) {
            info("Fleet (" + getLabelString() + ") no longer has the instance " + instance + ", removing from Jenkins.");
            removeNode(instance);
        }

        // Update the label for all Jenkins nodes in the fleet instance cache
        for (final String instanceId : jenkinsInstances) {
            final Node node = jenkins.getNode(instanceId);
            if (node == null) continue;

            if (!labelString.equals(node.getLabelString())) {
                try {
                    info("Updating label on node %s to \"%s\".", instanceId, labelString);
                    node.setLabelString(labelString);
                } catch (final Exception ex) {
                    warning(ex, "Unable to set label on node %s", instanceId);
                }
            }
        }

        // If we have new instances - create nodes for them!
        if (newFleetInstances.size() > 0) {
            // we tag new instances to help users to identify instances launched from plugin managed fleets
            // if failed we are fine to skip this call
            try {
                Registry.getEc2Api().tagInstances(ec2, newFleetInstances.keySet(),
                        EC2_INSTANCE_CLOUD_NAME_TAG, name);
            } catch (final Exception e) {
                warning(e, "failed to tag new instances %s, skip", newFleetInstances.keySet());
            }

            // addNewSlave will call addNode which call queue lock
            // we speed up this by getting one lock for all nodes to all
            Queue.withLock(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (final Instance instance : newFleetInstances.values()) {
                            addNewSlave(ec2, instance, currentStats);
                        }
                    } catch (final Exception ex) {
                        warning(ex, "Unable to set label on node");
                    }
                }
            });
        }

        // lock and update state of plugin, so terminate or provision could work with new state of world
        synchronized (this) {
            instanceIdsToTerminate.removeAll(currentInstanceIdsToTerminate);
            // toAdd only grow outside of this method, so we can subtract
            toAdd = toAdd - currentToAdd;
            stats = currentStats;
        }

        return stats;
    }

    /**
     * Schedule Jenkins Node and EC2 instance to termination. Check first if target capacity more
     * then <code>minSize</code> otherwise reject termination.
     * <p>
     * Real termination will happens in {@link EC2FleetCloud#update()} which periodically called by
     * {@link CloudNanny}. So it could be some lag between decision that node should be terminated
     * and actual termination, you can find max lag size in {@link CloudNanny#getRecurrencePeriod()}
     * <p>
     * This method doesn't do real termination to reduce load for Jenkins in case when multiple nodes should be
     * terminated in short time, without schedule process and batch termination, multiple calls should be raised
     * to AWS EC2 API which takes some time and block cloud class.
     *
     * @param instanceId node name or instance ID
     * @return <code>true</code> if node scheduled to delete, otherwise <code>false</code>
     */
    public synchronized boolean scheduleToTerminate(final String instanceId) {
        info("Attempting to terminate instance: %s", instanceId);

        if (stats == null) {
            info("First update not done, skip termination");
            return false;
        }

        // We can't remove instances beyond minSize
        if (minSize > 0 && stats.getNumDesired() - instanceIdsToTerminate.size() <= minSize) {
            info("Not terminating %s because we need a minimum of %s instances running.", instanceId, minSize);
            return false;
        }

        instanceIdsToTerminate.add(instanceId);
        return true;
    }

    @Override
    public boolean canProvision(final Label label) {
        boolean result = fleet != null && (label == null || Label.parse(this.labelString).containsAll(label.listAtoms()));
        fine("CanProvision called on fleet: \"" + this.labelString + "\" wanting: \"" + (label == null ? "(unspecified)" : label.getName()) + "\". Returning " + result + ".");
        return result;
    }

    private Object readResolve() {
        init();
        return this;
    }

    private void init() {
        id = new LazyUuid();

        plannedNodesCache = new HashSet<>();
        instanceIdsToTerminate = new HashSet<>();
    }

    private void removeNode(final String instanceId) {
        final Jenkins jenkins = Jenkins.getInstance();
        // If this node is dying, remove it from Jenkins
        final Node n = jenkins.getNode(instanceId);
        if (n != null) {
            try {
                jenkins.removeNode(n);
            } catch (final Exception ex) {
                throw new IllegalStateException(String.format("Error removing node %s", instanceId), ex);
            }
        }
    }

    /**
     * https://github.com/jenkinsci/ec2-plugin/blob/master/src/main/java/hudson/plugins/ec2/EC2Cloud.java#L640
     *
     * @param ec2      ec2 client
     * @param instance instance
     */
    private void addNewSlave(final AmazonEC2 ec2, final Instance instance, FleetStateStats stats) throws Exception {
        final String instanceId = instance.getInstanceId();

        final String address = privateIpUsed ? instance.getPrivateIpAddress() : instance.getPublicIpAddress();
        // Check if we have the address to use. Nodes don't get it immediately.
        if (address == null) {
            if (!privateIpUsed) {
                info("%s instance public IP address not assigned, it could take some time or" +
                        " Spot Request is not configured to assign public IPs", instance.getInstanceId());
            }
            return; // wait more time, probably IP address not yet assigned
        }

        // Generate a random FS root if one isn't specified
        final String effectiveFsRoot;
        if (StringUtils.isBlank(fsRoot)) {
            effectiveFsRoot = "/tmp/jenkins-" + UUID.randomUUID().toString().substring(0, 8);
        } else {
            effectiveFsRoot = fsRoot;
        }

        final Double instanceTypeWeight = stats.getInstanceTypeWeights().get(instance.getInstanceType());
        final int effectiveNumExecutors;
        if (scaleExecutorsByWeight && instanceTypeWeight != null) {
            effectiveNumExecutors = (int) Math.max(Math.round(numExecutors * instanceTypeWeight), 1);
        } else {
            effectiveNumExecutors = numExecutors;
        }

        final EC2FleetAutoResubmitComputerLauncher computerLauncher = new EC2FleetAutoResubmitComputerLauncher(
                computerConnector.launch(address, TaskListener.NULL));
        final Node.Mode nodeMode = restrictUsage ? Node.Mode.EXCLUSIVE : Node.Mode.NORMAL;
        final EC2FleetNode node = new EC2FleetNode(instanceId, "Fleet slave for " + instanceId,
                effectiveFsRoot, effectiveNumExecutors, nodeMode, labelString, new ArrayList<NodeProperty<?>>(),
                this, computerLauncher);

        // Initialize our retention strategy
        node.setRetentionStrategy(new IdleRetentionStrategy());

        final Jenkins jenkins = Jenkins.getInstance();
        // jenkins automatically remove old node with same name if any
        jenkins.addNode(node);

        final SettableFuture<Node> future;
        if (plannedNodesCache.isEmpty()) {
            future = SettableFuture.create();
        } else {
            final NodeProvisioner.PlannedNode plannedNode = plannedNodesCache.iterator().next();
            plannedNodesCache.remove(plannedNode);
            future = ((SettableFuture<Node>) plannedNode.future);
        }

        // use getters for timeout and interval as they provide default value
        // when user just install new version and did't recreate fleet
        EC2FleetOnlineChecker.start(node, future,
                TimeUnit.SECONDS.toMillis(getInitOnlineTimeoutSec()),
                TimeUnit.SECONDS.toMillis(getInitOnlineCheckIntervalSec()));
    }

    private String getLogPrefix() {
        return getDisplayName() + " [" + getLabelString() + "] ";
    }

    private void info(final String msg, final Object... args) {
        LOGGER.info(getLogPrefix() + String.format(msg, args));
    }

    private void fine(final String msg, final Object... args) {
        LOGGER.fine(getLogPrefix() + String.format(msg, args));
    }

    private void warning(final String msg, final Object... args) {
        LOGGER.warning(getLogPrefix() + String.format(msg, args));
    }

    private void warning(final Throwable t, final String msg, final Object... args) {
        LOGGER.log(Level.WARNING, getLogPrefix() + String.format(msg, args), t);
    }

    @Extension
    @SuppressWarnings("unused")
    public static class DescriptorImpl extends Descriptor<Cloud> {

        public String accessId;
        public String secretKey;
        public String region;
        public String privateKey;
        public String fleet;
        public boolean showAllFleets;

        public DescriptorImpl() {
            super();
            load();
        }

        @Override
        public String getDisplayName() {
            return "Amazon AWS EC2";
        }

        public List getComputerConnectorDescriptors() {
            return Jenkins.getInstance().getDescriptorList(ComputerConnector.class);
        }

        public ListBoxModel doFillAwsCredentialsIdItems() {
            return AWSCredentialsHelper.doFillCredentialsIdItems(Jenkins.getInstance());
        }

        public ListBoxModel doFillRegionItems(@QueryParameter final String awsCredentialsId) {
            // to keep user consistent order tree set
            final Set<String> regionNames = new TreeSet<>();

            try {
                final AmazonEC2 client = Registry.getEc2Api().connect(awsCredentialsId, null, null);
                final DescribeRegionsResult regions = client.describeRegions();
                for (final Region region : regions.getRegions()) {
                    regionNames.add(region.getRegionName());
                }
            } catch (final Exception ex) {
                // ignore exception it could be case that credentials are not belong to default region
                // which we are using to describe regions
            }

            for (final com.amazonaws.regions.Region region : RegionUtils.getRegions()) {
                regionNames.add(region.getName());
            }

            final ListBoxModel model = new ListBoxModel();
            for (final String regionName : regionNames) {
                model.add(new ListBoxModel.Option(regionName, regionName));
            }
            return model;
        }

        public ListBoxModel doFillFleetItems(@QueryParameter final boolean showAllFleets,
                                             @QueryParameter final String region,
                                             @QueryParameter final String endpoint,
                                             @QueryParameter final String awsCredentialsId,
                                             @QueryParameter final String fleet) {
            final ListBoxModel model = new ListBoxModel();
            try {
                for (final EC2Fleet EC2Fleet : EC2Fleets.all()) {
                    EC2Fleet.describe(
                            awsCredentialsId, region, endpoint, model, fleet, showAllFleets);
                }
            } catch (final Exception ex) {
                LOGGER.log(Level.WARNING, String.format("Cannot describe fleets in %s or by endpoint %s", region, endpoint), ex);
                return model;
            }

            return model;
        }

        public FormValidation doTestConnection(
                @QueryParameter final String awsCredentialsId,
                @QueryParameter final String region,
                @QueryParameter final String endpoint,
                @QueryParameter final String fleet) {
            try {
                // read state to check if we have access
                EC2Fleets.get(fleet).getState(awsCredentialsId, region, endpoint, fleet);
            } catch (final Exception ex) {
                return FormValidation.error(ex.getMessage());
            }
            return FormValidation.ok("Success");
        }

        @Override
        public boolean configure(final StaplerRequest req, final JSONObject formData) throws FormException {
            req.bindJSON(this, formData);
            save();
            return super.configure(req, formData);
        }

        public boolean isShowAllFleets() {
            return showAllFleets;
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
