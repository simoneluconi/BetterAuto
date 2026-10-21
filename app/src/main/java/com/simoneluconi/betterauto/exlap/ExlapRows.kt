package com.simoneluconi.betterauto.exlap

/**
 * Exlap values as label / value rows for the vehicle screen: one row per object, named after it
 * ("coolantTemperature" -> "Coolant temperature"), with its unit next to the value. The "unit" and "state"
 * members only describe the value, so they are not rows of their own; an object with several real values
 * gets one row each ("Object · member"). Objects with no value yet are left out.
 */
fun exlapRows(values: Collection<ExlapValue>): List<Pair<String, String>> =
    values.groupBy { it.url }.flatMap { (url, members) ->
        val unitMember = members.firstOrNull { it.name == "unit" }?.value
        val main = members.filter { it.name !in DESCRIPTIVE && it.value != null }
        main.map { member ->
            val label = if (main.size == 1) readable(url) else "${readable(url)} · ${readable(member.name)}"
            label to listOfNotNull(member.value, member.unit ?: unitMember).joinToString(" ")
        }
    }

/** "vehicleSpeed" -> "Vehicle speed", "System_activeSource" -> "System active source". */
fun readable(name: String): String = Labels.getOrPut(name) {
    name.replace(WordStart, "$1 $2").replace('_', ' ').trim().lowercase().replaceFirstChar { it.uppercase() }
}

private val WordStart = Regex("([a-z0-9])([A-Z])")

/** The car offers a fixed set of names, and rows are rebuilt on every update: each name is worked out once. */
private val Labels = java.util.concurrent.ConcurrentHashMap<String, String>()

private val DESCRIPTIVE = setOf("unit", "state")
