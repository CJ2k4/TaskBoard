package org.cj.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// We define our own security in SecurityConfig, so we switch off Spring Boot's
// default in-memory user (the one that prints a random "generated security
// password" on startup). Real authentication arrives in M1.
//
// @EnableScheduling turns on the @Scheduled support that BinPurgeJob needs to empty
// the card bin once cards pass the retention window.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }

}
