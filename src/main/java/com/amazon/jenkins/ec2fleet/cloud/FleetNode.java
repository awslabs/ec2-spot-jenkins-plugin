package com.amazon.jenkins.ec2fleet.cloud;

import com.amazon.jenkins.ec2fleet.EC2FleetCloud;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.List;

/**
 * User: cyberax
 * Date: 4/15/16
 * Time: 23:59
 */
public class FleetNode extends Slave implements EphemeralNode
{
    private static final long LAUNCH_TIMEOUT_MS=1000*1000L;
    private final String cloudName;

    public FleetNode(final String name, final String nodeDescription, final String remoteFS, final String numExecutors, final Mode mode, final String label,
                     final List<? extends NodeProperty<?>> nodeProperties, final String address, final String cloudName) throws IOException, Descriptor.FormException {
        super(name, nodeDescription, remoteFS, numExecutors, mode, label,
                new SimpleRemoteLauncher(address), RetentionStrategy.NOOP, nodeProperties);
        this.cloudName = cloudName;
    }

    @DataBoundConstructor
    public FleetNode(final String name, final String nodeDescription, final String remoteFS, final String numExecutors, final Mode mode, final String labelString,
                     final ComputerLauncher launcher, final RetentionStrategy<?> retentionStrategy, final List<? extends NodeProperty<?>> nodeProperties,
                     final String cloudName) throws Descriptor.FormException, IOException {
        super(name, nodeDescription, remoteFS, Util.tryParseNumber(numExecutors, 1).intValue(), mode, labelString, launcher, retentionStrategy, nodeProperties);
        this.cloudName = cloudName;
    }

    @Override public Node asNode() {
        return this;
    }

    @Override public Computer createComputer() {
        return new FleetNodeComputer(this);
    }

    public long getLaunchTimeoutInMillis() {
        return LAUNCH_TIMEOUT_MS;
    }

    public EC2FleetCloud getCloud() {
        return (EC2FleetCloud) Jenkins.getInstance().getCloud(cloudName);
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor
    {
        public DescriptorImpl() {
            super();
        }

        public String getDisplayName() {
            return "Fleet Slave";
        }

        /**
         * We only create this kind of nodes programatically.
         */
        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
