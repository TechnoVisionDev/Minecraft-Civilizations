-- Favor starts at level 1 for every player/god, including existing worshippers.
-- Sacrifice outcomes, cooldowns and favor advance in the same transaction.
CREATE TABLE IF NOT EXISTS deity_favor (
    player_uuid BINARY(16) NOT NULL,
    god_key VARCHAR(64) NOT NULL,
    favor_level TINYINT UNSIGNED NOT NULL DEFAULT 1,
    PRIMARY KEY (player_uuid, god_key),
    CONSTRAINT chk_deity_favor_level CHECK (favor_level BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
