package com.example.kevin.wear_where.AsyncTask;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.AsyncTask;

import com.example.kevin.wear_where.Google.Distance.DistanceMatrixObject;
import com.example.kevin.wear_where.Google.TimeZone.TimeZoneObject;
import com.example.kevin.wear_where.WundergroundData.HourlyForecast.HourlyObject;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.GregorianCalendar;

/**
 * Created by Hermes on 10/23/2016.
 */

public class IntervalInformationAST extends AsyncTask<Void, Void, ArrayList<MarkerOptions>> {

    /* NOTE: For all arrays involving intervals, the order is as follows:
     * Index 0 = Starting Point
     * Index 1 = First interval after starting
     * Index 2 = Second interval after starting
     * ...
     * Index ArrayList<>.size() - 1 = Ending Point
     */

    // ArrayList to keep track of the intervals to get set up markers for
    private ArrayList<LatLng> intervals;

    // ArrayList to keep track of the markers for each interval
    private ArrayList<MarkerOptions> intervalInformation;

    // DistanceMatrixObject containing the information for the Google Distance Matrix API (distance between starting point and each interval)
    private DistanceMatrixObject distanceMatrixObject = null;

    // ArrayList to keep track of the Google Time Zone API information for each interval
    private ArrayList<TimeZoneObject> intervalTimeZones;

    // ArrayList to keep track of the Wunderground 10 Day Hourly API information for each interval
    private ArrayList<HourlyObject> mapsHourlyForecast;

    // Get the current UTC Epoch Time in milliseconds
    private Long currentTimeSeconds = System.currentTimeMillis() - ((new GregorianCalendar().getTimeZone().getRawOffset()) + new GregorianCalendar().getTimeZone().getDSTSavings());

    public IntervalInformationAST(ArrayList<LatLng> intervals) {

        // Save parameter for future use
        this.intervals = intervals;

        // Initialize the ArrayLists for the AsyncTasks
        intervalInformation = new ArrayList<>();
        intervalTimeZones = new ArrayList<>();
        mapsHourlyForecast = new ArrayList<>();

        // Begin GoogleDistanceAST
        new GoogleDistanceAST(intervals) {

            @Override
            protected void onPostExecute(DistanceMatrixObject item) {

                // Get a handle to the returned object from GoogleDistanceAST
                distanceMatrixObject = item;

            }

        }.execute();

        // For each object in the intervals ArrayList, get the corresponding HourlyObject
        for (int i = 0; i < intervals.size(); ++i) {

            // Begin MapsForecastAST (if all goes well, the AsyncTasks will complete in the order they started)
            new MapsForecastAST(intervals.get(i)) {

                @Override
                protected void onPostExecute(HourlyObject item) {

                    // Add the HourlyObject returned to the mapsHourlyForecast (if all goes well, the order of objects added will correspond to the order of intervals)
                    mapsHourlyForecast.add(item);

                }

            }.execute();

        }

        // For each object in the intervals ArrayList, get the corresponding TimeZoneObject
        for (int i = 0; i < intervals.size(); ++i) {

            // Begin GoogleTimeZoneAST (if all goes well, the AsyncTasks will complete in the order they started)
            new GoogleTimeZoneAST(intervals.get(i), currentTimeSeconds/1000) {

                @Override
                protected void onPostExecute(TimeZoneObject item) {

                    // Add the TimeZoneObject returned to the mapsHourlyForecast (if all goes well, the order of objects added will correspond to the order of intervals)
                    intervalTimeZones.add(item);

                }

            }.execute();

        }
    }

