package com.ultratv.tv.nativeapp.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfAggregatorTest {
    private fun frames(a: PerfAggregator, route: String, n: Int, ms: Long = 10, jankyEvery: Int = 0) {
        for (i in 1..n) a.record(route, ms, jankyEvery > 0 && i % jankyEvery == 0)
    }

    @Test
    fun drain_sansDonnee_neRendRien() {
        assertTrue(PerfAggregator().drain(0).isEmpty())
    }

    @Test
    fun drain_echantillonTropMince_neRapportePasEtContinueDeS_accumuler() {
        val a = PerfAggregator(minFrames = 30)
        frames(a, "home", 10)
        assertTrue(a.drain(1_000).isEmpty())
        frames(a, "home", 25)
        assertEquals(1, a.drain(2_000).size)
    }

    @Test
    fun drain_formatDuMessage_compteursPourcentageEtPire() {
        val a = PerfAggregator()
        frames(a, "home", 100, ms = 10, jankyEvery = 5)   // 20 saccadées sur 100
        a.record("home", 480, true)                        // frame 101, la pire
        val line = a.drain(0).single()
        assertTrue(line, line.startsWith("perf home frames=101 janky=21 (21%) p95≈"))
        assertTrue(line, line.endsWith("worst=480ms"))
    }

    @Test
    fun drain_p95_estUneBorneHauteDuSeauDuQuantile() {
        val a = PerfAggregator()
        frames(a, "live", 95, ms = 8)     // seau 8..11 → borne 12
        frames(a, "live", 5, ms = 200)
        val line = a.drain(0).single()
        assertTrue(line, line.contains("p95≈12ms"))
        assertTrue(line, line.endsWith("worst=200ms"))
    }

    @Test
    fun drain_memeRoute_auPlusUneFoisParMinute() {
        val a = PerfAggregator()
        frames(a, "home", 40)
        assertEquals(1, a.drain(0).size)
        frames(a, "home", 40)
        assertTrue(a.drain(59_000).isEmpty())          // trop tôt : on garde les compteurs
        assertEquals(1, a.drain(60_000).size)           // dû : les 40 images accumulées partent
    }

    @Test
    fun drain_apresRapport_lesCompteursRepartentDeZero() {
        val a = PerfAggregator()
        frames(a, "home", 40)
        a.drain(0)
        frames(a, "home", 31)
        assertTrue(a.drain(60_000).single().contains("frames=31 "))
    }

    @Test
    fun drain_routeQuittee_neVideQueCetteRoute() {
        val a = PerfAggregator()
        frames(a, "home", 40)
        frames(a, "live", 40)
        val out = a.drain(0, only = "home")
        assertEquals(1, out.size)
        assertTrue(out.single().startsWith("perf home "))
        assertTrue(a.drain(0).single().startsWith("perf live "))
    }

    @Test
    fun normalize_retireLesArgumentsDeRoute() {
        assertEquals("player", PerfAggregator.normalize("player?url={url}&title={title}"))
        assertEquals("movies/{id}", PerfAggregator.normalize("movies/{id}"))
        assertEquals("(none)", PerfAggregator.normalize(null))
        assertEquals("(none)", PerfAggregator.normalize(""))
    }

    @Test
    fun stallGate_sousLeSeuil_neRapportePas() {
        assertFalse(StallGate().shouldReport(700, 0))
        assertTrue(StallGate().shouldReport(701, 0))
    }

    @Test
    fun stallGate_rafale_espaceeParLeDelaiMinimal() {
        val g = StallGate(minGapMs = 15_000)
        assertTrue(g.shouldReport(900, 0))
        assertFalse(g.shouldReport(900, 14_999))
        assertTrue(g.shouldReport(900, 15_000))
    }

    @Test
    fun stallGate_plafondParLancement_estRespecte() {
        val g = StallGate(minGapMs = 0, maxPerLaunch = 3)
        repeat(3) { assertTrue(g.shouldReport(900, it.toLong())) }
        assertFalse(g.shouldReport(900, 10))
    }

    @Test
    fun coldStart_attendUneRoute_puisUneSeuleFois() {
        val g = ColdStartGate()
        assertNull(g.onFrame(null, 900, 700))
        val msg = g.onFrame("home", 1_840, 1_520)
        assertNotNull(msg)
        assertEquals("perf coldstart 1840ms (since app onCreate 1520ms) route=home", msg)
        assertNull(g.onFrame("home", 2_000, 1_700))
    }
}
