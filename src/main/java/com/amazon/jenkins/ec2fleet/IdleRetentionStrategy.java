package com.amazon.jenkins.ec2fleet;

import hudson.model.Computer;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;

/**
 * User: cyberax
 * Date: 1/12/16
 * Time: 02:56
 */
public class IdleRetentionStrategy extends RetentionStrategy<SlaveComputer>
{
    private final int maxIdleMinutes;
    private final EC2FleetCloud parent;

    public IdleRetentionStrategy(final int maxIdleMinutes, final EC2FleetCloud parent) {
        this.maxIdleMinutes = maxIdleMinutes;
        this.parent = parent;
    }

    protected boolean isIdleForTooLong(final Computer c) {
        return System.currentTimeMillis()-c.getIdleStartMilliseconds() > (maxIdleMinutes*60*1000);
    }

    @Override public long check(final SlaveComputer c) {
        if (isIdleForTooLong(c))
            parent.terminateInstance(c.getName());
        else {
            if (c.isOffline() && !c.isConnecting() && c.isLaunchSupported())
                c.tryReconnect();
        }

        return 1;
    }
}
