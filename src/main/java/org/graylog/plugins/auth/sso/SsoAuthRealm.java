/**
 * This file is part of Graylog Archive.
 *
 * Graylog Archive is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog Archive is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog Archive.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog.plugins.auth.sso;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAccount;
import org.apache.shiro.authc.credential.AllowAllCredentialsMatcher;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.graylog2.database.NotFoundException;
import org.graylog2.plugin.cluster.ClusterConfigService;
import org.graylog2.plugin.database.ValidationException;
import org.graylog2.plugin.database.users.User;
import org.graylog2.security.realm.LdapUserAuthenticator;
import org.graylog2.shared.security.HttpHeadersToken;
import org.graylog2.shared.security.ShiroSecurityContext;
import org.graylog2.shared.users.Role;
import org.graylog2.shared.users.UserService;
import org.graylog2.users.RoleService;
import org.graylog2.utilities.IpSubnet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.core.MultivaluedMap;
import java.net.UnknownHostException;
import java.util.*;

public class SsoAuthRealm extends AuthenticatingRealm {
    private static final Logger LOG = LoggerFactory.getLogger(SsoAuthRealm.class);

    public static final String NAME = "sso";

    private final LdapUserAuthenticator ldapAuthenticator;

    private final UserService userService;
    private final ClusterConfigService clusterConfigService;
    private final RoleService roleService;
    private final Set<IpSubnet> trustedProxies;

    @Inject
    public SsoAuthRealm(UserService userService,
                        ClusterConfigService clusterConfigService,
                        RoleService roleService,
                        LdapUserAuthenticator ldapAuthenticator,
                        @Named("trusted_proxies") Set<IpSubnet> trustedProxies) {
        this.userService = userService;
        this.clusterConfigService = clusterConfigService;
        this.roleService = roleService;
        this.trustedProxies = trustedProxies;
        this.ldapAuthenticator = ldapAuthenticator;
        setAuthenticationTokenClass(HttpHeadersToken.class);
        setCredentialsMatcher(new AllowAllCredentialsMatcher());
        setCachingEnabled(false);
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        HttpHeadersToken headersToken = (HttpHeadersToken) token;
        final MultivaluedMap<String, String> requestHeaders = headersToken.getHeaders();

        final SsoAuthConfig config = clusterConfigService.getOrDefault(
                SsoAuthConfig.class,
                SsoAuthConfig.defaultConfig(""));

        final String usernameHeader = config.usernameHeader();

        final Optional<String> userNameOption = headerValue(requestHeaders, usernameHeader);
        if (userNameOption.isPresent()) {
            if (config.requireTrustedProxies()) {
                if (inTrustedSubnets(headersToken.getRemoteAddr())) {
                    LOG.info("Request with trusted header {} received from {} which is not in the trusted subnets: {}",
                             usernameHeader,
                             headersToken.getRemoteAddr(),
                             Joiner.on(", ").join(trustedProxies));
                    return null;
                }
            }
            final String username = userNameOption.get();
            User user = null;

            if (ldapAuthenticator.isEnabled()) {
                user = ldapAuthenticator.syncLdapUser(username);
            }

            if (user == null) {
                user = userService.load(username);
            }

            if (user == null) {
                if (config.autoCreateUser()) {
                    user = userService.create();

                    // common fields
                    user.setName(username);
                    user.setExternal(true);
                    user.setPassword("dummy password");
                    user.setPermissions(Collections.emptyList());

                    // fields based on optional headers
                    final Optional<String> fullnameHeaderOption = headerValue(requestHeaders, config.fullnameHeader());
                    user.setFullName(fullnameHeaderOption.orElse(username));

                    final Optional<String> emailHeaderOption = headerValue(requestHeaders, config.emailHeader());
                    if (emailHeaderOption.isPresent()) {
                        user.setEmail(emailHeaderOption.get());
                    } else {
                        user.setEmail(username + "@" + Optional.ofNullable(config.defaultEmailDomain()).orElse("localhost"));
                    }

                    try {
                        userService.save(user);
                    } catch (ValidationException e) {
                        LOG.error("Unable to save auto created user {}. Not logging in with http header.", user, e);
                        return null;
                    }
                } else {
                    LOG.trace(
                            "No user named {} found and automatic user creation is disabled, not using content of trusted header {}",
                            username,
                            usernameHeader);
                    return null;
                }
            }

            final String groupHeader = config.groupHeader();
            if (groupHeader != null) {
                try {
                    Map<String, Role> roles = roleService.loadAllLowercaseNameMap();
                    Set<String> ids = new HashSet<>();

                    for (String group : headerValue(requestHeaders, groupHeader)
                            .map(s -> StringUtils.split(s, ","))
                            .orElseGet(() -> new String[0])) {
                        Role role = roles.get(group.trim().toLowerCase());
                        if (role == null) continue;
                        ids.add(role.getId());
                    }

                    if (ids.size() > 0) {
                        user.setRoleIds(ids);
                    } else {
                        throw new NotFoundException();
                    }

                } catch (NotFoundException e) {
                    LOG.info("Unable to retrieve roles, giving user reader role");
                    user.setRoleIds(Collections.singleton(roleService.getReaderRoleObjectId()));
                }
            } else {
                user.setRoleIds(Collections.singleton(roleService.getReaderRoleObjectId()));
            }

            LOG.trace("Trusted header {} set, continuing with user name {}", usernameHeader, user.getName());

            ShiroSecurityContext.requestSessionCreation(true);
            return new SimpleAccount(user.getName(), null, NAME);
        }
        LOG.debug("Trusted header {} is not set.", usernameHeader);
        return null;
    }

    @VisibleForTesting
    @SuppressWarnings("WeakerAccess")
    boolean inTrustedSubnets(String remoteAddr) {
        return !trustedProxies.stream()
                .anyMatch(ipSubnet -> {
                    try {
                        return ipSubnet.contains(remoteAddr);
                    } catch (UnknownHostException ignored) {
                        LOG.debug("Looking up remote address {} failed.", remoteAddr);
                        return false;
                    }
                });
    }

    private Optional<String> headerValue(MultivaluedMap<String, String> headers, @Nullable String headerName) {
        if (headerName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(headers.getFirst(headerName.toLowerCase()));
    }

}
