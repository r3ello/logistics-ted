-- Adds the `importer` role: an account that may use the CSV import API and nothing else.
--
-- The point of the role is separation of duties. The data import is operated by whoever maintains
-- the client's spreadsheets, and that person has no business reaching the dispatcher/admin surface —
-- houses, crews, workers, orders, the audit log. Before this, using the import API required `admin`,
-- which handed them everything.
--
-- Authorisation is enforced in two places, both of which must list the role:
--   * SecurityConfig    — .requestMatchers("/api/import/**").hasAnyRole("ADMIN", "IMPORTER")
--   * ImportController / ImportDocsController — @PreAuthorize("hasAnyRole('ADMIN','IMPORTER')")
-- Everything else under /api/** stays on .hasAnyRole("ADMIN","USER"), so an `importer` token is
-- rejected there by the filter chain, not merely hidden in the UI.
--
-- No application code maps role names: AppUserDetailsService builds the authority generically as
-- "ROLE_" + role.toUpperCase(), so 'importer' becomes ROLE_IMPORTER with no change there.
--
-- 'importer' is 8 characters and fits the existing varchar(10).

ALTER TABLE public.app_user DROP CONSTRAINT IF EXISTS chk_app_user_role;

ALTER TABLE public.app_user
    ADD CONSTRAINT chk_app_user_role CHECK (role IN ('admin', 'user', 'importer'));

-- No account is seeded here on purpose: seeding one would put a known password into every
-- environment this migration runs against, including production. Create it by hand:
--
--   INSERT INTO public.app_user (username, password_hash, role)
--   VALUES ('importer', '$2b$10$...', 'importer');
--
-- The hash must be bcrypt — the application's BCryptPasswordEncoder accepts the $2a$, $2b$ and $2y$
-- variants. Generate one with any bcrypt tool at cost 10.
