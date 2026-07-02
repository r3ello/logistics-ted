package com.bellgado.logistics_ted.security;

import com.bellgado.logistics_ted.domain.AppUser;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import com.bellgado.logistics_ted.repository.AppUserRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
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
    private final WorkerRepository  workers;

    public AppUserDetailsService(AppUserRepository users, WorkerRepository workers) {
        this.users   = users;
        this.workers = workers;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        // Try admin users first
        AppUser u = users.findByUsername(username).orElse(null);
        if (u != null) {
            String role = "ROLE_" + u.getRole().toUpperCase();
            return new AuthenticatedUser(u.getId(), u.getUsername(), u.getPasswordHash(),
                u.getRole(), List.of(new SimpleGrantedAuthority(role)));
        }
        // Fall back to crew leaders with credentials set
        Worker w = workers.findByUsername(username).orElse(null);
        if (w != null && w.getRole() == WorkerRole.CREW_LEADER && w.getPasswordHash() != null) {
            return new AuthenticatedUser(w.getId(), w.getUsername(), w.getPasswordHash(),
                "crew_leader", List.of(new SimpleGrantedAuthority("ROLE_CREW_LEADER")));
        }
        throw new UsernameNotFoundException("Invalid credentials.");
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
