package com.ffp.feature_flag_platform.sdk;

import com.ffp.feature_flag_platform.environment.SdkKeyResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates SDK calls by environment SDK key, sent as {@code Authorization: <key>} (the
 * convention of most flag SDKs) or {@code X-SDK-Key}. Unauthenticated requests continue down
 * the chain and are rejected by the authorization rules with a 401.
 */
public class SdkKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-SDK-Key";

    private final SdkKeyResolver resolver;

    public SdkKeyAuthenticationFilter(SdkKeyResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null) {
            key = request.getHeader(HttpHeaders.AUTHORIZATION);
        }
        if (key != null) {
            resolver.resolve(key.trim()).ifPresent(env -> {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new SdkAuthentication(env));
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }
}
