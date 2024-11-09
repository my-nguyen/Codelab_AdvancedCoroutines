package com.nguyen.codelab_advancedcoroutines.utils

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.nguyen.codelab_advancedcoroutines.NetworkService
import com.nguyen.codelab_advancedcoroutines.PlantRepository
import com.nguyen.codelab_advancedcoroutines.ui.PlantListViewModelFactory

interface ViewModelFactoryProvider {
    fun providePlantListViewModelFactory(context: Context): PlantListViewModelFactory
}

val Injector: ViewModelFactoryProvider
    get() = currentInjector

private object DefaultViewModelProvider : ViewModelFactoryProvider {
    private fun getPlantRepository(context: Context): PlantRepository {
        return PlantRepository.getInstance(plantDao(context), plantService())
    }

    private fun plantService() = NetworkService()

    private fun plantDao(context: Context) = AppDatabase.getInstance(context.applicationContext).plantDao()

    override fun providePlantListViewModelFactory(context: Context): PlantListViewModelFactory {
        val repository = getPlantRepository(context)
        return PlantListViewModelFactory(repository)
    }
}

private object Lock

@Volatile
private var currentInjector: ViewModelFactoryProvider = DefaultViewModelProvider

@VisibleForTesting
private fun setInjectorForTesting(injector: ViewModelFactoryProvider?) {
    synchronized(Lock) {
        currentInjector = injector ?: DefaultViewModelProvider
    }
}

@VisibleForTesting
private fun resetInjector() = setInjectorForTesting(null)