    @Override
    protected ArrayList<MarkerOptions> doInBackground(Void... params) throws IllegalStateException {

        // Don't proceed until we have the distanceMatrixObject
        while (distanceMatrixObject == null) {
        }

        // Don't proceed until we have all information for all intervals
        while (intervals.size() != mapsHourlyForecast.size() && intervals.size() != intervalTimeZones.size()) {
        }

        // Get the current UTC Epoch Time in milliseconds
        Long currentTime = System.currentTimeMillis() - ((new GregorianCalendar().getTimeZone().getRawOffset()) + new GregorianCalendar().getTimeZone().getDSTSavings());

        // Create the list of MarkerOptions to add to the map
        for (int i = 0; i < intervals.size(); ++i) {

            // Get the duration for the current interval in milliseconds
            Long intervalDuration = Long.parseLong((distanceMatrixObject.getRowsArray().getElementsArray().getElementsArrayItems().get(i).getDuration().getSeconds())) * 1000;

            // Get the DST offset in milliseconds from the TimeZoneObject corresponding to the current object
            Long endingDSTOffset = Long.parseLong(intervalTimeZones.get(i).getDstOffset()) * 1000;

            // Get the Raw offset in milliseconds from the TimeZoneObject corresponding to the current object
            Long endingRawOffset = Long.parseLong(intervalTimeZones.get(i).getRawOffset()) * 1000;

            // Create the string representing the estimated time of arrival to the current interval point
            String estimatedTimeOfArrival = new java.text.SimpleDateFormat("MM/dd/yyyy hh:mm:ss a").format(new java.util.Date(currentTime + intervalDuration + (endingDSTOffset + endingRawOffset))) + '\n' + intervalTimeZones.get(i).getTimeZoneName();

            // Set the index for getting the correct hourly information (the index is how many hours it takes to get to the current interval location)
            int index = (int) (intervalDuration / (3600 * 1000));

            // Declare the strings that will be used to represent the precipitation, temperature (in farenheit), and the iconURL
            String precipitation, temperatureF, iconURL;

            // If the index is within range of the 10 days, then initialize the strings to the correct information from the corresponding hourly object
            if (index <= 240) {
                precipitation = mapsHourlyForecast.get(i).getCondition(index);
                temperatureF = Integer.toString(mapsHourlyForecast.get(i).getTemperatureF(index)) + (char) 0x00B0 + " F";
                iconURL = mapsHourlyForecast.get(i).getIconURL(index);
            }

            // Else, tell the user that we cannot get information for this interval
            else {
                precipitation = "Cannot get hourly information more than 10 days ahead!";
                temperatureF = "";
                iconURL = "http://www.errorfixz.com/wp-content/uploads/2016/02/question_red.png";
            }

            // Declare bitmap variable to store the interval weather icon
            Bitmap bitmap;

            // Try and get the bitmap image from the icon url provided in the hourlyObject
            try {

                // Create a URL object with the link stored in iconURL
                URL request = new URL(iconURL);

                // Open a URL connection to link
                HttpURLConnection urlConnection = (HttpURLConnection) request.openConnection();

                // Set up the connection to take in the contents as input from the specified link in request
                urlConnection.setDoInput(true);

                // Connect to the link provided in request
                urlConnection.connect();

                // Store the input from the link provided in request
                InputStream input = urlConnection.getInputStream();

                // Create the bitmap from the input
                bitmap = BitmapFactory.decodeStream(input);

                // If we get a valid bitmap, then scale the bitmap according to screen density
                if (bitmap != null) {
                    bitmap = Bitmap.createScaledBitmap(bitmap, (int) (24 * Resources.getSystem().getDisplayMetrics().density), (int) (24 * Resources.getSystem().getDisplayMetrics().density), false);
                }

            }

            // If getting the image failed, return a null bitmap
            catch (Exception e) {
                return null;
            }

            // If i == 0, we are creating the marker for the starting point
            if (i == 0) {
                intervalInformation.add(new MarkerOptions().position(intervals.get(0))
                        .title("Origin")
                        .snippet(distanceMatrixObject.getDestinationAddressesArray().getDestinationAddressesArrayItems().get(0) + '\n' +
                                "Distance Travelled: " + distanceMatrixObject.getRowsArray().getElementsArray().getElementsArrayItems().get(i).getDistance().getDistance() + '\n' +
                                "Time elapsed: " + distanceMatrixObject.getRowsArray().getElementsArray().getElementsArrayItems().get(i).getDuration().getDuration() + "\n\n" +
                                "Time at beginning of trip: " + '\n' + estimatedTimeOfArrival + "\n\n" +
                                "Weather at ETA: " + '\n' + precipitation + '\n' + temperatureF)
                        .icon(BitmapDescriptorFactory.fromBitmap(bitmap)));
            }

            // If i == interval.size() - 1, we are creating the marker for the ending point
            else if (i == intervals.size() - 1) {
                intervalInformation.add(new MarkerOptions().position(intervals.get(i))
                        .title("Destination")
                        .snippet(distanceMatrixObject.getDestinationAddressesArray().getDestinationAddressesArrayItems().get(i) + '\n' +
                                "Distance Travelled: " + distanceMatrixObject.getRowsArray().getElementsArray().getElementsArrayItems().get(i).getDistance().getDistance() + '\n' +
                                "Time elapsed: " + distanceMatrixObject.getRowsArray().getElementsArray().getElementsArrayItems().get(i).getDuration().getDuration() + "\n\n" +
                                "Estimated Arrival Time: " + '\n' + estimatedTimeOfArrival + "\n\n" +
                                "Weather at ETA: " + '\n' + precipitation + '\n' + temperatureF)
                        .icon(BitmapDescriptorFactory.fromBitmap(bitmap)));
            }

            // Else, we are creating a marker for an interval point
            else {
                intervalInformation.add(new MarkerOptions().position(intervals.get(i))
                        .title("Interval")
                        .snippet(distanceMatrixObject.getDestinationAddressesArray().getDestinationAddressesArrayItems().get(i) + '\n' +
                                "Distance Travelled: " + distanceMatrixObject.getRowsArray().getElementsArray().getElementsArrayItems().get(i).getDistance().getDistance() + '\n' +
                                "Time elapsed: " + distanceMatrixObject.getRowsArray().getElementsArray().getElementsArrayItems().get(i).getDuration().getDuration() + "\n\n" +
                                "Estimated Arrival Time: " + '\n' + estimatedTimeOfArrival + "\n\n" +
                                "Weather at ETA: " + '\n' + precipitation + '\n' + temperatureF)
                        .icon(BitmapDescriptorFactory.fromBitmap(bitmap)));
            }
        }

        // Return the ArrayList of MarkerOptions
        return intervalInformation;
    }
}
