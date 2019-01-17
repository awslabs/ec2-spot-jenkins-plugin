package com.amazon.jenkins.ec2fleet;

import hudson.model.Computer;
import hudson.model.Node;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * User: cyberax
 * Date: 1/12/16
 * Time: 02:56
 */
public class IdleRetentionStrategy extends RetentionStrategy<SlaveComputer>
{
    private final int maxIdleMinutes;
    private final boolean alwaysReconnect;
    private final EC2FleetCloud parent;

    private static final Logger LOGGER = Logger.getLogger(IdleRetentionStrategy.class.getName());

    public IdleRetentionStrategy(final EC2FleetCloud parent) {
        this.maxIdleMinutes = parent.getIdleMinutes();
        this.alwaysReconnect = parent.isAlwaysReconnect();
        this.parent = parent;
        LOGGER.log(Level.INFO, "Idle Retention initiated");
    }

    protected boolean isIdleForTooLong(final Computer c) {
        boolean isTooLong = false;
        if(maxIdleMinutes > 0) {
            long age = System.currentTimeMillis()-c.getIdleStartMilliseconds();
            long maxAge = maxIdleMinutes*60*1000;
            LOGGER.log(Level.FINE, "Instance: " + c.getDisplayName() + " Age: " + age + " Max Age:" + maxAge);
            isTooLong = age > maxAge;
        }
        return isTooLong;
    }

    @Override public long check(final SlaveComputer c) {
        // Ensure that the EC2FleetCloud cannot be mutated from under us while
        // we're doing this check
        synchronized(parent) {
            // Ensure nobody provisions onto this node until we've done
            // checking
            boolean shouldAcceptTasks = c.isAcceptingTasks();
            boolean justTerminated = false;
            c.setAcceptingTasks(false);
            try {
                if (isIdleForTooLong(c)) {
                    // Find instance ID
                    Node compNode = c.getNode();
                    if (compNode == null) {
                        return 0;
                    }

                    final String nodeId = compNode.getNodeName();
                    if (parent.terminateInstance(nodeId)) {
                        // Instance successfully terminated, so no longer accept tasks
                        shouldAcceptTasks = false;
                        justTerminated = true;
                    }
                }

                if (alwaysReconnect && !justTerminated && c.isOffline() && !c.isConnecting() && c.isLaunchSupported()) {
                    LOGGER.log(Level.INFO, "Reconnecting to instance: " + c.getDisplayName());
                    c.tryReconnect();
                }
            } finally {
                c.setAcceptingTasks(shouldAcceptTasks);
            }
        }

        return 1;
    }

    @Override public void start(SlaveComputer c) {
        LOGGER.log(Level.INFO, "Connecting to instance: " + c.getDisplayName());
        c.connect(false);
    }
}
