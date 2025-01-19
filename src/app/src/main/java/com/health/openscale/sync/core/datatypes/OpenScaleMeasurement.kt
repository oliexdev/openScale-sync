/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.datatypes

import androidx.annotation.Keep
import java.util.Date

@Keep
data class OpenScaleMeasurement(
    val id: Int,
    val date: Date,
    val weight: Float,
    val fat: Float,
    val water: Float,
    val muscle: Float
)