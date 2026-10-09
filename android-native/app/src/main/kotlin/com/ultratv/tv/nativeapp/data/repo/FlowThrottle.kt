package com.ultratv.tv.nativeapp.data.repo

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.transform

/**
 * Au plus une valeur toutes les [ms] millisecondes, la PREMIÈRE tout de suite, puis toujours la plus récente.
 * Room ré-émet ses requêtes (COUNT, GROUP BY…) à CHAQUE écriture de la table : pendant une synchro, des centaines
 * de fois par seconde. Une seule souscription en amont (merge(take(1), drop(1).sample()) en lançait deux).
 */
fun <T> Flow<T>.atMostEvery(ms: Long): Flow<T> = conflate().transform { emit(it); delay(ms) }

/**
 * Pendant une synchro ([syncing] = true), ne lit la source qu'UNE fois (instantané) au lieu de l'observer : une requête Room
 * observée est relancée à CHAQUE lot inséré (des centaines de fois pour 55 000 chaînes) — [atMostEvery] ne limite que ce qui
 * est ÉMIS, pas les requêtes. Le suivi complet reprend à la fin de la synchro (une relecture), l'écran affiche alors le
 * résultat final au lieu de clignoter pendant l'écriture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.snapshotWhile(syncing: Flow<Boolean>): Flow<T> =
    syncing.distinctUntilChanged().flatMapLatest { busy -> if (busy) this.take(1) else this }
