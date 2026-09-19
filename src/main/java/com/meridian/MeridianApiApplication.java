package com.meridian;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MeridianApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeridianApiApplication.class, args);
    }
}
