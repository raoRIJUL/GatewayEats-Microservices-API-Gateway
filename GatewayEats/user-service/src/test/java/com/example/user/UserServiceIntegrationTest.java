package com.example.user;

import com.example.user.persistence.Role;
import com.example.user.persistence.UserAccountRepository;
import com.example.user.security.JwtTokenService;
import com.example.user.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:users;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "security.demo-users.enabled=false"
})
@Transactional
class UserServiceIntegrationTest {

    @Autowired private UserAccountService users;
    @Autowired private UserAccountRepository repository;
    @Autowired private AuthenticationManager authenticationManager;
    @Autowired private JwtTokenService tokenService;
    @Autowired private JwtDecoder jwtDecoder;

    @Test
    void registersAuthenticatesAndIssuesUserIdJwt() {
        var registered = users.register(
                "Alice Customer", "ALICE@EXAMPLE.COM", "safe-password", Role.CUSTOMER);

        assertThat(registered.email()).isEqualTo("alice@example.com");
        assertThat(registered.role()).isEqualTo(Role.CUSTOMER);
        assertThat(repository.findByEmail("alice@example.com").orElseThrow().getPasswordHash())
                .isNotEqualTo("safe-password").startsWith("$2");

        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        "alice@example.com", "safe-password"));
        var issued = tokenService.issue(authentication);
        var jwt = jwtDecoder.decode(issued.value());

        assertThat(jwt.getSubject()).isEqualTo(Long.toString(registered.id()));
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("alice@example.com");
    }

    @Test
    void profileAccessIsLimitedToTheJwtUserId() {
        var registered = users.register(
                "Owner", "owner@example.com", "safe-password", Role.RESTAURANT_OWNER);

        assertThat(users.getOwnUser(registered.id(), Long.toString(registered.id())).name())
                .isEqualTo("Owner");
        assertThatThrownBy(() -> users.getOwnUser(registered.id(), "999"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
