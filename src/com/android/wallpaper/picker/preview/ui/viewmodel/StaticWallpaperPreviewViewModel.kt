/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.wallpaper.picker.preview.ui.viewmodel

import android.app.WallpaperColors
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Point
import android.graphics.Rect
import com.android.wallpaper.asset.Asset
import com.android.wallpaper.asset.StreamableAsset
import com.android.wallpaper.module.WallpaperPreferences
import com.android.wallpaper.picker.customization.shared.model.WallpaperColorsModel
import com.android.wallpaper.picker.data.WallpaperModel.StaticWallpaperModel
import com.android.wallpaper.picker.di.modules.BackgroundDispatcher
import com.android.wallpaper.picker.preview.domain.interactor.WallpaperPreviewInteractor
import com.android.wallpaper.picker.preview.shared.model.FullPreviewCropModel
import com.android.wallpaper.picker.preview.ui.WallpaperPreviewActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine

/** View model for static wallpaper preview used in [WallpaperPreviewActivity] and its fragments */
@ViewModelScoped
class StaticWallpaperPreviewViewModel
@Inject
constructor(
    private val interactor: WallpaperPreviewInteractor,
    @ApplicationContext private val context: Context,
    private val wallpaperPreferences: WallpaperPreferences,
    @BackgroundDispatcher private val bgDispatcher: CoroutineDispatcher,
) {
    /** The state of static wallpaper crop in full preview, before user confirmation. */
    var fullPreviewCropModel: FullPreviewCropModel? = null

    /** The info picker needs to post process crops for setting static wallpaper. */
    private val cropHintsInfo: MutableStateFlow<Map<Point, FullPreviewCropModel>?> =
        MutableStateFlow(null)

    private val cropHints: Flow<Map<Point, Rect>?> =
        cropHintsInfo.map { cropHintsInfoMap ->
            cropHintsInfoMap?.map { entry -> entry.key to entry.value.cropHint }?.toMap()
        }

    val staticWallpaperModel: Flow<StaticWallpaperModel> =
        interactor.wallpaperModel.map { it as? StaticWallpaperModel }.filterNotNull()
    val lowResBitmap: Flow<Bitmap> =
        staticWallpaperModel
            .map { it.staticWallpaperData.asset.getLowResBitmap(context) }
            .filterNotNull()
            .flowOn(bgDispatcher)
    // Asset detail includes the dimensions, bitmap and input stream decoded from the asset.
    private val assetDetail: Flow<Triple<Point, Bitmap?, InputStream?>?> =
        interactor.wallpaperModel
            .map { (it as? StaticWallpaperModel)?.staticWallpaperData?.asset }
            .map {
                if (it == null) {
                    null
                } else {
                    val dimensions = it.decodeRawDimensions()
                    val bitmap = it.decodeBitmap(dimensions)
                    val stream = it.getStream()
                    Triple(dimensions, bitmap, stream)
                }
            }
            .flowOn(bgDispatcher)
    val fullResWallpaperViewModel: Flow<FullResWallpaperViewModel?> =
        combine(assetDetail, cropHintsInfo) { assetDetail, cropHintsInfo ->
                if (assetDetail == null) {
                    null
                } else {
                    val (dimensions, bitmap, stream) = assetDetail
                    bitmap?.let {
                        FullResWallpaperViewModel(bitmap, dimensions, stream, cropHintsInfo)
                    }
                }
            }
            .flowOn(bgDispatcher)
    val subsamplingScaleImageViewModel: Flow<FullResWallpaperViewModel> =
        fullResWallpaperViewModel.filterNotNull()
    // TODO (b/315856338): cache wallpaper colors in preferences
    private val storedWallpaperColors: Flow<WallpaperColors?> =
        staticWallpaperModel
            .map { wallpaperPreferences.getWallpaperColors(it.commonWallpaperData.id.uniqueId) }
            .distinctUntilChanged()
    val wallpaperColors: Flow<WallpaperColorsModel> =
        combine(storedWallpaperColors, subsamplingScaleImageViewModel, cropHints) {
            storedColors,
            wallpaperViewModel,
            cropHints ->
            WallpaperColorsModel.Loaded(
                if (cropHints == null) {
                    storedColors
                        ?: interactor.getWallpaperColors(
                            wallpaperViewModel.rawWallpaperBitmap,
                            null
                        )
                } else {
                    interactor.getWallpaperColors(wallpaperViewModel.rawWallpaperBitmap, cropHints)
                }
            )
        }

    fun updateCropHintsInfo(cropHintsInfo: Map<Point, FullPreviewCropModel>) {
        this.cropHintsInfo.value = this.cropHintsInfo.value?.plus(cropHintsInfo) ?: cropHintsInfo
    }

    // TODO b/296288298 Create a util class for Bitmap and Asset
    private suspend fun Asset.decodeRawDimensions(): Point =
        suspendCancellableCoroutine { k: CancellableContinuation<Point> ->
            val callback =
                Asset.DimensionsReceiver { it?.let { k.resumeWith(Result.success(Point(it))) } }
            decodeRawDimensions(null, callback)
        }

    // TODO b/296288298 Create a util class functions for Bitmap and Asset
    private suspend fun Asset.decodeBitmap(dimensions: Point): Bitmap? =
        suspendCancellableCoroutine { k: CancellableContinuation<Bitmap?> ->
            val callback = Asset.BitmapReceiver { k.resumeWith(Result.success(it)) }
            decodeBitmap(dimensions.x, dimensions.y, /* hardwareBitmapAllowed= */ false, callback)
        }

    private suspend fun Asset.getStream(): InputStream? =
        suspendCancellableCoroutine { k: CancellableContinuation<InputStream?> ->
            if (this is StreamableAsset) {
                fetchInputStream { k.resumeWith(Result.success(it)) }
            } else {
                k.resumeWith(Result.success(null))
            }
        }

    // TODO b/296288298 Create a util class functions for Bitmap and Asset
    private fun Bitmap.extractColors(): WallpaperColors? {
        val tmpOut = ByteArrayOutputStream()
        var shouldRecycle = false
        var cropped = this
        if (cropped.compress(Bitmap.CompressFormat.PNG, 100, tmpOut)) {
            val outByteArray = tmpOut.toByteArray()
            val options = BitmapFactory.Options()
            options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            cropped = BitmapFactory.decodeByteArray(outByteArray, 0, outByteArray.size)
        }
        if (cropped.config == Bitmap.Config.HARDWARE) {
            cropped = cropped.copy(Bitmap.Config.ARGB_8888, false)
            shouldRecycle = true
        }
        val colors = WallpaperColors.fromBitmap(cropped)
        if (shouldRecycle) {
            cropped.recycle()
        }
        return colors
    }
}
