package com.amazon.jenkins.ec2fleet.fleet;

import com.amazon.jenkins.ec2fleet.FleetStateStats;
import com.amazon.jenkins.ec2fleet.Registry;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.elasticmapreduce.model.InstanceFleetConfig;
import com.amazonaws.services.elasticmapreduce.model.InstanceTypeConfig;
import hudson.util.ListBoxModel;
import org.springframework.util.ObjectUtils;

import java.util.*;

public class EC2CreateFleet implements EC2Fleet {
    @Override
    public void describe(String awsCredentialsId, String regionName, String endpoint, ListBoxModel model, String selectedId, boolean showAll) {
        final AmazonEC2 client = Registry.getEc2Api().connect(awsCredentialsId, regionName, endpoint);
        String token = null;
        do {
            final DescribeFleetsRequest request = new DescribeFleetsRequest();
            request.withNextToken(token);
            final DescribeFleetsResult result = client.describeFleets(request);
            for(final FleetData fleetData : result.getFleets()) {
                final String curFleetId = fleetData.getFleetId();
                final boolean selected = ObjectUtils.nullSafeEquals(selectedId, curFleetId);
                if (selected || showAll || isActiveAndMaintain(fleetData)) {
                    final String displayStr = "EC2 Spot Fleet - " + curFleetId +
                            " (" + fleetData.getFleetState() + ")" +
                            " (" + fleetData.getType() + ")";
                    model.add(new ListBoxModel.Option(displayStr, curFleetId, selected));
                }
            }
            token = result.getNextToken();
        } while (token != null);
    }

    private static boolean isActiveAndMaintain(final FleetData fleetData) {
        return FleetType.Maintain.toString().equals(fleetData.getType()) && isActive(fleetData);
    }

    private static boolean isActive(final FleetData fleetData) {
        return BatchState.Active.toString().equals(fleetData.getFleetState())
                || BatchState.Modifying.toString().equals(fleetData.getFleetState())
                || BatchState.Submitted.toString().equals(fleetData.getFleetState());
    }

    private static boolean isModifying(final FleetData fleetData) {
        return BatchState.Submitted.toString().equals(fleetData.getFleetState())
                || BatchState.Modifying.toString().equals(fleetData.getFleetState());
    }

    @Override
    public void modify(String awsCredentialsId, String regionName, String endpoint, String id, int targetCapacity, int min, int max) {
        final ModifyFleetRequest request = new ModifyFleetRequest();
        request.setFleetId(id);
        request.setTargetCapacitySpecification(new TargetCapacitySpecificationRequest().withTotalTargetCapacity(targetCapacity));
        request.setExcessCapacityTerminationPolicy("NoTermination");

        final AmazonEC2 ec2 = Registry.getEc2Api().connect(awsCredentialsId, regionName, endpoint);
        ec2.modifyFleet(request);
    }

    @Override
    public FleetStateStats getState(String awsCredentialsId, String regionName, String endpoint, String id) {
        final AmazonEC2 ec2 = Registry.getEc2Api().connect(awsCredentialsId, regionName, endpoint);

        String token = null;
        final Set<String> instances = new HashSet<>();
        do {
            final DescribeFleetInstancesRequest request = new DescribeFleetInstancesRequest();
            request.setFleetId(id);
            request.setNextToken(token);
            final DescribeFleetInstancesResult result = ec2.describeFleetInstances(request);
            for (final ActiveInstance instance : result.getActiveInstances()) {
                instances.add(instance.getInstanceId());
            }

            token = result.getNextToken();
        } while (token != null);

        final DescribeFleetsRequest request = new DescribeFleetsRequest();
        request.setFleetIds(Collections.singleton(id));
        final DescribeFleetsResult fleet = ec2.describeFleets(request);
        if (fleet.getFleets().isEmpty())
            throw new IllegalStateException("Fleet " + id + " can't be described");

        final FleetData fleetData = fleet.getFleets().get(0);
        final FleetLaunchTemplateConfig templateConfigs = fleetData.getLaunchTemplateConfigs().get(0);

        // Index configured instance types by weight:
        final Map<String, Double> instanceTypeWeights = new HashMap<>();
        for (FleetLaunchTemplateOverrides launchOverrides : templateConfigs.getOverrides()) {
            final String instanceType = launchOverrides.getInstanceType();
            if (instanceType == null) continue;

            final Double instanceWeight = launchOverrides.getWeightedCapacity();
            final Double existingWeight = instanceTypeWeights.get(instanceType);
            if (instanceWeight == null || (existingWeight != null && existingWeight > instanceWeight)) {
                continue;
            }
            instanceTypeWeights.put(instanceType, instanceWeight);
        }

        return new FleetStateStats(id,
                fleetData.getTargetCapacitySpecification().getTotalTargetCapacity(),
                new FleetStateStats.State(
                        isActive(fleetData),
                        isModifying(fleetData),
                        fleetData.getFleetState()),
                instances,
                instanceTypeWeights);
    }

    private static class State {
        String id;
        Set<String> instances;
        FleetData fleetData;
    }

    @Override
    public Map<String, FleetStateStats> getStateBatch(String awsCredentialsId, String regionName, String endpoint, Collection<String> ids) {
        final AmazonEC2 ec2 = Registry.getEc2Api().connect(awsCredentialsId, regionName, endpoint);

        List<State> states = new ArrayList<>();
        for (String id : ids) {
            final State s = new State();
            s.id = id;
            states.add(s);
        }

        for (State state : states) {
            String token = null;
            state.instances = new HashSet<>();
            do {
                final DescribeFleetInstancesRequest request = new DescribeFleetInstancesRequest();
                request.setFleetId(state.id);
                request.setNextToken(token);
                final DescribeFleetInstancesResult result = ec2.describeFleetInstances(request);
                for (final ActiveInstance instance : result.getActiveInstances()) {
                    state.instances.add(instance.getInstanceId());
                }

                token = result.getNextToken();
            } while (token != null);
        }

        final DescribeFleetsRequest request = new DescribeFleetsRequest();
        request.setFleetIds(ids);
        final DescribeFleetsResult fleet = ec2.describeFleets(request);
        for (FleetData fleetData: fleet.getFleets()) {
            for (State state : states) {
                if (state.id.equals(fleetData.getFleetId())) state.fleetData = fleetData;
            }
        }

        Map<String, FleetStateStats> r = new HashMap<>();
        for (State state : states) {
            r.put(state.id, new FleetStateStats(state.id,
                    state.fleetData.getTargetCapacitySpecification().getTotalTargetCapacity(),
                    new FleetStateStats.State(
                            isActive(state.fleetData),
                            isModifying(state.fleetData),
                            state.fleetData.getFleetState()),
                    state.instances,
                    Collections.<String, Double>emptyMap()));
        }
        return r;
    }
}
