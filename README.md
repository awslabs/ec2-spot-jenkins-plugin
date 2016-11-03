# ec2-spot-jenkins-plugin
The EC2 Spot Jenkins plugin launches EC2 Spot instances as worker nodes for Jenkins CI server, 
automatically scaling the capacity with the load. 

# SpotFleet
This plugin uses Spot Fleet to launch instances instead of directly launching them by itself. 
Amazon EC2 attempts to maintain your Spot fleet's target capacity as Spot prices change to maintain
the fleet within the specified price range. For more information, see 
[How Spot Fleet Works](http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/spot-fleet.html).

# Usage
An AWS account is required to use this plugin: [Sign up for an AWS account](https://portal.aws.amazon.com/gp/aws/developer/registration/index.html).
Once you have an account, create an IAM user with sufficient permissions to launch Spot Fleets ( 
[Spot Fleet Prerequisites](http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/spot-fleet-requests.html#spot-fleet-prerequisites 
"Spot Fleet Prerequisites") ) and get its AWS credentials.

Next, deploy a Spot Fleet that will serve as the build fleet for Jenkins. You can launch a Spot Fleet from the 
[AWS console](http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/spot-fleet-requests.html#create-spot-fleet)
or via the [AWS CLI](http://docs.aws.amazon.com/cli/latest/reference/ec2/request-spot-fleet.html). Make sure that you specify an SSH key that will be used later by Jenkins.

Once the fleet is launched, you can set it up by adding a new **EC2 Fleet** cloud in the 
"Manage Jenkins" > "Configure System" menu of Jenkins.

# Scaling
You can specify the scaling limits in your cloud settings. By default, Jenkins will try to scale fleet up
if there are enough tasks waiting in the build queue and scale down idle nodes after a specified idleness period.

You can use the History tab in the AWS console to view the scaling history.

Issues
======

Please address any issues or feedback via [issues](https://github.com/awslabs/ec2-spot-jenkins-plugin/issues).
