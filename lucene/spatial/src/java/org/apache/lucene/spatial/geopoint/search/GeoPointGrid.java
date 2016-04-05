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
package org.apache.lucene.spatial.geopoint.search;

import org.apache.lucene.geo.Polygon;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.spatial.util.GeoEncodingUtils;
import org.apache.lucene.util.BitUtil;
import org.apache.lucene.util.FixedBitSet;

/**
 * This is a temporary hack, until some polygon methods have better performance!
 * <p>
 * When this file is removed then we have made good progress! In general we don't call
 * the point-in-polygon algorithm that much, because of how BKD divides up the data. But
 * today the method is very slow (general to all polygons, linear with the number of vertices).
 * At the same time polygon-rectangle relation operations are also slow in the same way, this
 * just really ensures they are the bottleneck by removing most of the point-in-polygon calls.
 * <p>
 * See the "grid" algorithm description here: http://erich.realtimerendering.com/ptinpoly/
 * A few differences:
 * <ul>
 *   <li> We work in an integer encoding, so edge cases are simpler.
 *   <li> We classify each grid cell as "contained", "not contained", or "don't know".
 *   <li> We form a grid over a potentially complex multipolygon with holes.
 *   <li> Construction is less efficient because we do not do anything "smart" such
 *        as following polygon edges. 
 *   <li> Instead we construct a baby tree to reduce the number of relation operations,
 *        which are currently expensive.
 * </ul>
 */
// TODO: can we improve the 1D tree to better work with z-encoded ranges. it can be more efficient.
final class GeoPointGrid {
  // must be a power of two!
  private static final int GRID_SIZE = 1<<5;
  // bounding box of polygons
  private final long minLat;
  private final long maxLat;
  private final long minLon;
  private final long maxLon;
  // TODO: something more efficient than parallel bitsets? maybe one bitset?
  private final FixedBitSet haveAnswer = new FixedBitSet(GRID_SIZE * GRID_SIZE);
  private final FixedBitSet answer = new FixedBitSet(GRID_SIZE * GRID_SIZE);
  
  private final long latPerCell; // latitude range per grid cell
  private final long lonPerCell; // longitude range per grid cell
  
  private final Polygon[] polygons;
  
  // 1D "tree" for relations
  private final FixedBitSet crosses = new FixedBitSet(GRID_SIZE * GRID_SIZE);
  private final FixedBitSet outside = new FixedBitSet(GRID_SIZE * GRID_SIZE);
  private final FixedBitSet inside = new FixedBitSet(GRID_SIZE * GRID_SIZE);
  
  GeoPointGrid(long minLat, long maxLat, long minLon, long maxLon, Polygon... polygons) {
    this.minLat = minLat;
    this.maxLat = maxLat;
    this.minLon = minLon;
    this.maxLon = maxLon;
    this.polygons = polygons;
    if (minLon > maxLon) {
      // maybe make 2 grids if you want this? 
      throw new IllegalArgumentException("Grid cannot cross the dateline");
    }
    if (minLat > maxLat) {
      throw new IllegalArgumentException("bogus bounding box");
    }
    long latitudeRange = maxLat - minLat;
    long longitudeRange = maxLon - minLon;
    // we spill over the edge of the bounding box in each direction a bit,
    // but it prevents edge case bugs.
    latPerCell = latitudeRange / (GRID_SIZE - 1);
    lonPerCell = longitudeRange / (GRID_SIZE - 1);
    // long ms = System.currentTimeMillis();
    fill(0, GRID_SIZE, 0, GRID_SIZE);
    // System.out.println("construction time: " + (System.currentTimeMillis() - ms) + 
    //                    " ms, fill pct: " + 100 * haveAnswer.cardinality() / (float) haveAnswer.length());
  }
  
  /** fills a 2D range of grid cells [minLatIndex .. maxLatIndex) X [minLonIndex .. maxLonIndex) */
  private void fill(int minLatIndex, int maxLatIndex, int minLonIndex, int maxLonIndex) {
    // Math.min because grid cells at the edge of the bounding box are smaller than normal due to spilling.
    long cellMinLat = minLat + (minLatIndex * latPerCell);
    long cellMaxLat = Math.min(maxLat, minLat + (maxLatIndex * latPerCell) - 1);
    long cellMinLon = minLon + (minLonIndex * lonPerCell);
    long cellMaxLon = Math.min(maxLon, minLon + (maxLonIndex * lonPerCell) - 1);

    assert cellMinLat <= maxLat;
    assert cellMinLon <= maxLon;
    assert cellMaxLat >= cellMinLat;
    assert cellMaxLon >= cellMinLon;

    Relation relation = Polygon.relate(polygons, GeoEncodingUtils.unscaleLat(cellMinLat), 
                                                 GeoEncodingUtils.unscaleLat(cellMaxLat), 
                                                 GeoEncodingUtils.unscaleLon(cellMinLon), 
                                                 GeoEncodingUtils.unscaleLon(cellMaxLon));
    if (relation != Relation.CELL_CROSSES_QUERY) {
      // we know the answer for this region, fill the cell range
      for (int i = minLatIndex; i < maxLatIndex; i++) {
        for (int j = minLonIndex; j < maxLonIndex; j++) {
          int index = i * GRID_SIZE + j;
          int zIndex = (int) BitUtil.interleave(j, i);
          assert haveAnswer.get(index) == false;
          assert inside.get(zIndex) == false;
          assert outside.get(zIndex) == false;
          assert crosses.get(zIndex) == false;
          haveAnswer.set(index);
          if (relation == Relation.CELL_INSIDE_QUERY) {
            answer.set(index);
            inside.set(zIndex);
          } else {
            outside.set(zIndex);
          }
        }
      }
    } else if (minLatIndex == maxLatIndex - 1) {
      assert minLonIndex == maxLonIndex - 1;
      int zIndex = (int) BitUtil.interleave(minLonIndex, minLatIndex);
      assert crosses.get(zIndex) == false;
      crosses.set(zIndex);
      // nothing more to do: this is a single grid cell (leaf node) and
      // is an edge case for the polygon.
    } else {
      // grid range crosses our polygon, keep recursing.
      int midLatIndex = (minLatIndex + maxLatIndex) >>> 1;
      int midLonIndex = (minLonIndex + maxLonIndex) >>> 1;
      fill(minLatIndex, midLatIndex, minLonIndex, midLonIndex);
      fill(minLatIndex, midLatIndex, midLonIndex, maxLonIndex);
      fill(midLatIndex, maxLatIndex, minLonIndex, midLonIndex);
      fill(midLatIndex, maxLatIndex, midLonIndex, maxLonIndex);
    }
  }
  
