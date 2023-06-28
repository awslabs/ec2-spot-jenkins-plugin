package com.amazon.jenkins.ec2fleet;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;

public class EC2FleetNode extends Slave implements EphemeralNode, EC2FleetCloudAware {

    private volatile AbstractEC2FleetCloud cloud;
    private final int maxTotalUses;
    private int usesRemaining;

    public EC2FleetNode(final String name, final String nodeDescription, final String remoteFS, final int numExecutors, final Mode mode, final String label,
                        final List<? extends NodeProperty<?>> nodeProperties, final AbstractEC2FleetCloud cloud, ComputerLauncher launcher, final int maxTotalUses) throws IOException, Descriptor.FormException {
        //noinspection deprecation
        super(name, nodeDescription, remoteFS, numExecutors, mode, label,
                launcher, RetentionStrategy.NOOP, nodeProperties);
        this.cloud = cloud;
        this.maxTotalUses = maxTotalUses;
        this.usesRemaining = maxTotalUses;
    }

    @Override
    public Node asNode() {
        return this;
    }

    @Override
    public String getDisplayName() {
        // in some multi-thread edge cases cloud could be null for some time, just be ok with that
        return (cloud == null ? "unknown fleet" : cloud.getDisplayName()) + " " + name;
    }

    @Override
    public Computer createComputer() {
        return new EC2FleetNodeComputer(this, name, cloud);
    }

    @Override
    public AbstractEC2FleetCloud getCloud() {
        return cloud;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCloud(@Nonnull AbstractEC2FleetCloud cloud) {
        this.cloud = cloud;
    }

    public int getMaxTotalUses() {
        return this.maxTotalUses;
    }

    public int getUsesRemaining() {
        return usesRemaining;
    }

    public void decrementUsesRemaining() {
        this.usesRemaining--;
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {
        public DescriptorImpl() {
            super();
        }

        public String getDisplayName() {
            return "Fleet Slave";
        }

        /**
         * We only create this kind of nodes programmatically.
         */
        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
