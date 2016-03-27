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

import org.apache.lucene.util.SloppyMath;

import static java.lang.Math.atan;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.PI;
import static java.lang.Math.abs;

import static java.lang.Math.tan;
import static org.apache.lucene.util.SloppyMath.asin;
import static org.apache.lucene.util.SloppyMath.cos;
import static org.apache.lucene.util.SloppyMath.TO_DEGREES;
import static org.apache.lucene.util.SloppyMath.TO_RADIANS;
import static org.apache.lucene.spatial.util.GeoEncodingUtils.TOLERANCE;

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
  public static boolean isValidLat(double lat) {
    return Double.isNaN(lat) == false && lat >= MIN_LAT_INCL && lat <= MAX_LAT_INCL;
  }

  /** validates longitude value is within standard +/-180 coordinate bounds */
  public static boolean isValidLon(double lon) {
    return Double.isNaN(lon) == false && lon >= MIN_LON_INCL && lon <= MAX_LON_INCL;
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
    if (polyLats.length != polyLons.length) {
      throw new IllegalArgumentException("polyLats and polyLons must be equal length");
    }

    double minLon = Double.POSITIVE_INFINITY;
    double maxLon = Double.NEGATIVE_INFINITY;
    double minLat = Double.POSITIVE_INFINITY;
    double maxLat = Double.NEGATIVE_INFINITY;

    for (int i=0;i<polyLats.length;i++) {
      if (GeoUtils.isValidLat(polyLats[i]) == false) {
        throw new IllegalArgumentException("invalid polyLats[" + i + "]=" + polyLats[i]);
      }
      if (GeoUtils.isValidLon(polyLons[i]) == false) {
        throw new IllegalArgumentException("invalid polyLons[" + i + "]=" + polyLons[i]);
      }
      minLat = min(polyLats[i], minLat);
      maxLat = max(polyLats[i], maxLat);
      minLon = min(polyLons[i], minLon);
      maxLon = max(polyLons[i], maxLon);
    }
    // expand bounding box by TOLERANCE factor to handle round-off error
    return new GeoRect(max(minLat - TOLERANCE, MIN_LAT_INCL), min(maxLat + TOLERANCE, MAX_LAT_INCL),
                       max(minLon - TOLERANCE, MIN_LON_INCL), min(maxLon + TOLERANCE, MAX_LON_INCL));
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

  public static double axisLat(double centerLat, double radiusMeters) {
    // spherical triangle with:
    // A is angle between longitudes at the north pole
    // a is the radius of the circle in radians
    // b is the latitude of the circle center (ie the distance from the north pole to the circle center)
    // B is the angle between the circle radius and the axis latitude
    // c is the latitude of the axis (ie the distance from the north pole to the intersection of the radius and bbox longitude)
    final double radLat = TO_RADIANS * centerLat; // b
    double radDistance = radiusMeters / GeoUtils.SEMIMAJOR_AXIS; // a
    double deltaLon = asin(sloppySin(radDistance) / cos(radLat)); // A
    // solve for B, using sine rule of spherical trig:  sin b * sin A / sin a = sin B
    double latAngle = asin(sloppySin(radLat) * sloppySin(deltaLon) / sloppySin(radDistance)); // B
    assert Double.isFinite(latAngle);
    // napier's analogies, solve for the final side c:
    // 2 equations, the may produce the same result:
    // sin (0.5 * (A - B)) / sin (0.5 * (A + B)) = tan (0.5 * (a - b)) / tan (0.5 * c)
    // cos (0.5 * (A - B)) / cos (0.5 * (A + B)) = tan (0.5 * (a + b)) / tan (0.5 * c)
    double axisLat1 = 2D * atan( tan( 0.5D * (radDistance - radLat)) * sloppySin( 0.5D * (deltaLon + latAngle)) / sloppySin( 0.5D * (deltaLon - latAngle)));
    double axisLat2 = 2D * atan( tan( 0.5D * (radDistance + radLat)) * cos( 0.5D * (deltaLon + latAngle)) / cos( 0.5D * (deltaLon - latAngle)));
    /* some logic here for when to use axisLat1...
    if () {
      return TO_DEGREES * axisLat1;
    }
    */
    double axisLat1Deg = TO_DEGREES * axisLat1;
    double axisLat2Deg = TO_DEGREES * axisLat2;
    return TO_DEGREES * axisLat2;
  }
}
