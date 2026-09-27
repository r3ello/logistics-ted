-- Adds the `exporter` role: an external account that may download CSV exports and nothing else.
--
-- The read-only counterpart of `importer` (V4). Exports carry personal data (who worked where, for
-- how long), so the account that pulls them into a client's payroll/timesheet tooling gets exactly
-- that surface and not the dispatcher/admin application.
--
-- Authorisation is enforced in two places, both of which must list the role:
--   * SecurityConfig   — .requestMatchers("/api/export/**").hasAnyRole("ADMIN", "EXPORTER"), plus the
--                        GET matcher on /api/import/openapi.{yaml,json} so it can read the contract
--   * ExportController — @PreAuthorize("hasAnyRole('ADMIN','EXPORTER')")
-- Everything else under /api/** stays on .hasAnyRole("ADMIN","USER"), so an `exporter` token is
-- rejected there by the filter chain.
--
-- 'exporter' is 8 characters; the column is varchar(20) since V7.

ALTER TABLE public.app_user DROP CONSTRAINT IF EXISTS chk_app_user_role;

ALTER TABLE public.app_user
    ADD CONSTRAINT chk_app_user_role CHECK (role IN ('admin', 'user', 'importer', 'qa_inspector', 'exporter'));

-- No account is seeded on purpose (a known password in every environment). Create it by hand:
--
--   INSERT INTO public.app_user (username, password_hash, role)
--   VALUES ('exporter', '$2b$10$...', 'exporter');
