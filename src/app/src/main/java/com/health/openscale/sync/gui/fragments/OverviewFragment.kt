/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.health.openscale.sync.R
import com.health.openscale.sync.core.sync.HealthConnectSync
import com.health.openscale.sync.core.sync.MQTTSync
import com.health.openscale.sync.gui.view.StatusViewAdapter

class OverviewFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val fragment = inflater.inflate(R.layout.fragment_overview, container, false)

        val dataset = arrayOf(HealthConnectSync(requireContext()), MQTTSync(requireContext()))
        val statusViewAdapter = StatusViewAdapter(requireActivity(), dataset)

        val recyclerView: RecyclerView = fragment.findViewById(R.id.status_overview)
        recyclerView.layoutManager = GridLayoutManager(context,2)
        recyclerView.adapter = statusViewAdapter

        return fragment
    }
}
