package com.example.asyncsecuritydemo.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
@Profile("simple")
public class DemoConfigurationSimple {
}
