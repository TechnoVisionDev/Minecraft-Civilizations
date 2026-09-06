DELETE FROM research_queue
WHERE technology_key NOT IN (
    'agriculture', 'animal_husbandry', 'seafaring', 'copperworking', 'archery', 'civic_planning',
    'horseback_riding', 'ironworking', 'scholarship', 'navigation', 'fletching', 'mechanical_transport',
    'engineering', 'fortification', 'administration_ii', 'redstone_engineering', 'nether_expedition',
    'diamondworking', 'enchanting', 'alchemy', 'administration_iii', 'advanced_alchemy',
    'end_expedition', 'aeronautics', 'netherite_smithing', 'imperial_administration'
);

DELETE FROM civ_technologies
WHERE technology_key NOT IN (
    'agriculture', 'animal_husbandry', 'seafaring', 'copperworking', 'archery', 'civic_planning',
    'horseback_riding', 'ironworking', 'scholarship', 'navigation', 'fletching', 'mechanical_transport',
    'engineering', 'fortification', 'administration_ii', 'redstone_engineering', 'nether_expedition',
    'diamondworking', 'enchanting', 'alchemy', 'administration_iii', 'advanced_alchemy',
    'end_expedition', 'aeronautics', 'netherite_smithing', 'imperial_administration'
);

DELETE FROM work_orders
WHERE status = 'ACTIVE' AND (
    category NOT IN ('BASIC', 'INDUSTRIAL', 'STRATEGIC')
    OR order_key NOT IN (
        'basic_cobblestone', 'basic_logs', 'basic_coal', 'basic_heavy_cobblestone', 'basic_bound_timber',
        'industrial_coal', 'industrial_copper', 'industrial_iron', 'industrial_reinforced_masonry',
        'industrial_engineered_timber', 'industrial_forged_components', 'strategic_redstone',
        'strategic_lapis', 'strategic_amethyst', 'strategic_tempered_mechanisms',
        'strategic_arcane_matrix', 'strategic_research_folio'
    )
);
