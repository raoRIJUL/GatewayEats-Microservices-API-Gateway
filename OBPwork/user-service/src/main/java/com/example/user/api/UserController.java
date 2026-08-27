package com.example.user.api;

import com.example.user.persistence.Role;
import com.example.user.security.JwtTokenService;
import com.example.user.service.UserAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService tokenService;
    private final UserAccountService userAccountService;

    public UserController(AuthenticationManager authenticationManager, JwtTokenService tokenService,
                          UserAccountService userAccountService) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.userAccountService = userAccountService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserAccountService.UserView register(@Valid @RequestBody RegistrationRequest request) {
        return userAccountService.register(request.name(), request.email(), request.password(), request.role());
    }

    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        JwtTokenService.IssuedToken token = tokenService.issue(authentication);
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds());
    }

    @GetMapping("/{id}")
    UserAccountService.UserView get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return userAccountService.getOwnUser(id, jwt.getSubject());
    }

    @PutMapping("/{id}")
    UserAccountService.UserView update(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody UpdateUserRequest request) {
        return userAccountService.updateOwnUser(id, jwt.getSubject(), request.name(), request.email());
    }

    public record RegistrationRequest(
            @NotBlank @Size(min = 2, max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotNull Role role) { }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) { }

    public record UpdateUserRequest(
            @NotBlank @Size(min = 2, max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email) { }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) { }
}