  /** Returns true if inside one of our polygons, false otherwise */
  boolean contains(long latitude, long longitude) {
    // first see if the grid knows the answer
    int index = index(latitude, longitude);
    if (index == -1) {
      return false; // outside of bounding box range
    } else if (haveAnswer.get(index)) {
      return answer.get(index);
    }

    // the grid is unsure (boundary): do a real test.
    double docLatitude = GeoEncodingUtils.unscaleLat(latitude);
    double docLongitude = GeoEncodingUtils.unscaleLon(longitude);
    return Polygon.contains(polygons, docLatitude, docLongitude);
  }
  
  /** Returns grid index of lat/lon, or -1 if the value is outside of the bounding box. */
  private int index(long latitude, long longitude) {
    if (latitude < minLat || latitude > maxLat || longitude < minLon || longitude > maxLon) {
      return -1; // outside of bounding box range
    }
    
    long latRel = latitude - minLat;
    long lonRel = longitude - minLon;
    
    int latIndex = (int) (latRel / latPerCell);
    int lonIndex = (int) (lonRel / lonPerCell);
    return latIndex * GRID_SIZE + lonIndex;
  }
  
  /** Returns z-index of lat/lon. do not call for ranges outside of the bounding box. */
  private int zIndex(long latitude, long longitude) {
    assert latitude >= minLat && latitude <= maxLat && longitude >= minLon && longitude <= maxLon;
    
    long latRel = latitude - minLat;
    long lonRel = longitude - minLon;
    
    int latIndex = (int) (latRel / latPerCell);
    int lonIndex = (int) (lonRel / lonPerCell);
    return (int) BitUtil.interleave(lonIndex, latIndex);
  }
  
  /** scans bits from [minIndex .. maxIndex] and returns true if one is found */
  private boolean scan(FixedBitSet bitset, int minIndex, int maxIndex) {
    int index = bitset.nextSetBit(minIndex);
    return index >= 0 && index <= maxIndex;
  }

  /** Returns relation of bounding box to these polygons */
  Relation relate(long minLat, long maxLat, long minLon, long maxLon) {
    // if the bounding boxes are disjoint then the shape does not cross
    if (maxLon < this.minLon || minLon > this.maxLon || maxLat < this.minLat || minLat > this.maxLat) {
      return Relation.CELL_OUTSIDE_QUERY;
    }
    // if the rectangle fully encloses us, we cross.
    if (minLat <= this.minLat && maxLat >= this.maxLat && minLon <= this.minLon && maxLon >= this.maxLon) {
      return Relation.CELL_CROSSES_QUERY;
    }
    // otherwise, heavier stuff
    
    if (minLat >= this.minLat && maxLat <= this.maxLat && minLon >= this.minLon && maxLon <= this.maxLon) {
      // its fully within our box. it stands a chance of being contains or outside
      int lower = zIndex(minLat, minLon);
      int upper = zIndex(maxLat, maxLon);
      assert upper >= lower;
      if (scan(crosses, lower, upper)) {
        return Polygon.relate(polygons, GeoEncodingUtils.unscaleLat(minLat), 
                                        GeoEncodingUtils.unscaleLat(maxLat), 
                                        GeoEncodingUtils.unscaleLon(minLon), 
                                        GeoEncodingUtils.unscaleLon(maxLon));
      } else if (scan(outside, lower, upper) == false) {
        return Relation.CELL_INSIDE_QUERY;
      } else {
        return Relation.CELL_OUTSIDE_QUERY;
      }
    } else {
      // no chance of being contained, at least part of rectangle is outside.
      // just try to determine if we are fully outside
      int lower = zIndex(Math.max(minLat, this.minLat), Math.max(minLon, this.minLon));
      int upper = zIndex(Math.min(maxLat, this.maxLat), Math.min(maxLon, this.maxLon));
      assert upper >= lower;
      if (scan(inside, lower, upper) == false && scan(crosses, lower, upper) == false) {
        return Relation.CELL_OUTSIDE_QUERY;
      } else {
        return Polygon.relate(polygons, GeoEncodingUtils.unscaleLat(minLat), 
                                        GeoEncodingUtils.unscaleLat(maxLat), 
                                        GeoEncodingUtils.unscaleLon(minLon), 
                                        GeoEncodingUtils.unscaleLon(maxLon));
      }
    }
  }
}
