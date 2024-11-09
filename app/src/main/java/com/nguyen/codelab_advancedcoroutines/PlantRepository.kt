package com.nguyen.codelab_advancedcoroutines

import androidx.annotation.AnyThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import com.nguyen.codelab_advancedcoroutines.utils.CacheOnSuccess
import com.nguyen.codelab_advancedcoroutines.utils.ComparablePair
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * Repository module for handling data operations.
 *
 * This PlantRepository exposes two UI-observable database queries [plants] and
 * [getPlantsWithGrowZone].
 *
 * To update the plants cache, call [tryUpdateRecentPlantsForGrowZoneCache] or
 * [tryUpdateRecentPlantsCache].
 */
class PlantRepository private constructor(
    private val plantDao: PlantDao,
    private val plantService: NetworkService,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    /**
     * Fetch a list of [Plant]s from the database.
     * Returns a LiveData-wrapped List of Plants.
     */
    val plants: LiveData<List<Plant>> = liveData {
        val plantsLiveData = plantDao.getPlants()
        val customSortOrder = plantsListSortOrderCache.getOrAwait()
        emitSource(plantsLiveData.map { plantList ->
            plantList.applySort(customSortOrder)
        })
    }

    /**
     * Fetch a list of [Plant]s from the database that matches a given [GrowZone].
     * Returns a LiveData-wrapped List of Plants.
     */
    fun getPlantsWithGrowZone(growZone: GrowZone) =
        // once the custom sort order is received from the network, it can then be used with the new
        // main-safe applyMainSafeSort. This result is then emitted to the switchMap as the new value
        // returned by getPlantsWithGrowZone
        plantDao.getPlantsWithGrowZoneNumber(growZone.number)
            .switchMap { plantList ->
                liveData {
                    val customSortOrder = plantsListSortOrderCache.getOrAwait()
                    emit(plantList.applyMainSafeSort(customSortOrder))
                }
            }

    fun getPlantsWithGrowZoneFlow(growZone: GrowZone): Flow<List<Plant>> {
        return plantDao.getPlantsWithGrowZoneNumberFlow(growZone.number)
            .map { plantList ->
                // By relying on regular suspend functions to handle the async work, this map
                // operation is main-safe even though it combines two async operations
                // As each result from the database is returned, we'll get the cached sort order–and
                // if it's not ready yet, it will wait on the async network request. Then once we
                // have the sort order, it's safe to call applyMainSafeSort, which will run the sort
                // on the default dispatcher
                val sortOrderFromNetwork = plantsListSortOrderCache.getOrAwait()
                val nextValue = plantList.applyMainSafeSort(sortOrderFromNetwork)
                nextValue
            }
    }

    /**
     * Returns true if we should make a network request.
     */
    private fun shouldUpdatePlantsCache(): Boolean {
        // suspending function, so you can e.g. check the status of the database here
        return true
    }

    /**
     * Update the plants cache.
     *
     * This function may decide to avoid making a network requests on every call based on a
     * cache-invalidation policy.
     */
    suspend fun tryUpdateRecentPlantsCache() {
        if (shouldUpdatePlantsCache())
            fetchRecentPlants()
    }

    /**
     * Update the plants cache for a specific grow zone.
     *
     * This function may decide to avoid making a network requests on every call based on a
     * cache-invalidation policy.
     */
    suspend fun tryUpdateRecentPlantsForGrowZoneCache(growZoneNumber: GrowZone) {
        if (shouldUpdatePlantsCache())
            fetchPlantsForGrowZone(growZoneNumber)
    }

    /**
     * Fetch a new list of plants from the network, and append them to [plantDao]
     */
    private suspend fun fetchRecentPlants() {
        plantDao.insertAll(plantService.allPlants())
    }

    /**
     * Fetch a list of plants for a grow zone from the network, and append them to [plantDao]
     */
    private suspend fun fetchPlantsForGrowZone(growZone: GrowZone) {
        plantDao.insertAll(plantService.plantsByGrowZone(growZone))
    }

    private var plantsListSortOrderCache =
        CacheOnSuccess(onErrorFallback = { listOf<String>() }) {
            plantService.customPlantSortOrder()
        }

    private fun List<Plant>.applySort(customSortOrder: List<String>): List<Plant> {
        return sortedBy { plant ->
            val positionForItem = customSortOrder.indexOf(plant.plantId).let { order ->
                if (order > -1) order else Int.MAX_VALUE
            }
            ComparablePair(positionForItem, plant.name)
        }
    }

    @AnyThread
    suspend fun List<Plant>.applyMainSafeSort(customSortOrder: List<String>) =
        withContext(defaultDispatcher) {
            this@applyMainSafeSort.applySort(customSortOrder)
        }

    // a Flow that, when collected, will call getOrAwait and emit the sort order
    // private val customSortFlow = flow { emit(plantsListSortOrderCache.getOrAwait()) }
    private val customSortFlow = plantsListSortOrderCache::getOrAwait.asFlow()

    /*// This flow uses the following threads already:
    // * plantService.customPlantSortOrder runs on a Retrofit thread (it calls Call.enqueue)
    // * getPlantsFlow will run queries on a Room Executor
    // * applySort will run on the collecting dispatcher (in this case Dispatchers.Main)
    // So if all we were doing was calling suspend functions in Retrofit and using Room flows, we
    // wouldn't need to complicate this code with main-safety concerns.
    val plantsFlow: Flow<List<Plant>>
        get() = plantDao.getPlantsFlow()
            // When the result of customSortFlow is available, this will combine it with the latest
            // value from getPlantsFlow() above.  Thus, as long as both `plants` and `sortOrder`
            // have an initial value (their flow has emitted at least one value), any change to
            // either `plants` or `sortOrder`  will call `plants.applySort(sortOrder)`.
            .combine(customSortFlow) { plants, sortOrder ->
                plants.applySort(sortOrder)
            }*/

    // However, as our data set grows in size, the call to applySort may become slow enough to block
    // the main thread. Flow offers a declarative API called flowOn to control which thread the flow
    // runs on.
    // Calling flowOn has two important effects on how the code executes:
    // * Launch a new coroutine on the defaultDispatcher (in this case, Dispatchers.Default) to run
    //   and collect the flow before the call to flowOn.
    // * Introduces a buffer to send results from the new coroutine to later calls.
    // * Emit the values from that buffer into the Flow after flowOn. In this case, that's asLiveData
    //   in the ViewModel
    val plantsFlow: Flow<List<Plant>>
        get() = plantDao.getPlantsFlow()
            .combine(customSortFlow) { plants, sortOrder ->
                plants.applySort(sortOrder)
            }
            .flowOn(defaultDispatcher)
            .conflate()

    companion object {

        // For Singleton instantiation
        @Volatile
        private var instance: PlantRepository? = null

        fun getInstance(plantDao: PlantDao, plantService: NetworkService) =
            instance ?: synchronized(this) {
                instance ?: PlantRepository(plantDao, plantService).also { instance = it }
            }
    }
}
