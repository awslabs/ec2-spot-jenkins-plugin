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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * User: cyberax
 * Date: 1/11/16
 * Time: 20:54
 */
@SuppressWarnings("unused")
public final class FleetStateStats
{
    private static final double DEFAULT_WEIGHT = 1.0;
    private @Nonnull final String fleetId;
    private @Nonnegative final int numActive;
    private @Nonnegative final int numDesired;
    private @Nonnull final String state;
    private @Nonnull final Set<String> instances;
    private @Nonnull final Map<String,Double> instanceTypeWeights;
    private @Nonnull final String label;

    public FleetStateStats(final @Nonnull String fleetId,
                           final int numDesired, final @Nonnull String state,
                           final @Nonnull Set<String> instances,
                           final @Nonnull Map<String, Double> instanceTypeWeights,
                           final @Nonnull String label) {
        this.fleetId=fleetId;
        this.numActive=instances.size();
        this.numDesired=numDesired;
        this.state=state;
        this.instances=instances;
        this.instanceTypeWeights=instanceTypeWeights;
        this.label=label;
    }

    @Nonnull public String getFleetId() {
        return fleetId;
    }

    public int getNumActive() {
        return numActive;
    }

    public int getNumDesired() {
        return numDesired;
    }

    @Nonnull public String getState() {
        return state;
    }

    @Nonnull public Set<String> getInstances() {
        return instances;
    }

    @Nonnull public String getLabel() {
        return label;
    }

    public Double getInstanceTypeWeight(String instanceType)  {
        Double instanceTypeWeight = instanceTypeWeights.get(instanceType);
        return instanceTypeWeight == null ? DEFAULT_WEIGHT : instanceTypeWeight;
    }

    public static FleetStateStats readClusterState(final AmazonEC2 ec2, final String fleetId, final String label)
    {
        String token = null;
        final Set<String> instances = new HashSet<String>();
        do {
            final DescribeSpotFleetInstancesRequest req=new DescribeSpotFleetInstancesRequest();
            req.setSpotFleetRequestId(fleetId);
            req.setNextToken(token);
            final DescribeSpotFleetInstancesResult res=ec2.describeSpotFleetInstances(req);
            for(final ActiveInstance instance : res.getActiveInstances()) {
                instances.add(instance.getInstanceId());
            }

            token = res.getNextToken();
        } while(token!=null);

        final DescribeSpotFleetRequestsRequest req = new DescribeSpotFleetRequestsRequest();
        req.setSpotFleetRequestIds(Collections.singleton(fleetId));
        final DescribeSpotFleetRequestsResult fleet=ec2.describeSpotFleetRequests(req);
        if (fleet.getSpotFleetRequestConfigs().isEmpty())
            throw new IllegalStateException("Fleet "+fleetId+" can't be described");

        final SpotFleetRequestConfig fleetConfig=fleet.getSpotFleetRequestConfigs().get(0);
        final SpotFleetRequestConfigData fleetRequestConfig = fleetConfig.getSpotFleetRequestConfig();

        // Index configured instance types by weight:
        final Map<String, Double> instanceTypeWeight = new HashMap<String, Double>();
        for (SpotFleetLaunchSpecification launchSpecification : fleetRequestConfig.getLaunchSpecifications()) {
            final String instanceType = launchSpecification.getInstanceType();
            final Double instanceWeight = launchSpecification.getWeightedCapacity();
            final Double existingWeight = instanceTypeWeight.get(instanceType);
            if (existingWeight!=null && existingWeight > instanceWeight) {
                continue;
            }
            instanceTypeWeight.put(instanceType, instanceWeight);
        }

        return new FleetStateStats(fleetId,
                fleetRequestConfig.getTargetCapacity(),
                fleetConfig.getSpotFleetRequestState(), instances,
                Collections.unmodifiableMap(instanceTypeWeight),
                label);
    }
}
