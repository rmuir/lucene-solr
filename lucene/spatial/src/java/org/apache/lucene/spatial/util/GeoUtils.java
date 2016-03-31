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
   * Calculate the latitude of a circle's intersections with its bbox meridians.
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
    // We know r is tangent to the bbox meridians at l2, therefore it is a right angle.
    // So from the law of cosines, with the angle of l1 being 90, we have:
    // cos(l1) = cos(r) * cos(l2) + sin(r) * sin(l2) * cos(90)
    // The second part cancels out because cos(90) == 0, so we have:
    // cos(l1) = cos(r) * cos(l2)
    // Solving for l2, we get:
    // l2 = acos( cos(l1) / cos(r) )
    // We ensure r is in the range (0, PI/2) and l1 in the range (0, PI/2]. This means we
    // cannot divide by 0, and we will always get a positive value in the range [0, 1) as
    // the argument to arc cosine, resulting in a range (0, PI/2].

    double l1 = TO_RADIANS * centerLat;
    double r = (radiusMeters + 7E-2) / SEMIMAJOR_AXIS;

    // if we are within radius range of a pole, the lat is the pole itself
    if (Math.abs(l1) + r >= MAX_LAT_RADIANS) {
      return centerLat >= 0 ? MAX_LAT_INCL : MIN_LAT_INCL;
    }

    // adjust l1 as distance from closest pole, to form a right triangle with bbox meridians
    // and ensure it is in the range (0, PI/2]
    l1 = centerLat >= 0 ? PIO2 - l1 : l1 + PIO2;

    double l2 = Math.acos(Math.cos(l1) / Math.cos(r));
    assert !Double.isNaN(l2);

    // now adjust back to range [-pi/2, pi/2], ie latitude in radians
    l2 = centerLat >= 0 ? PIO2 - l2 : l2 - PIO2;

    return TO_DEGREES * l2;
  }
}
