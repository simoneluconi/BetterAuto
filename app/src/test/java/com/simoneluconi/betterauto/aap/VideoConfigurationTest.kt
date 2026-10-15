package com.simoneluconi.betterauto.aap

import aap_protobuf.service.media.shared.message.InsetsOuterClass.Insets
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import com.simoneluconi.betterauto.car.Edges
import com.simoneluconi.betterauto.car.Viewport
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoConfigurationTest {

    @Test
    fun widthAndHeightMarginsAreSplitAroundTheCenter() {
        val cfg = VideoConfiguration.newBuilder()
            .setCodecResolution(VideoCodecResolutionType.VIDEO_1920x1080)
            .setWidthMargin(0)
            .setHeightMargin(360)
            .build()
        assertEquals(Viewport(1920, 1080, 0, 180, 1920, 720), cfg.viewport())
    }

    @Test
    fun uiConfigMarginsAndInsetsAreTakenPerSide() {
        val ui = UiConfig.newBuilder()
            .setMargins(Insets.newBuilder().setLeft(100).setTop(0).setRight(300).setBottom(80))
            .setContentInsets(Insets.newBuilder().setLeft(10).setTop(20).setRight(30).setBottom(40))
        val cfg = VideoConfiguration.newBuilder()
            .setCodecResolution(VideoCodecResolutionType.VIDEO_1280x720)
            .setUiConfig(ui)
            .build()
        assertEquals(Viewport(1280, 720, 100, 0, 880, 640, Edges(10, 20, 30, 40)), cfg.viewport())
    }

    @Test
    fun marginsTheFrameCannotHoldFallBackToTheFullFrame() {
        val cfg = VideoConfiguration.newBuilder()
            .setCodecResolution(VideoCodecResolutionType.VIDEO_1280x720)
            .setWidthMargin(2000)
            .build()
        assertEquals(Viewport.full(1280, 720), cfg.viewport())
    }

    @Test
    fun unknownResolutionIsTheSmallestStandardScreen() {
        assertEquals(800 to 480, VideoConfiguration.getDefaultInstance().size())
    }
}
