package com.ultratv.tv.nativeapp.data.repo

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowThrottleTest {
    @Test
    fun atMostEvery_premiereToutDeSuite_puisLaPlusRecente() = runTest {
        // 0..9 toutes les 100 ms (comme Room pendant une synchro), limité à une valeur par seconde.
        val got = flow { repeat(10) { emit(it); delay(100) } }.atMostEvery(1_000).toList()
        assertEquals(0, got.first())          // la première, sans attendre
        assertEquals(9, got.last())           // la dernière n'est jamais perdue
        assertEquals(true, got.size <= 3)     // au lieu de 10
    }

    @Test
    fun snapshotWhile_synchroEnCours_neLitQuUneFoisPuisReprendLeSuiviALaFin() = runTest {
        val source = MutableSharedFlow<Int>(replay = 1)
        var subscriptions = 0
        val syncing = MutableStateFlow(false)
        val got = ArrayList<Int>()
        val job = source.onStart { subscriptions++ }.snapshotWhile(syncing).onEach { got += it }.launchIn(backgroundScope)

        source.emit(1); runCurrent()
        syncing.value = true; runCurrent()               // début de synchro : une relecture instantanée (1 abonnement de plus)
        val subsAtSyncStart = subscriptions
        repeat(50) { source.emit(100 + it); runCurrent() } // 50 lots insérés : AUCUNE requête supplémentaire ne doit être suivie
        assertEquals(subsAtSyncStart, subscriptions)
        syncing.value = false; runCurrent()              // fin : suivi complet, valeur finale
        source.emit(999); runCurrent()
        assertEquals(999, got.last())
        assertEquals(true, got.none { it in 101..148 })  // aucune valeur intermédiaire de la synchro
        job.cancel()
    }

    @Test
    fun snapshotWhile_horsSynchro_transmetToutesLesValeurs() = runTest {
        val got = flow { emit(1); emit(2); emit(3) }.snapshotWhile(MutableStateFlow(false)).take(3).toList()
        assertEquals(listOf(1, 2, 3), got)
    }
}
