package com.nguyen.codelab_advancedcoroutines

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * The [ViewModel] for fetching a list of [Plant]s.
 */
class PlantListViewModel internal constructor(
    private val plantRepository: PlantRepository
) : ViewModel() {

    /**
     * Request a snackbar to display a string.
     *
     * This variable is private because we don't want to expose [MutableLiveData].
     *
     * MutableLiveData allows anyone to set a value, and [PlantListViewModel] is the only
     * class that should be setting values.
     */
    private val _snackbar = MutableLiveData<String?>()

    /**
     * Request a snackbar to display a string.
     */
    val snackbar: LiveData<String?>
        get() = _snackbar

    private val _spinner = MutableLiveData<Boolean>(false)

    /**
     * Show a loading spinner if true
     */
    val spinner: LiveData<Boolean>
        get() = _spinner

    /**
     * The current growZone selection.
     */
    private val growZone = MutableLiveData<GrowZone>(NoGrowZone)

    /**
     * A list of plants that updates based on the current filter.
     */
    val plants: LiveData<List<Plant>> = growZone.switchMap { growZone ->
        if (growZone == NoGrowZone) {
            plantRepository.plants
        } else {
            plantRepository.getPlantsWithGrowZone(growZone)
        }
    }

    // This defines a new MutableStateFlow with an initial value of NoGrowZone. This is a special
    // kind of Flow value holder that holds only the last value it was given. It's a thread-safe
    // concurrency primitive, so you can write to it from multiple threads at the same time (and
    // whichever is considered "last" will win).
    private val growZoneFlow = MutableStateFlow<GrowZone>(NoGrowZone)

    // StateFlow is also a regular Flow, so you can use all the operators as you normally would.
    // Here we use the flatMapLatest operator which is exactly the same as switchMap from LiveData.
    // Whenever the growZone changes its value, this lambda will be applied and it must return a Flow.
    // Then, the returned Flow will be used as the Flow for all downstream operators.
    // Basically, this lets us switch between different flows based on the value of growZone.
    val plantsUsingFlow: LiveData<List<Plant>> = growZoneFlow.flatMapLatest { growZone ->
        if (growZone == NoGrowZone) {
            plantRepository.plantsFlow
        } else {
            plantRepository.getPlantsWithGrowZoneFlow(growZone)
        }
    }.asLiveData()

    init {
        // When creating a new ViewModel, clear the grow zone and perform any related udpates
        clearGrowZoneNumber()

        // fetch the full plant list
        launchDataLoad { plantRepository.tryUpdateRecentPlantsCache() }
    }

    /**
     * Filter the list to this grow zone.
     *
     * In the starter code version, this will also start a network request. After refactoring,
     * updating the grow zone will automatically kickoff a network request.
     */
    fun setGrowZoneNumber(num: Int) {
        growZone.value = GrowZone(num)
        growZoneFlow.value = GrowZone(num)

        launchDataLoad {
            plantRepository.tryUpdateRecentPlantsForGrowZoneCache(GrowZone(num)) }
    }

    /**
     * Clear the current filter of this plants list.
     *
     * In the starter code version, this will also start a network request. After refactoring,
     * updating the grow zone will automatically kickoff a network request.
     */
    fun clearGrowZoneNumber() {
        growZone.value = NoGrowZone
        growZoneFlow.value = NoGrowZone

        launchDataLoad {
            plantRepository.tryUpdateRecentPlantsCache()
        }
    }

    /**
     * Return true iff the current list is filtered.
     */
    fun isFiltered() = growZone.value != NoGrowZone

    /**
     * Called immediately after the UI shows the snackbar.
     */
    fun onSnackbarShown() {
        _snackbar.value = null
    }

    /**
     * Helper function to call a data load function with a loading spinner; errors will trigger a
     * snackbar.
     *
     * By marking [block] as [suspend] this creates a suspend lambda which can call suspend
     * functions.
     *
     * @param block lambda to actually load data. It is called in the viewModelScope. Before calling
     *              the lambda, the loading spinner will display. After completion or error, the
     *              loading spinner will stop.
     */
    private fun launchDataLoad(block: suspend () -> Unit): Job {
        return viewModelScope.launch {
            try {
                _spinner.value = true
                block()
            } catch (error: Throwable) {
                _snackbar.value = error.message
            } finally {
                _spinner.value = false
            }
        }
    }
}
