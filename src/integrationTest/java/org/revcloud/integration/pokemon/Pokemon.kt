package org.revcloud.integration.pokemon

data class Pokemon(val name: String)

data class Results(val results: List<Pokemon>)

data class Ability(val name: String)

data class AbilityWrapper(val ability: Ability)

data class Abilities(val abilities: List<AbilityWrapper>)
