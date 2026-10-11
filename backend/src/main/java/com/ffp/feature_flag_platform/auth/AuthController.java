package com.ffp.feature_flag_platform.auth;

import com.ffp.feature_flag_platform.auth.AuthDtos.LoginRequest;
import com.ffp.feature_flag_platform.auth.AuthDtos.LoginResponse;
import com.ffp.feature_flag_platform.auth.AuthDtos.UserResponse;
import com.ffp.feature_flag_platform.common.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository users;
    private final TokenService tokens;

    public AuthController(AuthenticationManager authenticationManager, UserRepository users, TokenService tokens) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.tokens = tokens;
    }

    @Operation(summary = "Exchange email/password for a bearer token")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        User user = users.findByEmailIgnoreCase(request.email()).orElseThrow();
        TokenService.IssuedToken token = tokens.issue(user);
        return new LoginResponse(token.value(), "Bearer", token.expiresAt(), UserResponse.from(user));
    }

    @Operation(summary = "The currently authenticated user")
    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        return users.findByEmailIgnoreCase(authentication.getName())
                .map(UserResponse::from)
                .orElseThrow(() -> new NotFoundException("User", authentication.getName()));
    }
}
