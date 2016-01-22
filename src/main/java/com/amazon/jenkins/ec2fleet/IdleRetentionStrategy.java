package com.amazon.jenkins.ec2fleet;

import hudson.model.Computer;
import hudson.slaves.RetentionStrategy;

/**
 * User: cyberax
 * Date: 1/12/16
 * Time: 02:56
 */
public class IdleRetentionStrategy extends RetentionStrategy<Computer>
{
    private final int maxIdleMinutes;
    private final EC2Cloud parent;

    public IdleRetentionStrategy(final int maxIdleMinutes, final EC2Cloud parent) {
        this.maxIdleMinutes = maxIdleMinutes;
        this.parent = parent;
    }

    protected boolean isIdleForTooLong(final Computer c) {
        return System.currentTimeMillis()-c.getIdleStartMilliseconds() > (maxIdleMinutes*60*1000);
    }

    @Override public long check(final Computer c) {
        if (isIdleForTooLong(c))
            parent.terminateInstance(c.getName());

        return 1;
    }
}
