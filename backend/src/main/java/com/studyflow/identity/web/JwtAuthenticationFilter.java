package com.studyflow.identity.web;

import com.studyflow.identity.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reads {@code Authorization: Bearer <token>}, validates it, and populates the SecurityContext. */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * Tutor chat's SSE response completes on a background thread (see
     * {@code TutorChatController}), which re-enters the filter chain as an ASYNC dispatch on a
     * different servlet container thread than the one that originally ran this filter. {@code
     * SecurityContextHolder}'s ThreadLocal doesn't carry over, so without this override Spring
     * Security's own {@code AuthorizationFilter} (which, unlike this filter, does re-run on ASYNC
     * dispatch) finds no {@code Authentication} on that thread and rejects the otherwise-successful
     * response with {@code AuthorizationDeniedException}. Re-reading the same request's
     * {@code Authorization} header again here is cheap and correct — it's the same
     * {@code HttpServletRequest}, carrying the same header, regardless of dispatch phase.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parseAndValidate(token);
                String userId = claims.getSubject();
                String role = claims.get("role", String.class);
                List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException invalidToken) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
