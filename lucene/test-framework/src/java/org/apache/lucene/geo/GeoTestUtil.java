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
package org.apache.lucene.geo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.SloppyMath;
import org.apache.lucene.util.TestUtil;

import com.carrotsearch.randomizedtesting.RandomizedContext;

/** static methods for testing geo */
public class GeoTestUtil {

  /** returns next pseudorandom latitude (anywhere) */
  public static double nextLatitude() {
    return nextDoubleInternal(-90, 90);
  }

  /** returns next pseudorandom longitude (anywhere) */
  public static double nextLongitude() {
    return nextDoubleInternal(-180, 180);
  }
  
  /**
   * Returns next double within range.
   * <p>
   * Don't pass huge numbers or infinity or anything like that yet. may have bugs!
   */
  // the goal is to adjust random number generation to test edges, create more duplicates, create "one-offs" in floating point space, etc.
  // we do this by first picking a good "base value" (explicitly targeting edges, zero if allowed, or "discrete values"). but it also
  // ensures we pick any double in the range and generally still produces randomish looking numbers.
  // then we sometimes perturb that by one ulp.
  private static double nextDoubleInternal(double low, double high) {
    assert low >= Integer.MIN_VALUE;
    assert high <= Integer.MAX_VALUE;
    assert Double.isFinite(low);
    assert Double.isFinite(high);
    assert high >= low : "low=" + low + " high=" + high;
    
    // if they are equal, not much we can do
    if (low == high) {
      return low;
    }

    // first pick a base value.
    final double baseValue;
    int surpriseMe = random().nextInt(17);
    if (surpriseMe == 0) {
      // random bits
      long lowBits = NumericUtils.doubleToSortableLong(low);
      long highBits = NumericUtils.doubleToSortableLong(high);
      baseValue = NumericUtils.sortableLongToDouble(TestUtil.nextLong(random(), lowBits, highBits));
    } else if (surpriseMe == 1) {
      // edge case
      baseValue = low;
    } else if (surpriseMe == 2) {
      // edge case
      baseValue = high;
    } else if (surpriseMe == 3 && low <= 0 && high >= 0) {
      // may trigger divide by 0
      baseValue = 0.0;
    } else if (surpriseMe == 4) {
      // divide up space into block of 360
      double delta = (high - low) / 360;
      int block = random().nextInt(360);
      baseValue = low + delta * block;
    } else {
      // distributed ~ evenly
      baseValue = low + (high - low) * random().nextDouble();
    }

    assert baseValue >= low;
    assert baseValue <= high;

    // either return the base value or adjust it by 1 ulp in a random direction (if possible)
    int adjustMe = random().nextInt(17);
    if (adjustMe == 0) {
      return Math.nextAfter(adjustMe, high);
    } else if (adjustMe == 1) {
      return Math.nextAfter(adjustMe, low);
    } else {
      return baseValue;
    }
  }

  /** returns next pseudorandom latitude, kinda close to {@code otherLatitude} */
  public static double nextLatitudeNear(double otherLatitude) {
    GeoUtils.checkLatitude(otherLatitude);
    int surpriseMe = random().nextInt(11);
    if (surpriseMe == 10) {
      // purely random
      return nextLatitude();
    } else if (surpriseMe < 5) {
      // upper half of region (the exact point or 1 ulp difference is still likely)
      return nextDoubleInternal(otherLatitude, Math.min(90, otherLatitude + 0.5));
    } else {
      // lower half of region (the exact point or 1 ulp difference is still likely)
      return nextDoubleInternal(Math.max(-90, otherLatitude - 0.5), otherLatitude);
    }
  }

  /** returns next pseudorandom longitude, kinda close to {@code otherLongitude} */
  public static double nextLongitudeNear(double otherLongitude) {
    GeoUtils.checkLongitude(otherLongitude);
    int surpriseMe = random().nextInt(11);
    if (surpriseMe == 10) {
      // purely random
      return nextLongitude();
    } else if (surpriseMe < 5) {
      // upper half of region (the exact point or 1 ulp difference is still likely)
      return nextDoubleInternal(otherLongitude, Math.min(180, otherLongitude + 0.5));
    } else {
      // lower half of region (the exact point or 1 ulp difference is still likely)
      return nextDoubleInternal(Math.max(-180, otherLongitude - 0.5), otherLongitude);
    }
  }

