package org.graylog.plugins.auth.httpheaders;

import org.graylog2.plugin.Plugin;
import org.graylog2.plugin.PluginMetaData;
import org.graylog2.plugin.PluginModule;

import java.util.Collection;
import java.util.Collections;

/**
 * Implement the Plugin interface here.
 */
public class HttpHeadersAuthPlugin implements Plugin {
    @Override
    public PluginMetaData metadata() {
        return new HttpHeadersAuthMetaData();
    }

    @Override
    public Collection<PluginModule> modules () {
        return Collections.<PluginModule>singletonList(new HttpHeadersAuthModule());
    }
}
