CREATE TABLE IF NOT EXISTS civ_plot_taxes (
    civ_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    weekly_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    changed_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_plot_tax_policy_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS plot_tax_accounts (
    claim_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenure_id BINARY(16) NOT NULL,
    civ_id BIGINT UNSIGNED NOT NULL,
    owner_uuid BINARY(16) NOT NULL,
    acquired_at DATETIME(6) NOT NULL,
    weekly_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    next_due DATETIME(6) NOT NULL,
    UNIQUE KEY uq_plot_tax_tenure (tenure_id),
    KEY idx_plot_tax_due (next_due, claim_id),
    CONSTRAINT fk_plot_tax_account_claim FOREIGN KEY (claim_id) REFERENCES civ_claims(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Keep bills after ownership changes or deletion of the original claim.
CREATE TABLE IF NOT EXISTS plot_tax_bills (
    operation_id BINARY(16) NOT NULL PRIMARY KEY,
    tenure_id BINARY(16) NOT NULL,
    claim_id BIGINT UNSIGNED NOT NULL,
    civ_id BIGINT UNSIGNED NOT NULL,
    owner_uuid BINARY(16) NOT NULL,
    world_name VARCHAR(128) NOT NULL,
    chunk_x INT NOT NULL,
    chunk_z INT NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    status VARCHAR(24) NOT NULL,
    settled_at DATETIME(6) NULL,
    notified BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY uq_plot_tax_bill_period (tenure_id, due_at),
    KEY idx_plot_tax_pending (status, due_at),
    KEY idx_plot_tax_notices (owner_uuid, notified, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
