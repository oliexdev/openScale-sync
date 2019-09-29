/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */

package com.health.openscale.sync.core.datatypes;

public class ScaleUser {
    public ScaleUser(int userid, String name) {
        this.userid = userid;
        this.name = name;
    }

    @Override
    public String toString() {
        return "userId " + userid + " name " + name;
    }

    public int userid;
    public String name;
}