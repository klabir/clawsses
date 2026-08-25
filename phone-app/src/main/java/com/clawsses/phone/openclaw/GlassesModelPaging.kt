package com.clawsses.phone.openclaw

import com.clawsses.shared.ModelInfo
import com.clawsses.shared.ModelPageItem
import com.clawsses.shared.ModelPageUpdate
import com.clawsses.shared.ModelPaging
import java.security.MessageDigest

internal fun buildGlassesModelPage(
    models: List<ModelInfo>,
    currentModelRef: String?,
    requestedOffset: Int,
    error: String? = null,
): ModelPageUpdate {
    val currentIndex = models.indexOfFirst { it.ref == currentModelRef }.takeIf { it >= 0 }
    val lastPageOffset = ((models.lastIndex.coerceAtLeast(0)) / ModelPaging.PAGE_SIZE) *
        ModelPaging.PAGE_SIZE
    val desiredOffset = if (requestedOffset < 0) {
        currentIndex?.let { (it / ModelPaging.PAGE_SIZE) * ModelPaging.PAGE_SIZE } ?: 0
    } else {
        (requestedOffset / ModelPaging.PAGE_SIZE) * ModelPaging.PAGE_SIZE
    }
    val offset = desiredOffset.coerceIn(0, lastPageOffset)
    val end = minOf(models.size, offset + ModelPaging.PAGE_SIZE)
    val pageCount = maxOf(1, (models.size + ModelPaging.PAGE_SIZE - 1) / ModelPaging.PAGE_SIZE)

    return ModelPageUpdate(
        catalogId = modelCatalogId(models),
        models = models.subList(offset, end).mapIndexed { localIndex, model ->
            ModelPageItem(
                index = offset + localIndex,
                name = ModelPaging.compactName(model.name),
                provider = ModelPaging.compactProvider(model.provider),
                available = model.available,
            )
        },
        offset = offset,
        nextOffset = end.takeIf { it < models.size },
        pageIndex = offset / ModelPaging.PAGE_SIZE,
        pageCount = pageCount,
        currentIndex = currentIndex,
        error = error,
    )
}

internal fun resolveGlassesModelSelection(
    models: List<ModelInfo>,
    catalogId: String,
    modelIndex: Int,
): ModelInfo? = models.getOrNull(modelIndex)
    ?.takeIf { catalogId == modelCatalogId(models) && it.available }

internal fun modelCatalogId(models: List<ModelInfo>): String {
    val source = models.joinToString(separator = "\u0000") { model ->
        "${model.ref}\u0001${model.available}"
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
