CREATE TABLE IF NOT EXISTS schema_history (
    version INT UNSIGNED NOT NULL PRIMARY KEY,
    description VARCHAR(190) NOT NULL,
    checksum CHAR(64) NOT NULL,
    installed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    success BOOLEAN NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS civilizations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(24) NOT NULL,
    normalized_name VARCHAR(24) NOT NULL,
    tag VARCHAR(5) NOT NULL,
    normalized_tag VARCHAR(5) NOT NULL,
    leader_uuid BINARY(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    capital_world_uuid BINARY(16) NOT NULL,
    capital_world_name VARCHAR(128) NOT NULL,
    capital_chunk_x INT NOT NULL,
    capital_chunk_z INT NOT NULL,
    home_x DOUBLE NOT NULL,
    home_y DOUBLE NOT NULL,
    home_z DOUBLE NOT NULL,
    home_yaw FLOAT NOT NULL,
    home_pitch FLOAT NOT NULL,
    default_plot_price DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    treasury_balance DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    knowledge_balance BIGINT UNSIGNED NOT NULL DEFAULT 0,
    peace_shield_until DATETIME(6) NULL,
    current_war_id BIGINT UNSIGNED NULL,
    admin_claim_bonus INT NOT NULL DEFAULT 0,
    capital_moved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    disbanded_at DATETIME(6) NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    UNIQUE KEY uq_civ_name (normalized_name),
    UNIQUE KEY uq_civ_tag (normalized_tag),
    KEY idx_civ_leader (leader_uuid),
    KEY idx_civ_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS civ_coordination_locks (
    lock_key VARCHAR(64) NOT NULL PRIMARY KEY,
    description VARCHAR(190) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO civ_coordination_locks(lock_key, description)
VALUES ('founding', 'Serializes founding distance and identity checks across server processes');

CREATE TABLE IF NOT EXISTS civ_members (
    civ_id BIGINT UNSIGNED NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    last_known_name VARCHAR(16) NOT NULL,
    role VARCHAR(16) NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    last_active_at DATETIME(6) NOT NULL,
    eligible_playtime_seconds BIGINT UNSIGNED NOT NULL DEFAULT 0,
    established BOOLEAN NOT NULL DEFAULT FALSE,
    established_checked_at DATETIME(6) NULL,
    contribution_total BIGINT UNSIGNED NOT NULL DEFAULT 0,
    membership_locked BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (civ_id, player_uuid),
    UNIQUE KEY uq_active_member (player_uuid),
    KEY idx_member_civ_role (civ_id, role),
    CONSTRAINT fk_member_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_membership_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    civ_id BIGINT UNSIGNED NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    left_at DATETIME(6) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    cooldown_until DATETIME(6) NULL,
    KEY idx_history_player (player_uuid, left_at),
    CONSTRAINT fk_history_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS member_activity_daily (
    civ_id BIGINT UNSIGNED NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    activity_date DATE NOT NULL,
    active_seconds BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (civ_id, player_uuid, activity_date),
    CONSTRAINT fk_activity_member FOREIGN KEY (civ_id, player_uuid)
        REFERENCES civ_members(civ_id, player_uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS civ_invites (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    civ_id BIGINT UNSIGNED NOT NULL,
    target_uuid BINARY(16) NOT NULL,
    target_name VARCHAR(16) NOT NULL,
    inviter_uuid BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    UNIQUE KEY uq_invite_pair_status (civ_id, target_uuid, status),
    KEY idx_invite_target (target_uuid, status, expires_at),
    CONSTRAINT fk_invite_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS civ_claims (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    world_uuid BINARY(16) NOT NULL,
    world_name VARCHAR(128) NOT NULL,
    chunk_x INT NOT NULL,
    chunk_z INT NOT NULL,
    civ_id BIGINT UNSIGNED NOT NULL,
    plot_type VARCHAR(16) NOT NULL,
    plot_owner_uuid BINARY(16) NULL,
    listing_kind VARCHAR(16) NULL,
    listing_seller_uuid BINARY(16) NULL,
    listing_price DECIMAL(19,2) NULL,
    original_purchase_price DECIMAL(19,2) NULL,
    plot_flags JSON NULL,
    home_label VARCHAR(32) NULL,
    greeting VARCHAR(160) NULL,
    acquisition_source VARCHAR(16) NOT NULL,
    claim_cost_snapshot JSON NULL,
    claimed_at DATETIME(6) NOT NULL,
    claimed_by BINARY(16) NULL,
    listed_at DATETIME(6) NULL,
    purchased_at DATETIME(6) NULL,
    purchased_by BINARY(16) NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    UNIQUE KEY uq_claim_chunk (world_uuid, chunk_x, chunk_z),
    KEY idx_claim_civ (civ_id),
    KEY idx_claim_owner (plot_owner_uuid),
    CONSTRAINT fk_claim_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS plot_trust (
    claim_id BIGINT UNSIGNED NOT NULL,
    trusted_player_uuid BINARY(16) NOT NULL,
    permission_mask INT UNSIGNED NOT NULL DEFAULT 7,
    granted_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (claim_id, trusted_player_uuid),
    CONSTRAINT fk_plot_trust_claim FOREIGN KEY (claim_id) REFERENCES civ_claims(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS civ_stockpile (
    civ_id BIGINT UNSIGNED NOT NULL,
    resource_key VARCHAR(48) NOT NULL,
    tier TINYINT UNSIGNED NOT NULL,
    quantity BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (civ_id, resource_key, tier),
    CONSTRAINT fk_stockpile_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS stockpile_ledger (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operation_id BINARY(16) NOT NULL,
    civ_id BIGINT UNSIGNED NOT NULL,
    actor_uuid BINARY(16) NULL,
    resource_key VARCHAR(48) NOT NULL,
    tier TINYINT UNSIGNED NOT NULL,
    delta BIGINT NOT NULL,
    reason VARCHAR(32) NOT NULL,
    related_type VARCHAR(32) NULL,
    related_id VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_stockpile_operation_resource (operation_id, resource_key, tier),
    KEY idx_stockpile_ledger_civ_time (civ_id, created_at),
    CONSTRAINT fk_stockpile_ledger_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_advancement_credits (
    player_uuid BINARY(16) NOT NULL,
    advancement_key VARCHAR(190) NOT NULL,
    credited_civ_id BIGINT UNSIGNED NOT NULL,
    knowledge_value INT UNSIGNED NOT NULL,
    credited_at DATETIME(6) NOT NULL,
    PRIMARY KEY (player_uuid, advancement_key),
    KEY idx_advancement_civ (credited_civ_id),
    CONSTRAINT fk_advancement_civ FOREIGN KEY (credited_civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS civ_technologies (
    civ_id BIGINT UNSIGNED NOT NULL,
    technology_key VARCHAR(64) NOT NULL,
    unlocked_at DATETIME(6) NOT NULL,
    unlocked_by BINARY(16) NULL,
    source VARCHAR(24) NOT NULL,
    PRIMARY KEY (civ_id, technology_key),
    CONSTRAINT fk_technology_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS research_queue (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    civ_id BIGINT UNSIGNED NOT NULL,
    queue_slot TINYINT UNSIGNED NOT NULL,
    technology_key VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    started_by BINARY(16) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completes_at DATETIME(6) NOT NULL,
    cost_snapshot JSON NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    UNIQUE KEY uq_research_slot (civ_id, queue_slot),
    UNIQUE KEY uq_research_technology (civ_id, technology_key),
    KEY idx_research_due (state, completes_at),
    CONSTRAINT fk_research_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS daily_work_orders (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    civ_id BIGINT UNSIGNED NOT NULL,
    game_date DATE NOT NULL,
    slot TINYINT UNSIGNED NOT NULL,
    order_key VARCHAR(64) NOT NULL,
    order_type VARCHAR(16) NOT NULL,
    target BIGINT UNSIGNED NOT NULL,
    progress BIGINT UNSIGNED NOT NULL DEFAULT 0,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    reward INT UNSIGNED NOT NULL,
    config_snapshot JSON NOT NULL,
    completed_at DATETIME(6) NULL,
    UNIQUE KEY uq_work_order_slot (civ_id, game_date, slot),
    KEY idx_work_order_civ_date (civ_id, game_date),
    CONSTRAINT fk_work_order_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS work_order_contributions (
    order_id BIGINT UNSIGNED NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    contribution BIGINT UNSIGNED NOT NULL DEFAULT 0,
    eligible_contribution BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (order_id, player_uuid),
    CONSTRAINT fk_contribution_order FOREIGN KEY (order_id) REFERENCES daily_work_orders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS wars (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attacker_civ_id BIGINT UNSIGNED NOT NULL,
    defender_civ_id BIGINT UNSIGNED NOT NULL,
    state VARCHAR(16) NOT NULL,
    declaration_actor BINARY(16) NOT NULL,
    declared_at DATETIME(6) NOT NULL,
    scheduled_start DATETIME(6) NOT NULL,
    scheduled_end DATETIME(6) NOT NULL,
    timezone_id VARCHAR(64) NOT NULL,
    cancellation_grace_until DATETIME(6) NOT NULL,
    objective_lock_at DATETIME(6) NOT NULL,
    declaration_cost_snapshot JSON NOT NULL,
    truce_until DATETIME(6) NULL,
    result VARCHAR(32) NULL,
    winning_civ_id BIGINT UNSIGNED NULL,
    resolution_metadata JSON NULL,
    attacker_peace BOOLEAN NOT NULL DEFAULT FALSE,
    defender_peace BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at DATETIME(6) NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    KEY idx_war_attacker_state (attacker_civ_id, state),
    KEY idx_war_defender_state (defender_civ_id, state),
    KEY idx_war_schedule (state, scheduled_start, scheduled_end),
    CONSTRAINT fk_war_attacker FOREIGN KEY (attacker_civ_id) REFERENCES civilizations(id),
    CONSTRAINT fk_war_defender FOREIGN KEY (defender_civ_id) REFERENCES civilizations(id),
    CONSTRAINT fk_war_winner FOREIGN KEY (winning_civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS war_roster (
    war_id BIGINT UNSIGNED NOT NULL,
    civ_id BIGINT UNSIGNED NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    role VARCHAR(16) NOT NULL,
    eligible BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (war_id, player_uuid),
    KEY idx_roster_civ (war_id, civ_id),
    CONSTRAINT fk_roster_war FOREIGN KEY (war_id) REFERENCES wars(id) ON DELETE CASCADE,
    CONSTRAINT fk_roster_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS war_objectives (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    war_id BIGINT UNSIGNED NOT NULL,
    nominating_civ_id BIGINT UNSIGNED NOT NULL,
    target_claim_id BIGINT UNSIGNED NOT NULL,
    frozen_world_uuid BINARY(16) NOT NULL,
    frozen_chunk_x INT NOT NULL,
    frozen_chunk_z INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    standard_world_uuid BINARY(16) NULL,
    standard_x INT NULL,
    standard_y INT NULL,
    standard_z INT NULL,
    accumulated_control_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
    last_progress_at DATETIME(6) NULL,
    secured_by BINARY(16) NULL,
    secured_at DATETIME(6) NULL,
    resolved_at DATETIME(6) NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    UNIQUE KEY uq_war_objective (war_id, nominating_civ_id, target_claim_id),
    KEY idx_objective_claim (target_claim_id),
    KEY idx_objective_standard (standard_world_uuid, standard_x, standard_y, standard_z),
    CONSTRAINT fk_objective_war FOREIGN KEY (war_id) REFERENCES wars(id) ON DELETE CASCADE,
    CONSTRAINT fk_objective_nominator FOREIGN KEY (nominating_civ_id) REFERENCES civilizations(id),
    CONSTRAINT fk_objective_claim FOREIGN KEY (target_claim_id) REFERENCES civ_claims(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS war_block_changes (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    war_id BIGINT UNSIGNED NOT NULL,
    actor_uuid BINARY(16) NULL,
    world_uuid BINARY(16) NOT NULL,
    block_x INT NOT NULL,
    block_y INT NOT NULL,
    block_z INT NOT NULL,
    action VARCHAR(16) NOT NULL,
    original_block_data VARCHAR(255) NULL,
    new_block_data VARCHAR(255) NULL,
    temporary BOOLEAN NOT NULL DEFAULT FALSE,
    cleaned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    KEY idx_war_changes_cleanup (war_id, temporary, cleaned),
    CONSTRAINT fk_block_change_war FOREIGN KEY (war_id) REFERENCES wars(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS economy_operations (
    operation_id BINARY(16) NOT NULL PRIMARY KEY,
    operation_type VARCHAR(32) NOT NULL,
    player_uuid BINARY(16) NULL,
    beneficiary_uuid BINARY(16) NULL,
    civ_id BIGINT UNSIGNED NULL,
    claim_id BIGINT UNSIGNED NULL,
    amount DECIMAL(19,2) NOT NULL,
    state VARCHAR(32) NOT NULL,
    retry_count INT UNSIGNED NOT NULL DEFAULT 0,
    provider_response VARCHAR(512) NULL,
    context_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_economy_state (state, updated_at),
    KEY idx_economy_player_type_state (player_uuid, operation_type, state, created_at),
    CONSTRAINT fk_economy_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id),
    CONSTRAINT fk_economy_claim FOREIGN KEY (claim_id) REFERENCES civ_claims(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS treasury_ledger (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operation_id BINARY(16) NOT NULL,
    civ_id BIGINT UNSIGNED NOT NULL,
    actor_uuid BINARY(16) NULL,
    beneficiary_uuid BINARY(16) NULL,
    amount DECIMAL(19,2) NOT NULL,
    prior_balance DECIMAL(19,2) NOT NULL,
    resulting_balance DECIMAL(19,2) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    related_type VARCHAR(32) NULL,
    related_id VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_treasury_operation (operation_id, reason),
    KEY idx_treasury_civ_time (civ_id, created_at),
    CONSTRAINT fk_treasury_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS civ_audit_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    civ_id BIGINT UNSIGNED NULL,
    actor_uuid BINARY(16) NULL,
    action_key VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NULL,
    target_id VARCHAR(96) NULL,
    metadata JSON NULL,
    server_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    KEY idx_audit_civ_time (civ_id, created_at),
    KEY idx_audit_actor_time (actor_uuid, created_at),
    KEY idx_audit_action_time (action_key, created_at),
    CONSTRAINT fk_audit_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_settings (
    player_uuid BINARY(16) NOT NULL PRIMARY KEY,
    last_known_name VARCHAR(16) NOT NULL,
    chat_mode BOOLEAN NOT NULL DEFAULT FALSE,
    notifications JSON NULL,
    locale VARCHAR(16) NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS civ_milestones (
    civ_id BIGINT UNSIGNED NOT NULL,
    milestone_key VARCHAR(64) NOT NULL,
    awarded_at DATETIME(6) NOT NULL,
    knowledge_value INT UNSIGNED NOT NULL,
    PRIMARY KEY (civ_id, milestone_key),
    CONSTRAINT fk_milestone_civ FOREIGN KEY (civ_id) REFERENCES civilizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
