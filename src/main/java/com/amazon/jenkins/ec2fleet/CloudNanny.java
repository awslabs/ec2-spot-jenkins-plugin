package com.amazon.jenkins.ec2fleet;

import hudson.Extension;
import hudson.model.Hudson;
import hudson.model.PeriodicWork;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.widgets.Widget;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * User: cyberax
 * Date: 1/11/16
 * Time: 13:21
 */
@Extension
@SuppressWarnings("unused")
public class CloudNanny extends PeriodicWork
{
    private static final Logger LOGGER = Logger.getLogger(CloudNanny.class.getName());

    @Override public long getRecurrencePeriod() {
        return 10000L;
    }

    @Override protected void doRun() throws Exception {

        // Trigger reprovisioning as well
        Jenkins.getInstance().unlabeledNodeProvisioner.suggestReviewNow();

        final List<FleetStateStats> stats = new ArrayList<FleetStateStats>();
        for(final Cloud cloud : Jenkins.getInstance().clouds) {

            if (!(cloud instanceof EC2Cloud))
                continue;

            // Update the cluster states
            final EC2Cloud fleetCloud =(EC2Cloud) cloud;
            LOGGER.log(Level.INFO, "Checking cloud: " + fleetCloud.getLabel() );
            stats.add(fleetCloud.updateStatus());
        }

        for (final Widget w : Jenkins.getInstance().getWidgets()) {
            if (!(w instanceof FleetStatusWidget))
                continue;

            ((FleetStatusWidget)w).setStatusList(stats);
        }
    }
}
