package com.example.stocks.web;

import com.example.stocks.security.RsaKeyHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 15 * 60 * 1000L;
    private static final long SSO_TOKEN_TTL_MS = 30_000L; // SSO token TTL: 30 seconds

    private final ConcurrentHashMap<String, long[]> loginAttempts = new ConcurrentHashMap<String, long[]>();

    private final RsaKeyHolder rsaKeyHolder;
    private final AuthenticationManager authManager;
    private final SessionRegistry sessionRegistry;
    private final UserDetailsService userDetailsService;

    @Value("${sso.secret:}")
    private String ssoSecret;

    @Value("${sso.partnerUrl:}")
    private String ssoPartnerUrl;

    @Value("${sso.partnerLabel:}")
    private String ssoPartnerLabel;

    public AuthController(RsaKeyHolder rsaKeyHolder, AuthenticationManager authManager,
                          SessionRegistry sessionRegistry, UserDetailsService userDetailsService) {
        this.rsaKeyHolder = rsaKeyHolder;
        this.authManager = authManager;
        this.sessionRegistry = sessionRegistry;
        this.userDetailsService = userDetailsService;
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isLockedOut(String ip) {
        long[] info = loginAttempts.get(ip);
        if (info == null) return false;
        if (info[0] < MAX_LOGIN_ATTEMPTS) return false;
        if (System.currentTimeMillis() - info[1] > LOCKOUT_DURATION_MS) {
            loginAttempts.remove(ip);
            return false;
        }
        return true;
    }

    private void recordFailure(String ip) {
        long now = System.currentTimeMillis();
        loginAttempts.compute(ip, new java.util.function.BiFunction<String, long[], long[]>() {
            @Override
            public long[] apply(String key, long[] existing) {
                if (existing == null) return new long[]{1, now};
                if (System.currentTimeMillis() - existing[1] > LOCKOUT_DURATION_MS) {
                    return new long[]{1, now};
                }
                return new long[]{existing[0] + 1, now};
            }
        });
    }

    private void clearFailures(String ip) {
        loginAttempts.remove(ip);
    }

    @GetMapping("/login")
    public String loginPage(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "redirect:/dashboard";
        }
        return "login";
    }

    @GetMapping("/api/auth/pubkey")
    @ResponseBody
    public Map<String, String> publicKey() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("publicKey", rsaKeyHolder.getPublicKeyBase64());
        return m;
    }

    @PostMapping("/api/auth/login")
    @ResponseBody
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();

        String clientIp = getClientIp(request);
        if (isLockedOut(clientIp)) {
            long[] info = loginAttempts.get(clientIp);
            long remainSec = info != null ? (LOCKOUT_DURATION_MS - (System.currentTimeMillis() - info[1])) / 1000 : 0;
            log.warn("Login locked: ip={}, remaining={}s", clientIp, remainSec);
            result.put("success", false);
            result.put("message", "Too many login attempts. Try again in " + (remainSec / 60 + 1) + " minutes.");
            return result;
        }

        String username = body.get("username");
        String encryptedPassword = body.get("encryptedPassword");

        if (username == null || username.trim().isEmpty()
                || encryptedPassword == null || encryptedPassword.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "Please enter username and password.");
            return result;
        }

        String plainPassword = rsaKeyHolder.decrypt(encryptedPassword.trim());
        if (plainPassword == null) {
            log.warn("RSA decryption failed - username={}", username);
            result.put("success", false);
            result.put("message", "Security error. Please refresh the page.");
            return result;
        }

        try {
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken(username.trim(), plainPassword);
            Authentication auth = authManager.authenticate(token);

            List<SessionInformation> existingSessions =
                    sessionRegistry.getAllSessions(auth.getPrincipal(), false);
            for (SessionInformation si : existingSessions) {
                si.expireNow();
                log.info("Expired existing session: sessionId={}", si.getSessionId());
            }

            HttpSession oldSession = request.getSession(false);
            if (oldSession != null) {
                sessionRegistry.removeSessionInformation(oldSession.getId());
                oldSession.invalidate();
            }
            HttpSession newSession = request.getSession(true);
            newSession.setMaxInactiveInterval(3600 * 4);

            SecurityContextHolder.getContext().setAuthentication(auth);
            newSession.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());
            sessionRegistry.registerNewSession(newSession.getId(), auth.getPrincipal());

            clearFailures(clientIp);

            log.info("Login success: {} (session: {})", username, newSession.getId());
            result.put("success", true);
            result.put("redirect", request.getContextPath() + "/dashboard");
        } catch (BadCredentialsException e) {
            recordFailure(clientIp);
            long[] info = loginAttempts.get(clientIp);
            int remaining = MAX_LOGIN_ATTEMPTS - (info != null ? (int) info[0] : 0);
            log.warn("Login failed (bad credentials): {} ip={} remaining={}", username, clientIp, remaining);
            result.put("success", false);
            result.put("message", remaining > 0
                    ? "Invalid credentials. (Remaining: " + remaining + ")"
                    : "Too many attempts. Try again in 15 minutes.");
        } catch (DisabledException e) {
            result.put("success", false);
            result.put("message", "Account is disabled.");
        } catch (LockedException e) {
            result.put("success", false);
            result.put("message", "Account is locked.");
        } catch (Exception e) {
            log.error("Login error: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "Server error occurred.");
        }

        return result;
    }

    @GetMapping("/api/auth/check")
    @ResponseBody
    public Map<String, Object> checkAuth() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
        m.put("authenticated", authenticated);
        if (authenticated) {
            m.put("username", auth.getName());
        }
        return m;
    }

    /** SSO partner info (for FE button display) */
    @GetMapping("/api/auth/sso-info")
    @ResponseBody
    public Map<String, String> ssoInfo() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("partnerUrl", ssoPartnerUrl != null ? ssoPartnerUrl : "");
        m.put("partnerLabel", ssoPartnerLabel != null ? ssoPartnerLabel : "");
        m.put("enabled", String.valueOf(ssoSecret != null && !ssoSecret.isEmpty()));
        return m;
    }

    /** Generate SSO token for current authenticated user */
    @GetMapping("/api/auth/sso-token")
    @ResponseBody
    public Map<String, Object> generateSsoToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("success", false);
            err.put("message", "Authentication required.");
            return err;
        }
        if (ssoSecret == null || ssoSecret.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("success", false);
            err.put("message", "SSO is not configured.");
            return err;
        }
        String username = auth.getName();
        long timestamp = System.currentTimeMillis();
        String token = generateHmac(username, timestamp);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("token", token);
        result.put("username", username);
        result.put("timestamp", timestamp);
        return result;
    }

    /** SSO login: validate token and create session, redirect to dashboard */
    @GetMapping("/api/auth/sso-login")
    public String ssoLogin(@RequestParam("token") String token,
                           @RequestParam("username") String username,
                           @RequestParam("ts") long timestamp,
                           HttpServletRequest request, HttpServletResponse response) {

        if (ssoSecret == null || ssoSecret.isEmpty()) {
            log.warn("[SSO] SSO secret not configured");
            return "redirect:/login";
        }

        // Token expiry check (30 seconds)
        if (Math.abs(System.currentTimeMillis() - timestamp) > SSO_TOKEN_TTL_MS) {
            log.warn("[SSO] Token expired: username={}, ts={}", username, timestamp);
            return "redirect:/login";
        }

        // HMAC verification
        String expected = generateHmac(username, timestamp);
        if (expected == null || !expected.equals(token)) {
            log.warn("[SSO] Token verification failed: username={}", username);
            return "redirect:/login";
        }

        // Load user and create session
        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

            // Expire existing sessions
            List<SessionInformation> existingSessions =
                    sessionRegistry.getAllSessions(userDetails, false);
            for (SessionInformation si : existingSessions) {
                si.expireNow();
            }

            HttpSession oldSession = request.getSession(false);
            if (oldSession != null) {
                sessionRegistry.removeSessionInformation(oldSession.getId());
                oldSession.invalidate();
            }
            HttpSession newSession = request.getSession(true);
            newSession.setMaxInactiveInterval(3600 * 4);

            SecurityContextHolder.getContext().setAuthentication(auth);
            newSession.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());
            sessionRegistry.registerNewSession(newSession.getId(), userDetails);

            log.info("[SSO] SSO login success: {}", username);
            return "redirect:/dashboard";
        } catch (Exception e) {
            log.error("[SSO] SSO login error: {}", e.getMessage(), e);
            return "redirect:/login";
        }
    }

    /** HMAC-SHA256 token generation */
    private String generateHmac(String username, long timestamp) {
        try {
            String data = username + ":" + timestamp;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(ssoSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[SSO] HMAC generation error", e);
            return null;
        }
    }
}