  /**
   * returns next pseudorandom latitude, kinda close to {@code minLatitude/maxLatitude}
   * <b>NOTE:</b>minLatitude/maxLatitude are merely guidelines. the returned value is sometimes
   * outside of that range! this is to facilitate edge testing.
   */
  public static double nextLatitudeAround(double minLatitude, double maxLatitude) {
    assert maxLatitude >= minLatitude;
    GeoUtils.checkLatitude(minLatitude);
    GeoUtils.checkLatitude(maxLatitude);
    if (random().nextInt(47) == 0) {
      // purely random
      return nextLatitude();
    } else {
      // extend the range by 1%
      double difference = (maxLatitude - minLatitude) / 100;
      double lower = Math.max(-90, minLatitude - difference);
      double upper = Math.min(90, maxLatitude + difference);
      return nextDoubleInternal(lower, upper);
    }
  }

  /**
   * returns next pseudorandom longitude, kinda close to {@code minLongitude/maxLongitude}
   * <b>NOTE:</b>minLongitude/maxLongitude are merely guidelines. the returned value is sometimes
   * outside of that range! this is to facilitate edge testing.
   */
  public static double nextLongitudeAround(double minLongitude, double maxLongitude) {
    assert maxLongitude >= minLongitude;
    GeoUtils.checkLongitude(minLongitude);
    GeoUtils.checkLongitude(maxLongitude);
    if (random().nextInt(47) == 0) {
      // purely random
      return nextLongitude();
    } else {
      // extend the range by 1%
      double difference = (maxLongitude - minLongitude) / 100;
      double lower = Math.max(-180, minLongitude - difference);
      double upper = Math.min(180, maxLongitude + difference);
      return nextDoubleInternal(lower, upper);
    }
  }

  /** returns next pseudorandom box: can cross the 180th meridian */
  public static Rectangle nextBox() {
    return nextBoxInternal(nextLatitude(), nextLatitude(), nextLongitude(), nextLongitude(), true);
  }
  
  /** returns next pseudorandom box: will not cross the 180th meridian */
  public static Rectangle nextSimpleBox() {
    return nextBoxInternal(nextLatitude(), nextLatitude(), nextLongitude(), nextLongitude(), false);
  }

  /** returns next pseudorandom box, can cross the 180th meridian, kinda close to {@code otherLatitude} and {@code otherLongitude} */
  public static Rectangle nextBoxNear(double otherLatitude, double otherLongitude) {
    GeoUtils.checkLongitude(otherLongitude);
    GeoUtils.checkLongitude(otherLongitude);
    return nextBoxInternal(nextLatitudeNear(otherLatitude), nextLatitudeNear(otherLatitude),
                           nextLongitudeNear(otherLongitude), nextLongitudeNear(otherLongitude), true);
  }
  
  /** returns next pseudorandom box, will not cross the 180th meridian, kinda close to {@code otherLatitude} and {@code otherLongitude} */
  public static Rectangle nextSimpleBoxNear(double otherLatitude, double otherLongitude) {
    GeoUtils.checkLongitude(otherLongitude);
    GeoUtils.checkLongitude(otherLongitude);
    return nextBoxInternal(nextLatitudeNear(otherLatitude), nextLatitudeNear(otherLatitude),
                           nextLongitudeNear(otherLongitude), nextLongitudeNear(otherLongitude), false);
  }

