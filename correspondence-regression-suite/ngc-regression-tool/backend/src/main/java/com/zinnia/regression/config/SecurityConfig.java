package com.zinnia.regression.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** One fixed account over HTTP Basic: UI, CLI and scripts all use the same credentials until real auth exists. */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                // 401 without WWW-Authenticate so the browser never shows its own login prompt; the UI has a login form
                .httpBasic(b -> b.authenticationEntryPoint((req, res, e) -> res.sendError(401, "Unauthorized")))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(@Value("${regression.auth.user}") String user,
                             @Value("${regression.auth.password:}") String password, PasswordEncoder encoder) {
        if (password.isBlank()) {
            throw new IllegalStateException("REGRESSION_PASSWORD must be set (add it to .env)");
        }
        return new InMemoryUserDetailsManager(User.withUsername(user).password(encoder.encode(password)).roles("USER").build());
    }
}
