package org.jenkinsci.plugins.casc.plugins;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.PluginManager;
import hudson.PluginWrapper;
import hudson.ProxyConfiguration;
import hudson.model.UpdateSite;
import hudson.util.VersionNumber;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.casc.Attribute;
import org.jenkinsci.plugins.casc.Configurator;
import org.jenkinsci.plugins.casc.PersistedListAttribute;
import org.jenkinsci.plugins.casc.RootElementConfigurator;

import java.net.UnknownHostException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Created by mads on 1/29/18.
 */
@Extension(ordinal = Double.MAX_VALUE, optional = true)
public class PluginConfigurator implements RootElementConfigurator {

    private static final Logger LOGGER = Logger.getLogger(PluginConfigurator.class.getName());

    @Override
    public PluginManager configure(Object config) throws Exception {
        Map<?,?> map = (Map) config;

        //Proxy is optional
        if(map.containsKey("proxy")) {
            Configurator<ProxyConfiguration> pc = Configurator.lookup(ProxyConfiguration.class);
            ProxyConfiguration pcc = pc.configure(map.get("proxy"));
            Jenkins.getInstance().proxy = pcc;
        }

        HashMap<String, UpdateSite> allUpdateSites = new HashMap<>();
        Configurator<UpdateSiteInfo> configUpdateInfo = Configurator.lookup(UpdateSiteInfo.class);

        //Reuse the default update center
        List<UpdateSite> sites = Jenkins.getInstance().getUpdateCenter().getSites();
        for(UpdateSite us : sites) {
            if(us.getId().equals("default")) {
                allUpdateSites.put(us.getId(), us);
            }
        }

        //Extra sites from configuration
        if(map.containsKey("updateSites")) {
            Configurator<UpdateSiteInfo> updateSiteConfiguratorConfigurator = Configurator.lookup(UpdateSiteInfo.class);
            for (Object o : (Collection) map.get("updateSites")) {
                UpdateSiteInfo usi = updateSiteConfiguratorConfigurator.configure(o);
                allUpdateSites.put(usi.getId(), usi.toUpdateSiteObject());
            }
        }

        //Clear all
        sites.clear();

        //Add ones from configuration
        sites.addAll(allUpdateSites.values());
        Jenkins.getInstance().save();

        //Do check to see if we have installed the required plugins
        StringBuilder existingPlugins = new StringBuilder();

        HashMap<String,VersionNumber> requiredPlugins = new HashMap<>();
        Configurator<RequiredPluginInfo> requiredPluginInfoConfigurator = Configurator.lookup(RequiredPluginInfo.class);
        //Required plugins list is optional
        if(map.containsKey("required")) {
            for (Object reqPlug : (Collection)map.get("required")) {
                RequiredPluginInfo rInfo = requiredPluginInfoConfigurator.configure(reqPlug);
                requiredPlugins.put(rInfo.getPluginId(), rInfo.toVersionNumberObject());
            }
        }

        //TODO: Much of this will change. Until we get a proper way to install plugins
        List<String> pluginsToInstall = new ArrayList<>();
        for(Map.Entry<String,VersionNumber> requiredPlugin : requiredPlugins.entrySet()) {
            PluginWrapper plugin = Jenkins.getInstance().getPluginManager().getPlugin(requiredPlugin.getKey());
            if(plugin == null) {
                LOGGER.info("Missing plugin: "+requiredPlugin.getKey()+". Adding to list of plugins to install");
                pluginsToInstall.add(requiredPlugin.getKey());
            } else if (plugin.getVersionNumber().isNewerThan(requiredPlugin.getValue())) {
                LOGGER.info(String.format("Plugin '%s' is up new than specified in configuration-as-code plugin. OK", requiredPlugin.getKey()));
            } else if (plugin.getVersionNumber().isOlderThan(requiredPlugin.getValue())) {
                LOGGER.info(String.format("Required plugin %s(%s) is older than the required version: %s. Installing newest",
                        plugin.getShortName(),
                        plugin.getVersion(),
                        requiredPlugin.getValue()));
                pluginsToInstall.add(requiredPlugin.getKey());
            } else {
                LOGGER.info(String.format("Plugin '%s' is up to date. OK", requiredPlugin.getKey()));
            }
        }

        //Install missing and/or plugins that need update plugins
        try {
            Jenkins.getInstance().getPluginManager().doCheckUpdatesServer();
        } catch (UnknownHostException ex) {
            LOGGER.fine("Unable to contact update site: "+ex.getMessage());
        }
        Jenkins.getInstance().getPluginManager().install(pluginsToInstall, true);

        //Restart if necessary
        if(Jenkins.getInstance().getUpdateCenter().isRestartRequiredForCompletion()) {
            Jenkins.getInstance().restart();
        }

        return Jenkins.getInstance().getPluginManager();
    }

    @Override
    public String getName() {
        return "plugins";
    }

    /**
     * A set of fake attributes for the PluginManager. When configuring plugins we need the PluginManager to find
     * installed plugins but to update UpdateSites we need to configure the UpdateCenter list on the Jenkins instance.
     * @return
     */
    @Override
    @SuppressFBWarnings(value="DM_NEW_FOR_GETCLASS", justification="one can't get a parameterized type .class")
    public Set<Attribute> describe() {
        Set<Attribute> attr =  new HashSet<Attribute>();
        attr.add(new Attribute("proxy", ProxyConfiguration.class));
        attr.add(new Attribute("updateSites", new ArrayList<UpdateSiteInfo>().getClass()));
        attr.add(new Attribute("required", new ArrayList<RequiredPluginInfo>().getClass()));
        return attr;
    }
}
