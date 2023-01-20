package com.nft.gallery.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nft.gallery.diskcache.MyMint
import com.nft.gallery.diskcache.MyMintsRepository
import com.nft.gallery.usecase.Connected
import com.nft.gallery.usecase.MyMintsUseCase
import com.nft.gallery.usecase.PersistenceUseCase
import com.nft.gallery.viewmodel.mapper.MyMintsMapper
import com.nft.gallery.viewmodel.viewstate.MyMintsViewState
import com.solana.core.PublicKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MyMintsViewModel @Inject constructor(
    application: Application,
    private val myMintsMapper: MyMintsMapper,
    private val persistenceUseCase: PersistenceUseCase,
    private val myMintsRepository: MyMintsRepository
) : AndroidViewModel(application) {

    private var _viewState: MutableStateFlow<MyMintsViewState> =
        MutableStateFlow(myMintsMapper.mapLoading())

    val viewState = _viewState

    private var wasLoaded = false

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun refresh() = viewModelScope.launch {
        _isRefreshing.update { true }
    }

    init {
        observeAllMints()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAllMints() {
        viewModelScope.launch {
            _isRefreshing.flatMapLatest { isRefreshing ->
                persistenceUseCase.walletDetails.map { isRefreshing to it }
            }.collect { (isRefreshing, userWalletDetails) ->
                if (userWalletDetails is Connected && isRefreshing) {
                    loadMyMints(userWalletDetails.publicKey, true)
                }
            }
        }

        viewModelScope.launch {
            persistenceUseCase.walletDetails.flatMapLatest { walletDetails ->
                val myMintsFlow = if (walletDetails is Connected) {
                    myMintsRepository.get(walletDetails.publicKey.toString())
                } else {
                    flow { emit(listOf()) }
                }

                myMintsFlow.map { walletDetails to it }
            }.collect { (userWalletDetail, myMints) ->
                if (userWalletDetail is Connected) {
                    if (myMints.isEmpty()) {
                        _viewState.value =
                            MyMintsViewState.Empty("No mints yet. Start minting pictures with Minty Fresh!")
                    } else {
                        _viewState.value = MyMintsViewState.Loaded(myMints)
                    }
                } else {
                    _viewState.value =
                        MyMintsViewState.Empty("Connect your wallet to see your mints")
                }
            }
        }
    }

    private suspend fun loadMyMints(publicKey: PublicKey, forceRefresh: Boolean) {
        if (publicKey.toString().isEmpty() || (!forceRefresh && wasLoaded)) {
            return
        }

        if (forceRefresh) {
            val loadingMints =
                _viewState.value.myMints.filter { it.id.isNotEmpty() }.toMutableList()
                    .apply {
                        val loadingSize = if (this.isEmpty()) 10 else 1
                        // Add a loading placeholder to existing data.
                        for (i in 0 until loadingSize) {
                            add(MyMint("", "", "", "", "", ""))
                        }
                    }
            _viewState.update {
                MyMintsViewState.Loaded(loadingMints)
            }
        } else {
            if (_viewState.value is MyMintsViewState.Loaded) {
                return
            }
        }

        wasLoaded = true
        val mintsUseCase = MyMintsUseCase(publicKey)

        try {
            val nfts = mintsUseCase.getAllUserMintyFreshNfts()
            Log.d(TAG, "Found ${nfts.size} NFTs")

            if (nfts.isNotEmpty()) {
                val currentMintList = myMintsMapper.map(nfts)
                myMintsRepository.deleteStaleData(
                    currentMintList = currentMintList,
                    publicKey.toString()
                )
                // Fetch and update each NFT data.
                nfts.forEach { nft ->
                    val metadata = mintsUseCase.getNftsMetadata(nft)
                    val mint = myMintsMapper.map(nft, metadata)
                    if (mint != null) {
                        myMintsRepository.insertAll(listOf(mint))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, e.toString())
            _viewState.value = MyMintsViewState.Error(e)
        }
        _isRefreshing.value = false
    }

    companion object {
        private const val TAG = "MyMintsViewModel"
    }
}