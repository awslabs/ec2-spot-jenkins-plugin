package com.amazon.jenkins.ec2fleet;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Map;
import java.util.Set;

/**
 * @see EC2FleetCloud
 */
@SuppressWarnings({"unused"})
@ThreadSafe
public final class FleetStateStats {

    @Nonnull
    private final String fleetId;
    @Nonnegative
    private final int numActive;
    @Nonnegative
    private final int numDesired;
    @Nonnull
    private final String state;
    @Nonnull
    private final Set<String> instances;
    @Nonnull
    private final Map<String, Double> instanceTypeWeights;

    public FleetStateStats(final @Nonnull String fleetId,
                           final int numDesired, final @Nonnull String state,
                           final @Nonnull Set<String> instances,
                           final @Nonnull Map<String, Double> instanceTypeWeights) {
        this.fleetId = fleetId;
        this.numActive = instances.size();
        this.numDesired = numDesired;
        this.state = state;
        this.instances = instances;
        this.instanceTypeWeights = instanceTypeWeights;
    }

    @Nonnull
    public String getFleetId() {
        return fleetId;
    }

    public int getNumActive() {
        return numActive;
    }

    public int getNumDesired() {
        return numDesired;
    }

    @Nonnull
    public String getState() {
        return state;
    }

    @Nonnull
    public Set<String> getInstances() {
        return instances;
    }

    @Nonnull
    public Map<String, Double> getInstanceTypeWeights() {
        return instanceTypeWeights;
    }

}
