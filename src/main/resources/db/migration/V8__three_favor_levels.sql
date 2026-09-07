-- Preserve existing relationships under the new three-level favor cap.
-- Safe to rerun if startup stopped before the migration history was recorded.
UPDATE deity_favor SET favor_level = 3 WHERE favor_level > 3;
