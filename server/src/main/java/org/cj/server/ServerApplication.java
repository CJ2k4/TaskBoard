package org.cj.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// We define our own security in SecurityConfig, so we switch off Spring Boot's
// default in-memory user (the one that prints a random "generated security
// password" on startup). Real authentication arrives in M1.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }

}
