package com.amazon.jenkins.ec2fleet;

import hudson.Extension;
import hudson.widgets.Widget;

import java.util.Collections;
import java.util.List;

/**
 * User: cyberax
 * Date: 1/11/16
 * Time: 13:21
 */
@Extension
public class FleetStatusWidget extends Widget
{
    private List<FleetStateStats> statusList = Collections.emptyList();

    public void setStatusList(final List<FleetStateStats> statusList) {
        this.statusList=statusList;
    }

    @SuppressWarnings("unused")
    public List<FleetStateStats> getStatusList() {
        return statusList;
    }
}
