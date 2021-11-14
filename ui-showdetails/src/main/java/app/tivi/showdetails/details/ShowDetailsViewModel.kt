/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.tivi.showdetails.details

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.molecule.AndroidUiDispatcher.Companion.Main
import app.cash.molecule.launchMolecule
import app.tivi.data.entities.TiviShow
import app.tivi.domain.interactors.ChangeSeasonFollowStatus
import app.tivi.domain.interactors.ChangeSeasonWatchedStatus
import app.tivi.domain.interactors.ChangeSeasonWatchedStatus.Action
import app.tivi.domain.interactors.ChangeSeasonWatchedStatus.Params
import app.tivi.domain.interactors.ChangeShowFollowStatus
import app.tivi.domain.interactors.ChangeShowFollowStatus.Action.TOGGLE
import app.tivi.domain.interactors.UpdateRelatedShows
import app.tivi.domain.interactors.UpdateShowDetails
import app.tivi.domain.interactors.UpdateShowImages
import app.tivi.domain.interactors.UpdateShowSeasonData
import app.tivi.domain.observers.ObserveRelatedShows
import app.tivi.domain.observers.ObserveShowDetails
import app.tivi.domain.observers.ObserveShowFollowStatus
import app.tivi.domain.observers.ObserveShowImages
import app.tivi.domain.observers.ObserveShowNextEpisodeToWatch
import app.tivi.domain.observers.ObserveShowSeasonsEpisodesWatches
import app.tivi.domain.observers.ObserveShowViewStats
import app.tivi.util.ObservableLoadingCounter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class ShowDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val updateShowDetails: UpdateShowDetails,
    observeShowDetails: ObserveShowDetails,
    observeShowImages: ObserveShowImages,
    private val updateShowImages: UpdateShowImages,
    private val updateRelatedShows: UpdateRelatedShows,
    observeRelatedShows: ObserveRelatedShows,
    private val updateShowSeasons: UpdateShowSeasonData,
    observeShowSeasons: ObserveShowSeasonsEpisodesWatches,
    private val changeSeasonWatchedStatus: ChangeSeasonWatchedStatus,
    observeShowFollowStatus: ObserveShowFollowStatus,
    observeNextEpisodeToWatch: ObserveShowNextEpisodeToWatch,
    observeShowViewStats: ObserveShowViewStats,
    private val changeShowFollowStatus: ChangeShowFollowStatus,
    private val changeSeasonFollowStatus: ChangeSeasonFollowStatus,
) : ViewModel() {
    private val showId: Long = savedStateHandle.get("showId")!!

    private val pendingActions = MutableSharedFlow<ShowDetailsAction>()

    private val _effects = MutableSharedFlow<ShowDetailsUiEffect>()
    val effects: Flow<ShowDetailsUiEffect>
        get() = _effects

    // Need to add a MonotonicFrameClock
    // See: https://github.com/cashapp/molecule/#frame-clock
    private val moleculeScope = CoroutineScope(viewModelScope.coroutineContext + Main)

    val state: StateFlow<ShowDetailsViewState> = moleculeScope.launchMolecule {
        val loadingCounter = remember { ObservableLoadingCounter() }

        val isFollowed by observeShowFollowStatus.flow.collectAsState(false)
        val show by observeShowDetails.flow.collectAsState(TiviShow.EMPTY_SHOW)
        val showImages by observeShowImages.flow.collectAsState(null)
        val refreshing by loadingCounter.observable.collectAsState(false)
        val relatedShows by observeRelatedShows.flow.collectAsState(emptyList())
        val nextEpisode by observeNextEpisodeToWatch.flow.collectAsState(null)
        val seasons by observeShowSeasons.flow.collectAsState(emptyList())
        val stats by observeShowViewStats.flow.collectAsState(null)

        LaunchedEffect(Unit) {
            pendingActions.collect { action ->
                when (action) {
                    is ShowDetailsAction.RefreshAction -> {
                        loadingCounter.addLoader()
                        launch {
                            val result = runCatching {
                                updateShowDetails.executeSync(
                                    UpdateShowDetails.Params(showId, action.fromUser)
                                )
                            }
                            if (result.isFailure) showError(result)
                            loadingCounter.removeLoader()
                        }

                        loadingCounter.addLoader()
                        launch {
                            val result = runCatching {
                                updateShowImages.executeSync(
                                    UpdateShowImages.Params(showId, action.fromUser)
                                )
                            }
                            if (result.isFailure) showError(result)
                            loadingCounter.removeLoader()
                        }

                        loadingCounter.addLoader()
                        launch {
                            val result = runCatching {
                                updateRelatedShows.executeSync(
                                    UpdateRelatedShows.Params(showId, action.fromUser)
                                )
                            }
                            if (result.isFailure) showError(result)
                            loadingCounter.removeLoader()
                        }

                        loadingCounter.addLoader()
                        launch {
                            val result = runCatching {
                                updateShowSeasons.executeSync(
                                    UpdateShowSeasonData.Params(showId, action.fromUser)
                                )
                            }
                            if (result.isFailure) showError(result)
                            loadingCounter.removeLoader()
                        }
                    }
                    ShowDetailsAction.FollowShowToggleAction -> {
                        launch {
                            val result = runCatching {
                                changeShowFollowStatus.executeSync(
                                    ChangeShowFollowStatus.Params(showId, TOGGLE)
                                )
                            }
                            if (result.isFailure) showError(result)
                        }
                    }
                    is ShowDetailsAction.MarkSeasonWatchedAction -> {
                        launch {
                            val result = runCatching {
                                changeSeasonWatchedStatus.executeSync(
                                    Params(
                                        seasonId = action.seasonId,
                                        action = Action.WATCHED,
                                        onlyAired = action.onlyAired,
                                        actionDate = action.date
                                    )
                                )
                            }
                            if (result.isFailure) showError(result)
                        }
                    }
                    is ShowDetailsAction.MarkSeasonUnwatchedAction -> {
                        launch {
                            val result = runCatching {
                                changeSeasonWatchedStatus.executeSync(
                                    Params(seasonId = action.seasonId, action = Action.UNWATCH)
                                )
                            }
                            if (result.isFailure) showError(result)
                        }
                    }
                    is ShowDetailsAction.ChangeSeasonFollowedAction -> {
                        launch {
                            val result = runCatching {
                                changeSeasonFollowStatus.executeSync(
                                    ChangeSeasonFollowStatus.Params(
                                        seasonId = action.seasonId,
                                        action = when {
                                            action.followed -> ChangeSeasonFollowStatus.Action.FOLLOW
                                            else -> ChangeSeasonFollowStatus.Action.IGNORE
                                        }
                                    )
                                )
                            }
                            if (result.isFailure) showError(result)
                        }
                    }
                    is ShowDetailsAction.UnfollowPreviousSeasonsFollowedAction -> {
                        launch {
                            val result = runCatching {
                                changeSeasonFollowStatus.executeSync(
                                    ChangeSeasonFollowStatus.Params(
                                        seasonId = action.seasonId,
                                        action = ChangeSeasonFollowStatus.Action.IGNORE_PREVIOUS
                                    )
                                )
                            }
                            if (result.isFailure) showError(result)
                        }
                    }
                    is ShowDetailsAction.ClearError -> {
                        launch {
                            _effects.emit(ShowDetailsUiEffect.ClearError)
                        }
                    }
                }
            }
        }

        ShowDetailsViewState(
            isFollowed = isFollowed,
            show = show,
            posterImage = showImages?.poster,
            backdropImage = showImages?.backdrop,
            relatedShows = relatedShows,
            nextEpisodeToWatch = nextEpisode,
            seasons = seasons,
            watchStats = stats,
            refreshing = refreshing,
        )
    }

    init {
        observeShowFollowStatus(ObserveShowFollowStatus.Params(showId))
        observeShowDetails(ObserveShowDetails.Params(showId))
        observeShowImages(ObserveShowImages.Params(showId))
        observeRelatedShows(ObserveRelatedShows.Params(showId))
        observeShowSeasons(ObserveShowSeasonsEpisodesWatches.Params(showId))
        observeNextEpisodeToWatch(ObserveShowNextEpisodeToWatch.Params(showId))
        observeShowViewStats(ObserveShowViewStats.Params(showId))

        // Refresh on start
        submitAction(ShowDetailsAction.RefreshAction(fromUser = false))
    }

    fun submitAction(action: ShowDetailsAction) {
        viewModelScope.launch { pendingActions.emit(action) }
    }

    private suspend inline fun <T> showError(result: Result<T>) {
        _effects.emit(ShowDetailsUiEffect.ShowError(result.exceptionOrNull()))
    }
}
