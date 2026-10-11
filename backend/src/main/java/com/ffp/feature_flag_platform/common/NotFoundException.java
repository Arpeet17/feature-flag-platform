package com.ffp.feature_flag_platform.common;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, String key) {
        super("%s '%s' not found".formatted(resource, key));
    }
}
