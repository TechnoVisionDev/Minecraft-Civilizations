CREATE TABLE IF NOT EXISTS civ_advancement_credits (
    civ_id BIGINT UNSIGNED NOT NULL,
    advancement_key VARCHAR(190) NOT NULL,
    knowledge_value INT UNSIGNED NOT NULL,
    credited_by BINARY(16) NULL,
    credited_at DATETIME(6) NOT NULL,
    PRIMARY KEY (civ_id, advancement_key),
    CONSTRAINT fk_civ_advancement_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO civ_advancement_credits(civ_id, advancement_key, knowledge_value, credited_by, credited_at)
SELECT credited_civ_id, advancement_key, MAX(knowledge_value), NULL, MIN(credited_at)
FROM player_advancement_credits
GROUP BY credited_civ_id, advancement_key;

CREATE TABLE IF NOT EXISTS work_orders (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    civ_id BIGINT UNSIGNED NOT NULL,
    category VARCHAR(16) NOT NULL,
    order_key VARCHAR(64) NOT NULL,
    target BIGINT UNSIGNED NOT NULL,
    progress BIGINT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    reward INT UNSIGNED NOT NULL,
    config_snapshot JSON NOT NULL,
    generated_at DATETIME(6) NOT NULL,
    cooldown_until DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    completed_by BINARY(16) NULL,
    KEY idx_work_orders_active (civ_id, status, category),
    KEY idx_work_orders_history (civ_id, category, completed_at),
    CONSTRAINT fk_persistent_work_order_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS persistent_work_order_contributions (
    order_id BIGINT UNSIGNED NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    contribution BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (order_id, player_uuid),
    CONSTRAINT fk_persistent_contribution_order FOREIGN KEY (order_id) REFERENCES work_orders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