  /** Makes an n-gon, centered at the provided lat/lon, and each vertex approximately
   *  distanceMeters away from the center.
   *
   * Do not invoke me across the dateline or a pole!! */
  public static Polygon createRegularPolygon(double centerLat, double centerLon, double radiusMeters, int gons) {

    // System.out.println("MAKE POLY: centerLat=" + centerLat + " centerLon=" + centerLon + " radiusMeters=" + radiusMeters + " gons=" + gons);

    double[][] result = new double[2][];
    result[0] = new double[gons+1];
    result[1] = new double[gons+1];
    //System.out.println("make gon=" + gons);
    for(int i=0;i<gons;i++) {
      double angle = 360.0-i*(360.0/gons);
      //System.out.println("  angle " + angle);
      double x = Math.cos(Math.toRadians(angle));
      double y = Math.sin(Math.toRadians(angle));
      double factor = 2.0;
      double step = 1.0;
      int last = 0;

      //System.out.println("angle " + angle + " slope=" + slope);
      // Iterate out along one spoke until we hone in on the point that's nearly exactly radiusMeters from the center:
      while (true) {

        // TODO: we could in fact cross a pole?  Just do what surpriseMePolygon does?
        double lat = centerLat + y * factor;
        GeoUtils.checkLatitude(lat);
        double lon = centerLon + x * factor;
        GeoUtils.checkLongitude(lon);
        double distanceMeters = SloppyMath.haversinMeters(centerLat, centerLon, lat, lon);

        //System.out.println("  iter lat=" + lat + " lon=" + lon + " distance=" + distanceMeters + " vs " + radiusMeters);
        if (Math.abs(distanceMeters - radiusMeters) < 0.1) {
          // Within 10 cm: close enough!
          result[0][i] = lat;
          result[1][i] = lon;
          break;
        }

        if (distanceMeters > radiusMeters) {
          // too big
          //System.out.println("    smaller");
          factor -= step;
          if (last == 1) {
            //System.out.println("      half-step");
            step /= 2.0;
          }
          last = -1;
        } else if (distanceMeters < radiusMeters) {
          // too small
          //System.out.println("    bigger");
          factor += step;
          if (last == -1) {
            //System.out.println("      half-step");
            step /= 2.0;
          }
          last = 1;
        }
      }
    }

    // close poly
    result[0][gons] = result[0][0];
    result[1][gons] = result[1][0];

    //System.out.println("  polyLats=" + Arrays.toString(result[0]));
    //System.out.println("  polyLons=" + Arrays.toString(result[1]));

    return new Polygon(result[0], result[1]);
  }

  /** returns next pseudorandom polygon */
  public static Polygon nextPolygon() {
    if (random().nextBoolean()) {
      return surpriseMePolygon(null, null);
    } else if (random().nextInt(10) == 1) {
      // this poly is slow to create ... only do it 10% of the time:
      while (true) {
        int gons = TestUtil.nextInt(random(), 4, 500);
        // So the poly can cover at most 50% of the earth's surface:
        double radiusMeters = random().nextDouble() * GeoUtils.EARTH_MEAN_RADIUS_METERS * Math.PI / 2.0 + 1.0;
        try {
          return createRegularPolygon(nextLatitude(), nextLongitude(), radiusMeters, gons);
        } catch (IllegalArgumentException iae) {
          // we tried to cross dateline or pole ... try again
        }
      }
    }

    Rectangle box = nextBoxInternal(nextLatitude(), nextLatitude(), nextLongitude(), nextLongitude(), false);
    if (random().nextBoolean()) {
      // box
      return boxPolygon(box);
    } else {
      // triangle
      return trianglePolygon(box);
    }
  }

  /** returns next pseudorandom polygon, kinda close to {@code otherLatitude} and {@code otherLongitude} */
  public static Polygon nextPolygonNear(double otherLatitude, double otherLongitude) {
    if (random().nextBoolean()) {
      return surpriseMePolygon(otherLatitude, otherLongitude);
    }

    Rectangle box = nextBoxInternal(nextLatitudeNear(otherLatitude), nextLatitudeNear(otherLatitude),
                                  nextLongitudeNear(otherLongitude), nextLongitudeNear(otherLongitude), false);
    if (random().nextBoolean()) {
      // box
      return boxPolygon(box);
    } else {
      // triangle
      return trianglePolygon(box);
    }
  }

  private static Rectangle nextBoxInternal(double lat0, double lat1, double lon0, double lon1, boolean canCrossDateLine) {
    if (lat1 < lat0) {
      double x = lat0;
      lat0 = lat1;
      lat1 = x;
    }

    if (canCrossDateLine == false && lon1 < lon0) {
      double x = lon0;
      lon0 = lon1;
      lon1 = x;
    }

    return new Rectangle(lat0, lat1, lon0, lon1);
  }

