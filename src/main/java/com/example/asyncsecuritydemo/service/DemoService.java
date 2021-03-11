package com.example.asyncsecuritydemo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.concurrent.Future;

@Service
@Slf4j
public class DemoService {

    @Async
    public Future<String> hello(String username) throws InterruptedException {
        String usernameFromContext = SecurityContextHolder.getContext().getAuthentication().getName();
        log.info("Username param: {} and context: {}", username, usernameFromContext);
        Thread.sleep(10);
        return new AsyncResult<String>(usernameFromContext);
    }
}
