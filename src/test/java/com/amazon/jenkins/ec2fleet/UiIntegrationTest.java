package com.amazon.jenkins.ec2fleet;

import com.gargoylesoftware.htmlunit.html.DomElement;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlFormUtil;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlTextInput;
import hudson.PluginWrapper;
import hudson.slaves.Cloud;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * Detailed guides https://jenkins.io/doc/developer/testing/
 * https://wiki.jenkins.io/display/JENKINS/Unit+Test#UnitTest-DealingwithproblemsinJavaScript
 */
public class UiIntegrationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @ClassRule
    public static BuildWatcher bw = new BuildWatcher();

    @Test
    public void shouldFindThePluginByShortName() {
        PluginWrapper wrapper = j.getPluginManager().getPlugin("ec2-fleet");
        assertNotNull("should have a valid plugin", wrapper);
    }

    @Test
    public void shouldShowInConfigurationClouds() throws IOException, SAXException {
        Cloud cloud = new EC2FleetCloud(null, null, null, null, null,
                null, null, null, false, false,
                0, 0, 0, 0);
        j.jenkins.clouds.add(cloud);

        HtmlPage page = j.createWebClient().goTo("configure");
        System.out.println(page);

        assertEquals("ec2-fleet", ((HtmlTextInput) getElementsByNameWithoutJdk(page, "_.labelString").get(1)).getText());
    }

    @Test
    public void shouldShowMultipleClouds() throws IOException, SAXException {
        Cloud cloud1 = new EC2FleetCloud("a", null, null, null, null,
                null, null, null, false, false,
                0, 0, 0, 0);
        j.jenkins.clouds.add(cloud1);

        Cloud cloud2 = new EC2FleetCloud("b", null, null, null, null,
                null, null, null, false, false,
                0, 0, 0, 0);
        j.jenkins.clouds.add(cloud2);

        HtmlPage page = j.createWebClient().goTo("configure");
        System.out.println(page);

        List<DomElement> elementsByName = getElementsByNameWithoutJdk(page, "_.name");
        assertEquals(2, elementsByName.size());
        assertEquals("a", ((HtmlTextInput) elementsByName.get(0)).getText());
        assertEquals("b", ((HtmlTextInput) elementsByName.get(1)).getText());
    }

    @Test
    public void shouldShowMultipleCloudsWithDefaultName() throws IOException, SAXException {
        Cloud cloud1 = new EC2FleetCloud(null, null, null, null, null,
                null, null, null, false, false,
                0, 0, 0, 0);
        j.jenkins.clouds.add(cloud1);

        Cloud cloud2 = new EC2FleetCloud(null, null, null, null, null,
                null, null, null, false, false,
                0, 0, 0, 0);
        j.jenkins.clouds.add(cloud2);

        HtmlPage page = j.createWebClient().goTo("configure");
        System.out.println(page);

        List<DomElement> elementsByName = getElementsByNameWithoutJdk(page, "_.name");
        assertEquals(2, elementsByName.size());
        assertEquals("FleetCloud", ((HtmlTextInput) elementsByName.get(0)).getText());
        assertEquals("FleetCloud", ((HtmlTextInput) elementsByName.get(1)).getText());
    }

    @Test
    public void shouldUpdateProperCloudWhenMultiple() throws IOException, SAXException {
        EC2FleetCloud cloud1 = new EC2FleetCloud(null, null, null, null, null,
                null, null, null, false, false,
                0, 0, 0, 0);
        j.jenkins.clouds.add(cloud1);

        EC2FleetCloud cloud2 = new EC2FleetCloud(null, null, null, null, null,
                null, null, null, false, false,
                0, 0, 0, 0);
        j.jenkins.clouds.add(cloud2);

        HtmlPage page = j.createWebClient().goTo("configure");
        HtmlForm form = page.getFormByName("config");

        ((HtmlTextInput) getElementsByNameWithoutJdk(page, "_.name").get(0)).setText("a");

        HtmlFormUtil.submit(form);

        assertEquals("a", j.jenkins.clouds.get(0).name);
        assertEquals("FleetCloud", j.jenkins.clouds.get(1).name);
    }

    @Test
    public void shouldGetFirstWhenMultipleCloudWithSameName() {
        EC2FleetCloud cloud1 = new EC2FleetCloud(null, null, null, null, null,
                null, null, null, false, false,
                0, 0, 0, 0);
        j.jenkins.clouds.add(cloud1);

        EC2FleetCloud cloud2 = new EC2FleetCloud(null, null, null, null, null,
                null, null, null, false, false,
                0, 0, 0, 0);
        j.jenkins.clouds.add(cloud2);

        assertSame(cloud1, j.jenkins.getCloud("FleetCloud"));
    }

    @Test
    public void shouldGetProperWhenMultipleWithDiffName() {
        EC2FleetCloud cloud1 = new EC2FleetCloud("a", null, null, null, null,
                null, null, null, false, false,
                0, 0, 0, 0);
        j.jenkins.clouds.add(cloud1);

        EC2FleetCloud cloud2 = new EC2FleetCloud("b", null, null, null, null,
                null, null, null, false, false,
                0, 0, 0, 0);
        j.jenkins.clouds.add(cloud2);

        assertSame(cloud1, j.jenkins.getCloud("a"));
        assertSame(cloud2, j.jenkins.getCloud("b"));
    }

    private static List<DomElement> getElementsByNameWithoutJdk(HtmlPage page, String name) {
        String jdkCheckUrl = "/jenkins/descriptorByName/hudson.model.JDK/checkName";
        List<DomElement> r = new ArrayList<>();
        for (DomElement domElement : page.getElementsByName(name)) {
            if (!jdkCheckUrl.equals(domElement.getAttribute("checkurl"))) {
                r.add(domElement);
            }
        }
        return r;
    }

}
