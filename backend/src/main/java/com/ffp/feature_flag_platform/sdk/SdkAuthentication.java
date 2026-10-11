package com.ffp.feature_flag_platform.sdk;

import com.ffp.feature_flag_platform.environment.SdkKeyResolver.SdkEnvironment;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/** Authentication for SDK requests: the principal is the environment the key belongs to. */
public class SdkAuthentication extends AbstractAuthenticationToken {

    public static final String AUTHORITY = "SDK";

    private final SdkEnvironment environment;

    public SdkAuthentication(SdkEnvironment environment) {
        super(AuthorityUtils.createAuthorityList(AUTHORITY));
        this.environment = environment;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public SdkEnvironment getPrincipal() {
        return environment;
    }

    @Override
    public String getName() {
        return "sdk:" + environment.projectKey() + "/" + environment.environmentKey();
    }
}
