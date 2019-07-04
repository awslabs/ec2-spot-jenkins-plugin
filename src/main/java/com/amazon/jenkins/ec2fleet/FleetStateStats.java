package com.amazon.jenkins.ec2fleet;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.ActiveInstance;
import com.amazonaws.services.ec2.model.DescribeSpotFleetInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeSpotFleetInstancesResult;
import com.amazonaws.services.ec2.model.DescribeSpotFleetRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotFleetRequestsResult;
import com.amazonaws.services.ec2.model.SpotFleetLaunchSpecification;
import com.amazonaws.services.ec2.model.SpotFleetRequestConfig;
import com.amazonaws.services.ec2.model.SpotFleetRequestConfigData;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @see EC2FleetCloud
 */
@SuppressWarnings({"unused", "WeakerAccess"})
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

    public static FleetStateStats readClusterState(final AmazonEC2 ec2, final String fleetId, final String label) {
        String token = null;
        final Set<String> instances = new HashSet<>();
        do {
            final DescribeSpotFleetInstancesRequest request = new DescribeSpotFleetInstancesRequest();
            request.setSpotFleetRequestId(fleetId);
            request.setNextToken(token);
            final DescribeSpotFleetInstancesResult res = ec2.describeSpotFleetInstances(request);
            for (final ActiveInstance instance : res.getActiveInstances()) {
                instances.add(instance.getInstanceId());
            }

            token = res.getNextToken();
        } while (token != null);

        final DescribeSpotFleetRequestsRequest request = new DescribeSpotFleetRequestsRequest();
        request.setSpotFleetRequestIds(Collections.singleton(fleetId));
        final DescribeSpotFleetRequestsResult fleet = ec2.describeSpotFleetRequests(request);
        if (fleet.getSpotFleetRequestConfigs().isEmpty())
            throw new IllegalStateException("Fleet " + fleetId + " can't be described");

        final SpotFleetRequestConfig fleetConfig = fleet.getSpotFleetRequestConfigs().get(0);
        final SpotFleetRequestConfigData fleetRequestConfig = fleetConfig.getSpotFleetRequestConfig();

        // Index configured instance types by weight:
        final Map<String, Double> instanceTypeWeights = new HashMap<>();
        for (SpotFleetLaunchSpecification launchSpecification : fleetRequestConfig.getLaunchSpecifications()) {
            final String instanceType = launchSpecification.getInstanceType();
            if (instanceType == null) continue;

            final Double instanceWeight = launchSpecification.getWeightedCapacity();
            final Double existingWeight = instanceTypeWeights.get(instanceType);
            if (instanceWeight == null || (existingWeight != null && existingWeight > instanceWeight)) {
                continue;
            }
            instanceTypeWeights.put(instanceType, instanceWeight);
        }

        return new FleetStateStats(fleetId,
                fleetRequestConfig.getTargetCapacity(),
                fleetConfig.getSpotFleetRequestState(), instances,
                instanceTypeWeights);
    }
}
