/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */

package com.health.openscale.sync.core.datatypes;

import androidx.annotation.Keep;

import java.util.Date;

@Keep
public class ScaleMeasurement {
    private long date;
    private float weight;

    public ScaleMeasurement(Date date, float weight) {
        this.date = date.getTime();
        this.weight = weight;
    }

    public Date getDate() {
        return new Date(date);
    }

    public void setDate(Date date) {
        this.date = date.getTime();
    }

    public float getWeight() {
        return weight;
    }

    public void setWeight(float weight) {
        this.weight = weight;
    }

    @Override
    public String toString() {
        return "date " + getDate() + " weight " + getWeight();
    }
}