  private static Polygon boxPolygon(Rectangle box) {
    assert box.crossesDateline() == false;
    final double[] polyLats = new double[5];
    final double[] polyLons = new double[5];
    polyLats[0] = box.minLat;
    polyLons[0] = box.minLon;
    polyLats[1] = box.maxLat;
    polyLons[1] = box.minLon;
    polyLats[2] = box.maxLat;
    polyLons[2] = box.maxLon;
    polyLats[3] = box.minLat;
    polyLons[3] = box.maxLon;
    polyLats[4] = box.minLat;
    polyLons[4] = box.minLon;
    return new Polygon(polyLats, polyLons);
  }

  private static Polygon trianglePolygon(Rectangle box) {
    assert box.crossesDateline() == false;
    final double[] polyLats = new double[4];
    final double[] polyLons = new double[4];
    polyLats[0] = box.minLat;
    polyLons[0] = box.minLon;
    polyLats[1] = box.maxLat;
    polyLons[1] = box.minLon;
    polyLats[2] = box.maxLat;
    polyLons[2] = box.maxLon;
    polyLats[3] = box.minLat;
    polyLons[3] = box.minLon;
    return new Polygon(polyLats, polyLons);
  }

  private static Polygon surpriseMePolygon(Double otherLatitude, Double otherLongitude) {
    // repeat until we get a poly that doesn't cross dateline:
    newPoly:
    while (true) {
      //System.out.println("\nPOLY ITER");
      final double centerLat;
      final double centerLon;
      if (otherLatitude == null) {
        centerLat = nextLatitude();
        centerLon = nextLongitude();
      } else {
        GeoUtils.checkLatitude(otherLatitude);
        GeoUtils.checkLongitude(otherLongitude);
        centerLat = nextLatitudeNear(otherLatitude);
        centerLon = nextLongitudeNear(otherLongitude);
      }

      double radius = 0.1 + 20 * random().nextDouble();
      double radiusDelta = random().nextDouble();

      ArrayList<Double> lats = new ArrayList<>();
      ArrayList<Double> lons = new ArrayList<>();
      double angle = 0.0;
      while (true) {
        angle += random().nextDouble()*40.0;
        //System.out.println("  angle " + angle);
        if (angle > 360) {
          break;
        }
        double len = radius * (1.0 - radiusDelta + radiusDelta * random().nextDouble());
        //System.out.println("    len=" + len);
        double lat = centerLat + len * Math.cos(Math.toRadians(angle));
        double lon = centerLon + len * Math.sin(Math.toRadians(angle));
        if (lon <= GeoUtils.MIN_LON_INCL || lon >= GeoUtils.MAX_LON_INCL) {
          // cannot cross dateline: try again!
          continue newPoly;
        }
        if (lat > 90) {
          // cross the north pole
          lat = 180 - lat;
          lon = 180 - lon;
        } else if (lat < -90) {
          // cross the south pole
          lat = -180 - lat;
          lon = 180 - lon;
        }
        if (lon <= GeoUtils.MIN_LON_INCL || lon >= GeoUtils.MAX_LON_INCL) {
          // cannot cross dateline: try again!
          continue newPoly;
        }
        lats.add(lat);
        lons.add(lon);

        //System.out.println("    lat=" + lats.get(lats.size()-1) + " lon=" + lons.get(lons.size()-1));
      }

      // close it
      lats.add(lats.get(0));
      lons.add(lons.get(0));

      double[] latsArray = new double[lats.size()];
      double[] lonsArray = new double[lons.size()];
      for(int i=0;i<lats.size();i++) {
        latsArray[i] = lats.get(i);
        lonsArray[i] = lons.get(i);
      }
      return new Polygon(latsArray, lonsArray);
    }
  }

  /** Keep it simple, we don't need to take arbitrary Random for geo tests */
  private static Random random() {
   return RandomizedContext.current().getRandom();
  }

