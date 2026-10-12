package com.andreagrossetti.training.data

/**
 * What changed from [old] to [new], one readable line each (Italian, as shown in the app).
 * Exercises are matched by name, so a renamed exercise shows up as removed and added.
 */
fun programChanges(old: Program, new: Program): List<String> {
    val out = mutableListOf<String>()
    if (old.name != new.name) out += "Nome: ${old.name} → ${new.name}"
    when {
        old.rounds == null && new.rounds != null -> out += "Diventa un circuito da ${new.rounds} giri"
        old.rounds != null && new.rounds == null -> out += "Non più a circuito"
        old.rounds != new.rounds -> out += "Giri: ${old.rounds} → ${new.rounds}"
    }
    if (new.circuit && old.circuit && old.roundRest != new.roundRest) {
        out += "Recupero tra i giri: ${formatDuration(old.roundRest)} → ${formatDuration(new.roundRest)}"
    }

    val oldByName = old.exercises.associateBy { it.name }
    val newByName = new.exercises.associateBy { it.name }
    old.exercises.filter { it.name !in newByName }.forEach { out += "Tolto: ${it.name}" }
    new.exercises.filter { it.name !in oldByName }.forEach { out += "Aggiunto: ${it.name} (${it.summary()})" }

    // Moved: the common exercises outside the longest run that kept its relative order.
    val common = new.exercises.map { it.name }.filter { it in oldByName }
    val oldIndex = old.exercises.map { it.name }.withIndex().associate { (i, n) -> n to i }
    val kept = longestIncreasing(common.map { oldIndex.getValue(it) }).map { common[it] }.toSet()
    common.forEachIndexed { i, name ->
        if (name !in kept) out += "Spostato: $name, " + if (i == 0) "ora all'inizio" else "ora dopo ${common[i - 1]}"
    }

    for (ex in new.exercises) {
        val was = oldByName[ex.name] ?: continue
        // In a circuit the sets don't count, only the target of each set.
        val before = if (new.circuit) was.amountText() else was.summary()
        val after = if (new.circuit) ex.amountText() else ex.summary()
        if (before != after) out += "${ex.name}: $before → $after"
        if (was.rest != ex.rest) out += "${ex.name}: recupero ${formatDuration(was.rest)} → ${formatDuration(ex.rest)}"
        if (was.notes != ex.notes) out += "${ex.name}: note aggiornate"
        if (was.video != ex.video) out += "${ex.name}: " + if (ex.video.isBlank()) "video tolto" else "video aggiornato"
    }
    return out
}

/** Indices (into [values]) of one longest strictly increasing subsequence. */
private fun longestIncreasing(values: List<Int>): List<Int> {
    if (values.isEmpty()) return emptyList()
    val length = IntArray(values.size) { 1 }
    val prev = IntArray(values.size) { -1 }
    for (i in values.indices) for (j in 0 until i) {
        if (values[j] < values[i] && length[j] + 1 > length[i]) {
            length[i] = length[j] + 1
            prev[i] = j
        }
    }
    var i = length.indices.maxBy { length[it] }
    val out = ArrayList<Int>()
    while (i >= 0) { out += i; i = prev[i] }
    return out.reversed()
}
