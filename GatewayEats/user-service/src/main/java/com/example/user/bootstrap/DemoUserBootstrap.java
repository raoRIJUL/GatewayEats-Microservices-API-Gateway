package com.example.user.bootstrap;

import com.example.user.persistence.Role;
import com.example.user.service.UserAccountService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "security.demo-users", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoUserBootstrap implements ApplicationRunner {

    private final UserAccountService users;

    public DemoUserBootstrap(UserAccountService users) {
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        users.ensureUser("Demo Customer", "customer@demo.com", "customer-password", Role.CUSTOMER);
        users.ensureUser("Demo Restaurant Owner", "owner@demo.com", "owner-password", Role.RESTAURANT_OWNER);
    }
}