  /** Returns svg of polygon for debugging. */
  public static String toSVG(Object ...objects) {
    List<Object> flattened = new ArrayList<>();
    for (Object o : objects) {
      if (o instanceof Polygon[]) {
        flattened.addAll(Arrays.asList((Polygon[]) o));
      } else {
        flattened.add(o);
      }
    }
    // first compute bounding area of all the objects
    double minLat = Double.POSITIVE_INFINITY;
    double maxLat = Double.NEGATIVE_INFINITY;
    double minLon = Double.POSITIVE_INFINITY;
    double maxLon = Double.NEGATIVE_INFINITY;
    for (Object o : flattened) {
      final Rectangle r;
      if (o instanceof Polygon) {
        r = Rectangle.fromPolygon(new Polygon[] { (Polygon) o });
      } else if (o instanceof Rectangle) {
        r = (Rectangle) o;
      } else if (o instanceof double[]) {
        double point[] = (double[]) o;
        r = new Rectangle(point[0], point[0], point[1], point[1]);
      } else {
        throw new UnsupportedOperationException("Unsupported element: " + o.getClass());
      }
      minLat = Math.min(minLat, r.minLat);
      maxLat = Math.max(maxLat, r.maxLat);
      minLon = Math.min(minLon, r.minLon);
      maxLon = Math.max(maxLon, r.maxLon);
    }
    
    // add some additional padding so we can really see what happens on the edges too
    double xpadding = (maxLon - minLon) / 64;
    double ypadding = (maxLat - minLat) / 64;
    // expand points to be this large
    double pointX = xpadding * 0.1;
    double pointY = ypadding * 0.1;
    StringBuilder sb = new StringBuilder();
    sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" height=\"640\" width=\"480\" viewBox=\"");
    sb.append(minLon - xpadding)
      .append(" ")
      .append(90 - maxLat - ypadding)
      .append(" ")
      .append(maxLon - minLon + (2*xpadding))
      .append(" ")
      .append(maxLat - minLat + (2*ypadding));
    sb.append("\">\n");

    // encode each object
    for (Object o : flattened) {
      // tostring
      if (o instanceof double[]) {
        double point[] = (double[]) o;
        sb.append("<!-- point: \n");
        sb.append(point[0] + "," + point[1]);
      } else {
        sb.append("<!-- " + o.getClass().getSimpleName() + ": \n");
        sb.append(o.toString());
      }
      sb.append("\n-->\n");
      final Polygon gon;
      final String color;
      final String strokeColor;
      if (o instanceof Rectangle) {
        gon = boxPolygon((Rectangle) o);
        color = "lightskyblue";
        strokeColor = "black";
      } else if (o instanceof double[]) {
        double point[] = (double[]) o;
        gon = boxPolygon(new Rectangle(Math.max(-90, point[0]-pointY), 
                                      Math.min(90, point[0]+pointY), 
                                      Math.max(-180, point[1]-pointX), 
                                      Math.min(180, point[1]+pointX)));
        color = strokeColor = "red";
      } else {
        gon = (Polygon) o;
        color = "lawngreen";
        strokeColor = "black";
      }
      // polygon
      double polyLats[] = gon.getPolyLats();
      double polyLons[] = gon.getPolyLons();
      sb.append("<polygon fill-opacity=\"0.5\" points=\"");
      for (int i = 0; i < polyLats.length; i++) {
        if (i > 0) {
          sb.append(" ");
        }
        sb.append(polyLons[i])
        .append(",")
        .append(90 - polyLats[i]);
      }
      sb.append("\" style=\"fill:" + color + ";stroke:" + strokeColor + ";stroke-width:0.3%\"/>\n");
      for (Polygon hole : gon.getHoles()) {
        double holeLats[] = hole.getPolyLats();
        double holeLons[] = hole.getPolyLons();
        sb.append("<polygon points=\"");
        for (int i = 0; i < holeLats.length; i++) {
          if (i > 0) {
            sb.append(" ");
          }
          sb.append(holeLons[i])
          .append(",")
          .append(90 - holeLats[i]);
        }
        sb.append("\" style=\"fill:lightgray\"/>\n");
      }
    }
    sb.append("</svg>\n");
    return sb.toString();
  }
}
