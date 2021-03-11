package com.example.asyncsecuritydemo.controller;

import com.example.asyncsecuritydemo.service.DemoService;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {

    @Autowired
    DemoService demoService;

    @SneakyThrows
    @RequestMapping("/name/{username}")
    public String doSomethingA(@PathVariable String username) {
        return demoService.hello(username).get();
    }
}
