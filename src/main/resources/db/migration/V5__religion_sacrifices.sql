CREATE TABLE IF NOT EXISTS sacrifice_cooldowns (
    player_uuid BINARY(16) NOT NULL,
    god_key VARCHAR(64) NOT NULL,
    operation_id BINARY(16) NOT NULL,
    available_at DATETIME(6) NOT NULL,
    last_sacrifice_at DATETIME(6) NOT NULL,
    last_offering VARCHAR(64) NOT NULL,
    last_amount INT UNSIGNED NOT NULL,
    last_roll TINYINT UNSIGNED NOT NULL,
    last_success BOOLEAN NOT NULL,
    PRIMARY KEY (player_uuid, god_key),
    UNIQUE KEY uq_sacrifice_operation (operation_id),
    KEY idx_sacrifice_available (available_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
