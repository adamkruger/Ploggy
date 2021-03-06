/*
 * Copyright (c) 2013, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package ca.psiphon.ploggy;

import java.io.IOException;
import java.util.List;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;

/**
 * Schedule and monitor location events from Android OS. 
 * 
 * Implements best practices from:
 * - http://developer.android.com/guide/topics/location/strategies.html
 * - http://code.google.com/p/android-protips-location/
 * 
 * Does not use the newer, higher level Play Services location API as it's
 * not available on open source Android builds and its source is not available
 * to review (e.g., verify that location isn't sent to 3rd party).  
 */
public class LocationMonitor implements android.location.LocationListener {
    
    private static final String LOG_TAG = "Location Monitor";

    Engine mEngine;    
    Handler mHandler;
    Runnable mStartLocationFixTask;
    Runnable mFinishLocationFixTask;
    Runnable mStopLocationUpdatesTask;
    Location mLastReportedLocation;
    Location mCurrentLocation;
    
    LocationMonitor(Engine engine) {
        mEngine = engine;
        mHandler = new Handler();
        initRunnables();
    }
    
    public void start() throws Utils.ApplicationError {
        // Using a Handler for LocationManager calls, which need to run on a Looper thread. StartLocationFixTask
        // kicks off location updates and schedules FinishLocationFixTask which reports the "best" location fix
        // and stops updates after a set time period. FinishLocationFixTask also schedules the next location fix.
        mHandler.post(mStartLocationFixTask);
    }
    
    public void stop() {
        mHandler.removeCallbacks(mStartLocationFixTask);
        mHandler.removeCallbacks(mFinishLocationFixTask);
        // Ensure removeUpdates is called
        // TODO: ok that posting this task makes stop() asynchronous?
        mHandler.post(mStopLocationUpdatesTask);
    }

    private void initRunnables() {
        final LocationMonitor finalLocationMonitor = this; 

        mStartLocationFixTask = new Runnable() {
            @Override
            public void run() {
                try {
                    LocationManager locationManager = (LocationManager)mEngine.getContext().getSystemService(Context.LOCATION_SERVICE);
    
                    // Use last known location already present in all providers (they don't need to be enabled)
                    for (String provider: locationManager.getAllProviders()) {
                        updateCurrentLocation(locationManager.getLastKnownLocation(provider));
                    }
    
                    if (locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                        locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000, 1, finalLocationMonitor);    
                    }
                    
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, finalLocationMonitor);    
                    }
                    
                    // TODO: previously had a preference to allow use of Network location provider, since this provider
                    // sends data to a 3rd party. But is the provider always sending this data? I.e., is there any privacy
                    // benefit to not using it if it's available?
                    
                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1, finalLocationMonitor);    
                    }
                    
                    mHandler.postDelayed(
                            mFinishLocationFixTask,
                            1000*mEngine.getIntPreference(R.string.preferenceLocationFixPeriodInSeconds));
                } catch (Utils.ApplicationError e) {
                    Log.addEntry(LOG_TAG, "start location fix failed");
                }
            }
        };

        mFinishLocationFixTask = new Runnable() {
            @Override
            public void run() {
                try {
                    LocationManager locationManager = (LocationManager)mEngine.getContext().getSystemService(Context.LOCATION_SERVICE);
                    locationManager.removeUpdates(finalLocationMonitor);
                    
                    reportLocation();
    
                    // TODO: simulate scheduleAtFixedrate by adjusting next fix delay to account for elapsed fix time period
                    mHandler.postDelayed(
                            mStartLocationFixTask,
                            60*1000*mEngine.getIntPreference(R.string.preferenceLocationFixFrequencyInMinutes));
                } catch (Utils.ApplicationError e) {
                    Log.addEntry(LOG_TAG, "finish location fix failed");
                }
            }
        };

        mStopLocationUpdatesTask = new Runnable() {
            @Override
            public void run() {
                LocationManager locationManager = (LocationManager)mEngine.getContext().getSystemService(Context.LOCATION_SERVICE);
                locationManager.removeUpdates(finalLocationMonitor);        
            }
        };
    }

    public void reportLocation() throws Utils.ApplicationError {
        if (mCurrentLocation == null) {
            return;
        }

        mLastReportedLocation = mCurrentLocation;

        if (mEngine.getBooleanPreference(R.string.preferenceUseGeoCoder)) {
            // Run a background task to map and reverse geocode the location
            Runnable task = new Runnable() {
                public void run() {
                    Geocoder geocoder = new Geocoder(mEngine.getContext());
                    List<Address> addresses = null;
                    try {
                        // TODO: Google terms of service prohibit use of this data with non-Google maps.
                        //       In any case, all will be replaced with Open Street Map (geocoding and maps)
                        addresses = geocoder.getFromLocation(
                                mLastReportedLocation.getLatitude(),
                                mLastReportedLocation.getLongitude(),
                                1);
                    } catch (IOException e) {
                        Log.addEntry(LOG_TAG, "reverse geocode failed: " + e.getMessage());
                    }
                    
                    // TODO: get map                    
                    Address address = (addresses != null && addresses.size() > 0) ? addresses.get(0) : null;
                    Events.post(new Events.NewSelfLocation(mLastReportedLocation, address));
                }
            };
            mEngine.submitTask(task);
            
        } else {
            Events.post(new Events.NewSelfLocation(mLastReportedLocation, null));
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        updateCurrentLocation(location);
    }

    @Override
    public void onProviderDisabled(String provider) {
        restart();
    }

    @Override
    public void onProviderEnabled(String provider) {
        restart();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        restart();
    }

    private void restart() {
        try {
            stop();
            start();
        } catch (Utils.ApplicationError e) {
            Log.addEntry(LOG_TAG, "failed to restart");
        }
    }

    private void updateCurrentLocation(Location location) {
        if (location != null) {
            if (isBetterLocation(location, mCurrentLocation)) {
                mCurrentLocation = location;
            }
        }
    }
    
    // From: http://developer.android.com/guide/topics/location/strategies.html
    private boolean isBetterLocation(Location location, Location currentBestLocation) {

        final int TWO_MINUTES = 1000 * 60 * 2;

        if (currentBestLocation == null) {
            // A new location is always better than no location
            return true;
        }

        // Check whether the new location fix is newer or older
        long timeDelta = location.getTime() - currentBestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
        boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
        boolean isNewer = timeDelta > 0;

        // If it's been more than two minutes since the current location, use the new location
        // because the user has likely moved
        if (isSignificantlyNewer) {
            return true;
        // If the new location is more than two minutes older, it must be worse
        } else if (isSignificantlyOlder) {
            return false;
        }

        // Check whether the new location fix is more or less accurate
        int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        // Check if the old and new location are from the same provider
        boolean isFromSameProvider = isSameProvider(location.getProvider(),
                currentBestLocation.getProvider());

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            return true;
        }
        return false;
    }

    private boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
          return provider2 == null;
        }
        return provider1.equals(provider2);
    }
}
