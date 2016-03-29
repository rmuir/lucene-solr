/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.spatial.util;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.PI;

import static org.apache.lucene.util.SloppyMath.asin;
import static org.apache.lucene.util.SloppyMath.cos;
import static org.apache.lucene.util.SloppyMath.TO_DEGREES;
import static org.apache.lucene.util.SloppyMath.TO_RADIANS;

/**
 * Basic reusable geo-spatial utility methods
 *
 * @lucene.experimental
 */
public final class GeoUtils {
  /** Minimum longitude value. */
  public static final double MIN_LON_INCL = -180.0D;

  /** Maximum longitude value. */
  public static final double MAX_LON_INCL = 180.0D;

  /** Minimum latitude value. */
  public static final double MIN_LAT_INCL = -90.0D;

  /** Maximum latitude value. */
  public static final double MAX_LAT_INCL = 90.0D;
  
  /** min longitude value in radians */
  public static final double MIN_LON_RADIANS = TO_RADIANS * MIN_LON_INCL;
  /** min latitude value in radians */
  public static final double MIN_LAT_RADIANS = TO_RADIANS * MIN_LAT_INCL;
  /** max longitude value in radians */
  public static final double MAX_LON_RADIANS = TO_RADIANS * MAX_LON_INCL;
  /** max latitude value in radians */
  public static final double MAX_LAT_RADIANS = TO_RADIANS * MAX_LAT_INCL;
  
  // WGS84 earth-ellipsoid parameters
  /** major (a) axis in meters */
  public static final double SEMIMAJOR_AXIS = 6_378_137; // [m]

  // No instance:
  private GeoUtils() {
  }

  /** validates latitude value is within standard +/-90 coordinate bounds */
  public static void checkLatitude(double latitude) {
    if (Double.isNaN(latitude) || latitude < MIN_LAT_INCL || latitude > MAX_LAT_INCL) {
      throw new IllegalArgumentException("invalid latitude " +  latitude + "; must be between " + MIN_LAT_INCL + " and " + MAX_LAT_INCL);
    }
  }

  /** validates longitude value is within standard +/-180 coordinate bounds */
  public static void checkLongitude(double longitude) {
    if (Double.isNaN(longitude) || longitude < MIN_LON_INCL || longitude > MAX_LON_INCL) {
      throw new IllegalArgumentException("invalid longitude " +  longitude + "; must be between " + MIN_LON_INCL + " and " + MAX_LON_INCL);
    }
  }
  
  /** validates polygon values are within standard +/-180 coordinate bounds, same
   *  number of latitude and longitude, and is closed
   */
  public static void checkPolygon(double[] polyLats, double[] polyLons) {
    if (polyLats == null) {
      throw new IllegalArgumentException("polyLats must not be null");
    }
    if (polyLons == null) {
      throw new IllegalArgumentException("polyLons must not be null");
    }
    if (polyLats.length != polyLons.length) {
      throw new IllegalArgumentException("polyLats and polyLons must be equal length");
    }
    if (polyLats.length != polyLons.length) {
      throw new IllegalArgumentException("polyLats and polyLons must be equal length");
    }
    if (polyLats.length < 4) {
      throw new IllegalArgumentException("at least 4 polygon points required");
    }
    if (polyLats[0] != polyLats[polyLats.length-1]) {
      throw new IllegalArgumentException("first and last points of the polygon must be the same (it must close itself): polyLats[0]=" + polyLats[0] + " polyLats[" + (polyLats.length-1) + "]=" + polyLats[polyLats.length-1]);
    }
    if (polyLons[0] != polyLons[polyLons.length-1]) {
      throw new IllegalArgumentException("first and last points of the polygon must be the same (it must close itself): polyLons[0]=" + polyLons[0] + " polyLons[" + (polyLons.length-1) + "]=" + polyLons[polyLons.length-1]);
    }
    for (int i = 0; i < polyLats.length; i++) {
      checkLatitude(polyLats[i]);
      checkLongitude(polyLons[i]);
    }
  }

