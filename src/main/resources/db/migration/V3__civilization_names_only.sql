ALTER TABLE civilizations
    DROP INDEX uq_civ_tag,
    DROP COLUMN normalized_tag,
    DROP COLUMN tag;
