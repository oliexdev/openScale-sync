/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.gui;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

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
import com.health.openscale.sync.gui.utils.DebugTree;

import cat.ereza.customactivityoncrash.config.CaocConfig;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity {
    public static final int COLOR_GREEN = Color.parseColor("#99CC00");
    public static final int COLOR_BLUE = Color.parseColor("#33B5E5");
    public static final int COLOR_GRAY = Color.parseColor("#d3d3d3");

    private final int DEBUG_WRITE_PERMISSIONS_REQUEST_CODE = 102;

    private DebugTree debugTree;
    private Menu actionMenu;

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

        debugTree = new DebugTree();

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
        Class fragmentClass;

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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.action_menu, menu);

        actionMenu = menu;

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case android.R.id.home:
                mDrawer.openDrawer(GravityCompat.START);
                return true;
            case R.id.actionAbout:
                final SpannableString abouotMsg = new SpannableString(getResources().getString(R.string.txt_about_info));
                Linkify.addLinks(abouotMsg, Linkify.WEB_URLS);

                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(getResources().getString(R.string.app_name) + " " + String.format("v%s (%d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                        .setMessage(abouotMsg)
                        .setIcon(R.drawable.ic_launcher_openscale_sync)
                        .setPositiveButton(getResources().getString(R.string.txt_btn_ok), null)
                        .create();

                dialog.show();

                ((TextView)dialog.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance());
                break;
            case R.id.actionDebug:
                if (item.isChecked()) {
                    debugTree.close();
                    item.setChecked(false);
                } else {
                    startActivityForResult(debugTree.requestDebugIntent(), DEBUG_WRITE_PERMISSIONS_REQUEST_CODE);
                }
                break;
            default:
                Timber.e("no action item found");
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == DEBUG_WRITE_PERMISSIONS_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            debugTree.startLogTo(this, data.getData());
            MenuItem actionDebug = actionMenu.findItem(R.id.actionDebug);
            actionDebug.setChecked(true);
        }
    }
}
