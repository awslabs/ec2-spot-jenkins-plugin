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
    private final EC2FleetCloud parent;

    private static final Logger LOGGER = Logger.getLogger(IdleRetentionStrategy.class.getName());

    public IdleRetentionStrategy(final int maxIdleMinutes, final EC2FleetCloud parent) {
        this.maxIdleMinutes = maxIdleMinutes;
        this.parent = parent;
        LOGGER.log(Level.INFO, "Idle Retention initiated");
    }

    protected boolean isIdleForTooLong(final Computer c) {
        long age = System.currentTimeMillis()-c.getIdleStartMilliseconds();
        long maxAge = maxIdleMinutes*60*1000;
        LOGGER.log(Level.FINE, "Instance: " + c.getDisplayName() + " Age: " + age + " Max Age:" + maxAge);
        return System.currentTimeMillis()-c.getIdleStartMilliseconds() > (maxIdleMinutes*60*1000);
    }

    @Override public long check(final SlaveComputer c) {
        if (isIdleForTooLong(c)){
            // Split labels and find instance ID
            Node compNode = c.getNode();
            if (compNode.equals(null)){
                return 0;
            }
            
            String nodeId = null;
            for(String str: c.getNode().getLabelString().split(" ")){
                if(str.startsWith("i-")){
                    nodeId = str;
                }
            }
             
            if (nodeId.equals(null)){
                LOGGER.log(Level.INFO, "Node " + c.getName(), " does not have proper labels");
                return 0;
            }
            LOGGER.log(Level.INFO, "Terminating Fleet instance: " + nodeId);
            parent.terminateInstance(nodeId);
        } else {
            if (c.isOffline() && !c.isConnecting() && c.isLaunchSupported())
                c.tryReconnect();
        }

        return 1;
    }
}
