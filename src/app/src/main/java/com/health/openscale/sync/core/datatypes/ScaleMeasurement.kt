/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.datatypes

import androidx.annotation.Keep
import java.util.Date

@Keep
class ScaleMeasurement(var date: Date, var weight: Float) {
    override fun toString(): String {
        return "date $date weight $weight"
    }
}