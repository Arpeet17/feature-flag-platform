package com.ffp.feature_flag_platform.cluster;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Random identity of this process, used to recognise (and skip) our own cluster messages. */
@Component
public class InstanceId {

    private final String value = UUID.randomUUID().toString();

    public String value() {
        return value;
    }
}
