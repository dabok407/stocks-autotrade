package com.example.stocks.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private final CustomUserDetailsService userDetailsService;

    public SecurityConfig(CustomUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.userDetailsService(userDetailsService).passwordEncoder(passwordEncoder());
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .authorizeRequests()
                .antMatchers("/login", "/api/auth/**", "/api/auth/sso-login").permitAll()
                .antMatchers("/css/**", "/js/**", "/favicon.ico").permitAll()
                .antMatchers("/api/backtest/**", "/api/strategies", "/api/intervals").permitAll()
                .antMatchers("/api/report/**").permitAll()
                .antMatchers("/api/candle-export/**").permitAll()
                .antMatchers("/api/krx/overtime-rank/**").permitAll()
                .anyRequest().authenticated()
            .and()

            .formLogin().disable()
            .httpBasic().disable()

            .exceptionHandling()
                .authenticationEntryPoint((req, resp, ex) -> {
                    String accept = req.getHeader("Accept");
                    String xReq = req.getHeader("X-Requested-With");
                    String ctx = req.getContextPath();
                    boolean isApi = req.getRequestURI().startsWith(ctx + "/api/")
                            || "XMLHttpRequest".equals(xReq)
                            || (accept != null && accept.contains("application/json"));
                    if (isApi) {
                        resp.setStatus(401);
                        resp.setContentType("application/json;charset=UTF-8");
                        resp.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다. 로그인해주세요.\"}");
                    } else {
                        resp.sendRedirect(ctx + "/login");
                    }
                })
            .and()

            .logout()
                .logoutUrl("/api/auth/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("STOCKS_SESSION")
            .and()

            .csrf()
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringAntMatchers("/api/auth/login", "/api/auth/pubkey", "/api/backtest/**", "/api/report/**", "/api/candle-export/**",
                        "/api/bot/**", "/api/keys/kis/**",
                        "/api/scanner/**", "/api/dashboard/**", "/api/config/**",
                        "/api/krx-opening/**", "/api/krx-allday/**", "/api/krx-morning-rush/**",
                        "/api/nyse-opening/**", "/api/nyse-allday/**", "/api/nyse-morning-rush/**")
            .and()

            .headers()
                .frameOptions().deny()
            .and()

            .sessionManagement()
                .maximumSessions(1)
                    .maxSessionsPreventsLogin(false)
                    .expiredUrl("/login?expired")
                    .sessionRegistry(sessionRegistry());
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}
