/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import cat.ereza.customactivityoncrash.config.CaocConfig
import com.google.android.material.navigation.NavigationView
import com.health.openscale.sync.BuildConfig
import com.health.openscale.sync.R
import com.health.openscale.sync.gui.fragments.HealthConnectFragment
import com.health.openscale.sync.gui.fragments.MQTTFragment
import com.health.openscale.sync.gui.fragments.OverviewFragment
import com.health.openscale.sync.gui.fragments.WgerFragment
import timber.log.Timber

class MainActivity : AppCompatActivity() {
    private var mDrawer: DrawerLayout? = null
    private var toolbar: Toolbar? = null
    private var nvDrawer: NavigationView? = null
    private var drawerToggle: ActionBarDrawerToggle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        CaocConfig.Builder.create()
            .trackActivities(false)
            .apply()

        // Set a Toolbar to replace the ActionBar.
        toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        mDrawer = findViewById<View>(R.id.drawer_layout) as DrawerLayout
        nvDrawer = findViewById<View>(R.id.nvView) as NavigationView

        // Setup toggle to display hamburger icon with nice animation
        drawerToggle = ActionBarDrawerToggle(
            this,
            mDrawer,
            toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        )
        drawerToggle!!.isDrawerIndicatorEnabled = true
        drawerToggle!!.syncState()

        // Tie DrawerLayout events to the ActionBarToggle
        mDrawer!!.addDrawerListener(drawerToggle!!)

        selectDrawerItem(nvDrawer!!.menu.findItem(R.id.nav_overview_fragment))

        setupDrawerContent(nvDrawer)
    }

    private fun setupDrawerContent(navigationView: NavigationView?) {
        navigationView!!.setNavigationItemSelectedListener { menuItem ->
            selectDrawerItem(menuItem)
            true
        }
    }

    fun selectDrawerItem(menuItem: MenuItem) {
        // Create a new fragment and specify the fragment to show based on nav item clicked
        var fragment: Fragment? = null
        var fragmentClass: Class<*> = OverviewFragment::class.java

        fragmentClass = when (menuItem.itemId) {
            R.id.nav_overview_fragment -> OverviewFragment::class.java
            R.id.nav_health_connect_fragment -> HealthConnectFragment::class.java
            R.id.nav_mqtt_fragment -> MQTTFragment::class.java
            R.id.nav_wger_fragment -> WgerFragment::class.java
            R.id.nav_help -> {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/oliexdev/openScale/wiki/openScale-sync")
                    )
                )
                return
            }

            R.id.nav_about -> {
                showAboutDialog()
                return
            }

            else -> OverviewFragment::class.java
        }
        try {
            fragment = fragmentClass.newInstance() as Fragment
        } catch (e: Exception) {
            Timber.e(e.message)
        }

        // Insert the fragment by replacing any existing fragment
        val fragmentManager = supportFragmentManager
        fragmentManager.beginTransaction().replace(R.id.flContent, fragment!!).commit()

        // Highlight the selected item has been done by NavigationView
        menuItem.setChecked(true)

        // Set action bar title
        title = menuItem.title

        // Close the navigation drawer
        mDrawer!!.closeDrawers()
    }

    private fun showAboutDialog() {
        val abouotMsg = SpannableString(resources.getString(R.string.txt_about_info))

        val dialog = AlertDialog.Builder(this)
            .setTitle(
                resources.getString(R.string.app_name) + " " + String.format(
                    "v%s (%d)",
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                )
            )
            .setMessage(abouotMsg)
            .setIcon(R.drawable.ic_launcher_openscale_sync)
            .setPositiveButton(resources.getString(R.string.txt_btn_ok), null)
            .create()

        dialog.show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.home -> {
                mDrawer!!.openDrawer(GravityCompat.START)
                return true
            }

            else -> Timber.e("no action item found")
        }
        return super.onOptionsItemSelected(item)
    }
}
