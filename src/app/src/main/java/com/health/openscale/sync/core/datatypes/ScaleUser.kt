/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.datatypes

import androidx.annotation.Keep

@Keep
class ScaleUser(var userid: Int, var name: String) {
    override fun toString(): String {
        return "userId $userid name $name"
    }
}