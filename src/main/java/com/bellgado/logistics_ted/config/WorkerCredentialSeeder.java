package com.bellgado.logistics_ted.config;

import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WorkerCredentialSeeder {

    private final WorkerRepository workers;
    private final PasswordEncoder  encoder;

    public WorkerCredentialSeeder(WorkerRepository workers, PasswordEncoder encoder) {
        this.workers = workers;
        this.encoder = encoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        List<Worker> all = workers.findAll();
        Random rng = new Random();
        Map<String, Integer> usedNames = new HashMap<>();
        all.stream()
           .filter(w -> w.getUsername() != null)
           .forEach(w -> usedNames.put(w.getUsername(), 1));

        for (Worker w : all) {
            // backfill plain password for workers already seeded without it
            if (w.getUsername() != null && w.getPasswordPlain() == null && w.getPasswordHash() != null) {
                String pin = String.format("%04d", 1000 + rng.nextInt(9000));
                w.setPasswordHash(encoder.encode(pin));
                w.setPasswordPlain(pin);
                workers.save(w);
                continue;
            }
            if (w.getUsername() != null) continue;

            String base = firstNameLower(w.getName());
            String username = base;
            int suffix = 2;
            while (usedNames.containsKey(username)) {
                username = base + suffix++;
            }
            usedNames.put(username, 1);

            String pin = String.format("%04d", 1000 + rng.nextInt(9000));
            w.setUsername(username);
            w.setPasswordHash(encoder.encode(pin));
            w.setPasswordPlain(pin);
            workers.save(w);
        }
    }

    private String firstNameLower(String fullName) {
        if (fullName == null || fullName.isBlank()) return "worker";
        String first = fullName.trim().split("\\s+")[0];
        return first.toLowerCase();
    }
}
