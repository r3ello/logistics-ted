package com.bellgado.logistics_ted.security;

import com.bellgado.logistics_ted.domain.AppUser;
import com.bellgado.logistics_ted.repository.AppUserRepository;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser u = users.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials."));
        String role = "ROLE_" + u.getRole().toUpperCase();
        return new AuthenticatedUser(
            u.getId(),
            u.getUsername(),
            u.getPasswordHash(),
            u.getRole(),
            List.of(new SimpleGrantedAuthority(role))
        );
    }

    /** Extends Spring's User to carry the DB id and original role string for the /api/me response. */
    public static class AuthenticatedUser extends User {
        private final Integer userId;
        private final String roleLabel;

        public AuthenticatedUser(Integer userId, String username, String password, String roleLabel,
                                 List<SimpleGrantedAuthority> authorities) {
            super(username, password, authorities);
            this.userId = userId;
            this.roleLabel = roleLabel;
        }

        public Integer getUserId() {
            return userId;
        }

        public String getRoleLabel() {
            return roleLabel;
        }
    }
}
