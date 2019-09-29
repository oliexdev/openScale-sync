/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.navigation.NavigationView;
import com.health.openscale.sync.BuildConfig;
import com.health.openscale.sync.R;
import com.health.openscale.sync.gui.fragments.GoogleFitFragment;
import com.health.openscale.sync.gui.fragments.MQTTFragment;
import com.health.openscale.sync.gui.fragments.OverviewFragment;

import cat.ereza.customactivityoncrash.config.CaocConfig;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity {
    public static final int COLOR_GREEN = Color.parseColor("#99CC00");
    public static final int COLOR_BLUE = Color.parseColor("#33B5E5");
    public static final int COLOR_GRAY = Color.parseColor("#d3d3d3");

    private DrawerLayout mDrawer;
    private Toolbar toolbar;
    private NavigationView nvDrawer;
    private ActionBarDrawerToggle drawerToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        CaocConfig.Builder.create()
                .trackActivities(false)
                .apply();

        // Set a Toolbar to replace the ActionBar.

        toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        mDrawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        nvDrawer = (NavigationView) findViewById(R.id.nvView);

        // Setup toggle to display hamburger icon with nice animation
        drawerToggle = new ActionBarDrawerToggle(this, mDrawer, toolbar, R.string.drawer_open,  R.string.drawer_close);
        drawerToggle.setDrawerIndicatorEnabled(true);
        drawerToggle.syncState();

        // Tie DrawerLayout events to the ActionBarToggle
        mDrawer.addDrawerListener(drawerToggle);

        selectDrawerItem(nvDrawer.getMenu().findItem(R.id.nav_overview_fragment));

        setupDrawerContent(nvDrawer);
    }

    private void setupDrawerContent(NavigationView navigationView) {
        navigationView.setNavigationItemSelectedListener(
                new NavigationView.OnNavigationItemSelectedListener() {
                    @Override
                    public boolean onNavigationItemSelected(MenuItem menuItem) {
                        selectDrawerItem(menuItem);
                        return true;
                    }
                });
    }

    public void selectDrawerItem(MenuItem menuItem) {
        // Create a new fragment and specify the fragment to show based on nav item clicked
        Fragment fragment = null;
        Class fragmentClass = OverviewFragment.class;

        switch (menuItem.getItemId()) {
            case R.id.nav_overview_fragment:
                fragmentClass = OverviewFragment.class;
                break;
            case R.id.nav_googlefit_fragment:
                fragmentClass = GoogleFitFragment.class;
                break;
            case R.id.nav_mqtt_fragment:
                fragmentClass = MQTTFragment.class;
                break;
            case R.id.nav_help:
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/oliexdev/openScale/wiki/openScale-sync")));
                return;
            case R.id.nav_about:
                showAboutDialog();
                return;
            default:
                fragmentClass = OverviewFragment.class;
        }

        try {
            fragment = (Fragment) fragmentClass.newInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Insert the fragment by replacing any existing fragment

        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.beginTransaction().replace(R.id.flContent, fragment).commit();

        // Highlight the selected item has been done by NavigationView
        menuItem.setChecked(true);

        // Set action bar title
        setTitle(menuItem.getTitle());

        // Close the navigation drawer
        mDrawer.closeDrawers();
    }

    private void showAboutDialog() {
        final SpannableString abouotMsg = new SpannableString(getResources().getString(R.string.txt_about_info));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getResources().getString(R.string.app_name) + " " + String.format("v%s (%d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                .setMessage(abouotMsg)
                .setIcon(R.drawable.ic_launcher_openscale_sync)
                .setPositiveButton(getResources().getString(R.string.txt_btn_ok), null)
                .create();

        dialog.show();
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.home:
                mDrawer.openDrawer(GravityCompat.START);
                return true;
            default:
                Timber.e("no action item found");
                break;
        }

        return super.onOptionsItemSelected(item);
    }
}
