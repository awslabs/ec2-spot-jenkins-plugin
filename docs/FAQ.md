## FAQ

NOTE: "Jenkins" will refer to the Jenkins code that is not handled by EC2-Fleet-Plugin

**Q:** How does the EC2-Fleet-Plugin handle scaling?  
**A:** For scaling up, the EC2-Fleet-Plugin increases EC2 Fleet's `target capacity` or ASG `desired capacity` to obtain new instances. 

For scaling down, the plugin will manually terminate instances that should be removed and adjust the target/desired capacity.

**Q:** What's an `update` cycle?  
**A:** As long as the plugin is running, the `update` function is called every `Cloud Status Interval` seconds to sync the state of
the plugin with the state of the EC2 Fleet or ASG. Most work done by the plugin occurs within `update`. 

In the logs, "start" denotes the beginning of an `update` cycle. 

**Q:** When does the EC2-Fleet-Plugin scale up?  
**A:** When there are pending jobs in the queue, Jenkins will hook into the EC2-Fleet-Plugin to provision new capacity.
If the cloud is able to handle the job (matching labels or no label restrictions), it will calculate how much capacity is needed based on the instance weights and number of executors
per instance. On the next `update` cycle the cloud adjusts `target capacity` to provision new instances. Therefore, if `Cloud Status Interval` is large you will see some delay
between the Jenkins call to provision new instances and the actual API request that changes target capacity.

**Q:** When does the EC2-Fleet-Plugin scale down?  
**A:** Jenkins periodically checks for idle nodes that it can remove. If there are more nodes than `minSize` and either a node has been idle for longer than `Max Idle Minutes Before Scaledown`
or there are more nodes than allowed by `maxSize`, the idle node will be scheduled for termination.

Pseudo-code:
```
if (node.isIdle() && numNodes > minSize && (node.isIdleTooLong() || numNodes > maxSize) {
    scheduleToTerminate(node);
}
```

On the next `update` cycle, an API call is made for each node scheduled for termination. Note that it might be a few more update cycles
before the instances are fully terminated. Check [IdleRetentionStrategy](https://github.com/jenkinsci/ec2-fleet-plugin/blob/master/src/main/java/com/amazon/jenkins/ec2fleet/IdleRetentionStrategy.java)
for details.

**Q:** What does "first update not done" mean?  
**A:** This means that the first `update` cycle hasn't completed and the cloud state is unknown. 

If the plugin configuration was recently saved this shouldn't be a problem, just wait a bit and the update will be triggered after "Cloud Status Interval in sec" seconds.

If the plugin has been running for a while untouched, there might be an error in the configuration, such as incorrect AWS Credentials. Check the
logs to see if there is any additional information. 

If the plugin version is older than 2.2.2, upgrade to 2.2.2 or later. There was a known bug in older version that was fixed in [#247](https://github.com/jenkinsci/ec2-fleet-plugin/pull/247).

Otherwise, open an issue and we'll take a look. 

**Q:** Why isn't the plugin scaling down?  
**A:** Double check the cloud configuration and the log file. **If `Max Idle Minutes Before Scaledown` is 0, instances will never be removed.** 

If using a plugin version older than 2.2.2, try upgrading. Change [#247](https://github.com/jenkinsci/ec2-fleet-plugin/pull/247)
was released in that version to fix a common problem with instances not being terminated after configuration changes.   

Check the minimum instances on the Fleet or ASG, it might be higher than the min instances in the plugin config.

If there is nothing abnormal, check for open issues or open a new one if none exist. The plugin should always be able to scale down!

**Q:** Why isn't the plugin scaling up?  
**A:** Double check the cloud configuration and the log file. Also, check the maximum instances on the Fleet or ASG.
If the plugin version is older than 2.2.1, it might be fixed by updating the plugin.
Before that version, modifying the configuration of the plugin during a scaling operation could cause the state of Jenkins and the plugin to become out of sync and require a restart.

If the plugin version is newer than 2.2.1, check for open issues or open a new one if none exist.  

**Q:** Why does the plugin keep enabling scale-in protection on my ASG?  
**A:** The plugin handles termination of instances manually based on idle period settings. Without scale-in protection enabled,
instances could be terminated unexpectedly by external conditions and running jobs could be interrupted.   

**Q:** I only changed one configuration field, why did it reload everything?  
**A:** Jenkins doesn't hot swap plugin settings. When 'Save' is clicked, Jenkins will write the plugin configuration to disk and
reinitialize the plugin using the new, current version. If a cloud is modified the plugin will attempt to migrate resources,
but this is not perfect and issues sometimes arise here. If possible, restarting Jenkins after modifying the plugin
configuration often solves most of these problems.  

**Q:** I want to know about _____, but I don't see any information here?  
**A:** Check out the [docs](https://github.com/jenkinsci/ec2-fleet-plugin/tree/master/docs) folder. If you're still unable to
find what you're looking for, or you think we should add something, let us know by opening an issue. 

**Q:** Can I contribute?  
**A:** Yes, please! Check out the [contributing](https://github.com/jenkinsci/ec2-fleet-plugin/blob/master/CONTRIBUTING.md) page for more information.

## Gotchas

- Modifying the plugin settings will cause all Cloud Fleets to be reconstructed. This can cause strange behavior if done
while jobs are queued or running. If possible, avoid modifying the configuration while jobs are queued or running, or restart
Jenkins after making configuration changes. 

- Max Idle Minutes Before Scaledown is set to 0 so instances are never removed (click the ? on the config page).
