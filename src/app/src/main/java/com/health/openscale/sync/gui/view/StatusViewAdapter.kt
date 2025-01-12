package com.health.openscale.sync.gui.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.health.openscale.sync.R
import com.health.openscale.sync.core.sync.ScaleMeasurementSync

class StatusViewAdapter(val activity: FragmentActivity, val dataSet: Array<ScaleMeasurementSync>)  : RecyclerView.Adapter<StatusViewAdapter.ViewHolder>() {
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layout_sync_status : ConstraintLayout
        val txt_sync_name: TextView

        init {
            layout_sync_status = view.findViewById(R.id.layout_sync_status)
            txt_sync_name = view.findViewById(R.id.txt_sync_name)
        }
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        // Create a new view, which defines the UI of the list item
        val view = LayoutInflater.from(viewGroup.context)
            .inflate(R.layout.status_view_item, viewGroup, false)

        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val scaleSync = dataSet[position]

        viewHolder.txt_sync_name.text = scaleSync.getName()
        viewHolder.txt_sync_name.setOnClickListener{ view ->
            if (!scaleSync.hasPermission()) {
            }
        }

    }

    override fun getItemCount() = dataSet.size
}