  /** Compute Bounding Box for a circle using WGS-84 parameters */
  public static GeoRect circleToBBox(final double centerLat, final double centerLon, final double radiusMeters) {
    final double radLat = TO_RADIANS * centerLat;
    final double radLon = TO_RADIANS * centerLon;
    // LUCENE-7143
    double radDistance = (radiusMeters + 7E-2) / SEMIMAJOR_AXIS;
    double minLat = radLat - radDistance;
    double maxLat = radLat + radDistance;
    double minLon;
    double maxLon;

    if (minLat > MIN_LAT_RADIANS && maxLat < MAX_LAT_RADIANS) {
      double deltaLon = asin(sloppySin(radDistance) / cos(radLat));
      minLon = radLon - deltaLon;
      if (minLon < MIN_LON_RADIANS) {
        minLon += 2d * PI;
      }
      maxLon = radLon + deltaLon;
      if (maxLon > MAX_LON_RADIANS) {
        maxLon -= 2d * PI;
      }
    } else {
      // a pole is within the distance
      minLat = max(minLat, MIN_LAT_RADIANS);
      maxLat = min(maxLat, MAX_LAT_RADIANS);
      minLon = MIN_LON_RADIANS;
      maxLon = MAX_LON_RADIANS;
    }

    return new GeoRect(TO_DEGREES * minLat, TO_DEGREES * maxLat, TO_DEGREES * minLon, TO_DEGREES * maxLon);
  }

  /** Compute Bounding Box for a polygon using WGS-84 parameters */
  public static GeoRect polyToBBox(double[] polyLats, double[] polyLons) {
    checkPolygon(polyLats, polyLons);

    double minLon = Double.POSITIVE_INFINITY;
    double maxLon = Double.NEGATIVE_INFINITY;
    double minLat = Double.POSITIVE_INFINITY;
    double maxLat = Double.NEGATIVE_INFINITY;

    for (int i=0;i<polyLats.length;i++) {
      minLat = min(polyLats[i], minLat);
      maxLat = max(polyLats[i], maxLat);
      minLon = min(polyLons[i], minLon);
      maxLon = max(polyLons[i], maxLon);
    }
    return new GeoRect(minLat, maxLat, minLon, maxLon);
  }
  
  // some sloppyish stuff, do we really need this to be done in a sloppy way?
  // unless it is performance sensitive, we should try to remove.
  private static final double PIO2 = Math.PI / 2D;

  /**
   * Returns the trigonometric sine of an angle converted as a cos operation.
   * <p>
   * Note that this is not quite right... e.g. sin(0) != 0
   * <p>
   * Special cases:
   * <ul>
   *  <li>If the argument is {@code NaN} or an infinity, then the result is {@code NaN}.
   * </ul>
   * @param a an angle, in radians.
   * @return the sine of the argument.
   * @see Math#sin(double)
   */
  // TODO: deprecate/remove this? at least its no longer public.
  private static double sloppySin(double a) {
    return cos(a - PIO2);
  }

  /** maximum error from {@link #axisLat(double, double)}. logic must be prepared to handle this */
  public static final double AXISLAT_ERROR = 0.1D / SEMIMAJOR_AXIS * TO_DEGREES;

  /**
   * Calculate the latitude of a circle's intersections with its bbox longitudes.
   * <p>
   * <b>NOTE:</b> the returned value will be +/- {@link #AXISLAT_ERROR} of the actual value.
   * @param centerLat The latitude of the circle center
   * @param radiusMeters The radius of the circle in meters
   * @return A latitude
   */
  public static double axisLat(double centerLat, double radiusMeters) {
    // A spherical triangle with:
    // r is the radius of the circle in radians
    // l1 is the latitude of the circle center
    // l2 is the latitude of the point at which the circle intersect's its bbox longitudes
    // We know r is tangent to longitudes at l2, therefore it is a right angle.
    // So from the law of cosines, with the angle of l1 being 90, we have:
    // cos(l1) = cos(r) * cos(l2)
    // Taking acos can be error prone
    // We transform this into:
    // l2 = PI/2 - asin( sin(PI/2 - l1) / cos(r) )
    // This ensures we always get a positive angle (0 to PI/2).

    double l1 = TO_RADIANS * centerLat;
    double r = (radiusMeters + 7E-2) / SEMIMAJOR_AXIS;

    // if we are within radius range of a pole, the lat is the pole itself
    if (l1 + r >= MAX_LAT_RADIANS) {
      return MAX_LAT_INCL;
    } else if (l1 - r <= MIN_LAT_RADIANS) {
      return MIN_LAT_INCL;
    }

    // adjust l1 as distance from closest pole, to form a right triangle with longitude lines
    if (centerLat > 0) {
      l1 = PIO2 - l1;
    } else {
      l1 += PIO2;
    }

    double l2 = PIO2 - Math.asin(Math.sin(PIO2 - l1) / Math.cos(r));
    assert !Double.isNaN(l2);
    // now adjust back to range pi/2 to -pi/2, ie latitude degrees in radians
    if (centerLat > 0) {
      l2 = PIO2 - l2;
    } else {
      l2 -= PIO2;
    }
    return TO_DEGREES * l2;
  }
}
