package com.clawsses.phone.openclaw

import com.clawsses.phone.glasses.CxrOutboundTransport
import com.clawsses.shared.CxrPayloadLimits
import com.clawsses.shared.ModelInfo
import com.clawsses.shared.ModelPaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesModelPagingTest {
    private val models = (0 until 8).map { index ->
        ModelInfo(
            ref = "provider-$index/model-with-a-deliberately-long-id-$index",
            provider = "provider-with-a-long-name-$index",
            id = "model-$index",
            name = "A deliberately long model display name number $index",
            available = index != 6,
        )
    }

    @Test
    fun `initial page contains current model and fits reliable CXR payload`() {
        val page = buildGlassesModelPage(
            models = models,
            currentModelRef = models[5].ref,
            requestedOffset = -1,
        )

        assertEquals(3, page.offset)
        assertEquals(1, page.pageIndex)
        assertEquals(3, page.pageCount)
        assertEquals(5, page.currentIndex)
        assertEquals(listOf(3, 4, 5), page.models.map { it.index })
        assertTrue(
            "payload plus ACK reserve exceeded CXR limit",
            CxrPayloadLimits.byteSize(page.toJson()) +
                CxrOutboundTransport.ACK_METADATA_RESERVE_BYTES <= CxrPayloadLimits.MAX_BYTES,
        )
    }

    @Test
    fun `requested offsets are normalized to fixed pages`() {
        val page = buildGlassesModelPage(models, models.first().ref, requestedOffset = 7)

        assertEquals(6, page.offset)
        assertEquals(listOf(6, 7), page.models.map { it.index })
        assertNull(page.nextOffset)
        assertEquals(ModelPaging.MAX_DISPLAY_NAME_CHARS, page.models.first().name.length)
        assertEquals(ModelPaging.MAX_PROVIDER_CHARS, page.models.first().provider.length)
    }

    @Test
    fun `selection requires current catalog and available model`() {
        val catalogId = modelCatalogId(models)

        assertEquals(models[2], resolveGlassesModelSelection(models, catalogId, 2))
        assertNull(resolveGlassesModelSelection(models, "stale", 2))
        assertNull(resolveGlassesModelSelection(models, catalogId, 6))
        assertNull(resolveGlassesModelSelection(models, catalogId, 99))
    }

    @Test
    fun `catalog token changes when availability or order changes`() {
        assertNotEquals(modelCatalogId(models), modelCatalogId(models.reversed()))
        assertNotEquals(
            modelCatalogId(models),
            modelCatalogId(models.mapIndexed { index, model ->
                if (index == 0) model.copy(available = !model.available) else model
            }),
        )
    }
}
