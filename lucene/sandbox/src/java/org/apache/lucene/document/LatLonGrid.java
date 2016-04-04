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
package org.apache.lucene.document;

import org.apache.lucene.geo.Polygon;
import org.apache.lucene.index.PointValues.Relation;
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
// TODO: just make a more proper tree (maybe in-ram BKD)? then we can answer most 
// relational operations as rectangle <-> rectangle relations in integer space in log(n) time..
final class LatLonGrid {
  // must be a power of two!
  private static final int GRID_SIZE = 1<<5;
  // bounding box of polygons
  private final int minLat;
  private final int maxLat;
  private final int minLon;
  private final int maxLon;
  // TODO: something more efficient than parallel bitsets? maybe one bitset?
  private final FixedBitSet haveAnswer = new FixedBitSet(GRID_SIZE * GRID_SIZE);
  private final FixedBitSet answer = new FixedBitSet(GRID_SIZE * GRID_SIZE);
  
  private final long latPerCell; // latitude range per grid cell
  private final long lonPerCell; // longitude range per grid cell
  
  private final Polygon[] polygons;
  
  LatLonGrid(int minLat, int maxLat, int minLon, int maxLon, Polygon... polygons) {
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
    long latitudeRange = maxLat - (long) minLat;
    long longitudeRange = maxLon - (long) minLon;
    // we spill over the edge of the bounding box in each direction a bit,
    // but it prevents edge case bugs.
    latPerCell = latitudeRange / (GRID_SIZE - 1);
    lonPerCell = longitudeRange / (GRID_SIZE - 1);
    fill(0, GRID_SIZE, 0, GRID_SIZE);
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

    Relation relation = Polygon.relate(polygons, LatLonPoint.decodeLatitude((int) cellMinLat), 
                                                 LatLonPoint.decodeLatitude((int) cellMaxLat), 
                                                 LatLonPoint.decodeLongitude((int) cellMinLon), 
                                                 LatLonPoint.decodeLongitude((int) cellMaxLon));
    if (relation != Relation.CELL_CROSSES_QUERY) {
      // we know the answer for this region, fill the cell range
      for (int i = minLatIndex; i < maxLatIndex; i++) {
        for (int j = minLonIndex; j < maxLonIndex; j++) {
          int index = i * GRID_SIZE + j;
          assert haveAnswer.get(index) == false;
          haveAnswer.set(index);
          if (relation == Relation.CELL_INSIDE_QUERY) {
            answer.set(index);
          }
        }
      }
    } else if (minLatIndex == maxLatIndex - 1) {
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
  boolean contains(int latitude, int longitude) {
    // first see if the grid knows the answer
    int index = index(latitude, longitude);
    if (index == -1) {
      return false; // outside of bounding box range
    } else if (haveAnswer.get(index)) {
      return answer.get(index);
    }

    // the grid is unsure (boundary): do a real test.
    double docLatitude = LatLonPoint.decodeLatitude(latitude);
    double docLongitude = LatLonPoint.decodeLongitude(longitude);
    return Polygon.contains(polygons, docLatitude, docLongitude);
  }
  
  /** Returns relation to the provided rectangle */
  Relation relate(int minLat, int maxLat, int minLon, int maxLon) {
    return Polygon.relate(polygons, LatLonPoint.decodeLatitude(minLat), 
                                    LatLonPoint.decodeLatitude(maxLat), 
                                    LatLonPoint.decodeLongitude(minLon), 
                                    LatLonPoint.decodeLongitude(maxLon));
    /*
    // if the bounding boxes are disjoint then the shape does not cross
    if (maxLon < this.minLon || minLon > this.maxLon || maxLat < this.minLat || minLat > this.maxLat) {
      return Relation.CELL_OUTSIDE_QUERY;
    }
    // if the rectangle fully encloses us, we cross.
    if (minLat <= this.minLat && maxLat >= this.maxLat && minLon <= this.minLon && maxLon >= this.maxLon) {
      return Relation.CELL_CROSSES_QUERY;
    }
    // check any holes
    for (Polygon hole : holes) {
      Relation holeRelation = hole.relate(minLat, maxLat, minLon, maxLon);
      if (holeRelation == Relation.CELL_CROSSES_QUERY) {
        return Relation.CELL_CROSSES_QUERY;
      } else if (holeRelation == Relation.CELL_INSIDE_QUERY) {
        return Relation.CELL_OUTSIDE_QUERY;
      }
    }
    // check each corner: if < 4 are present, its cheaper than crossesSlowly
    int numCorners = numberOfCorners(minLat, maxLat, minLon, maxLon);
    if (numCorners == 4) {
      if (crossesSlowly(minLat, maxLat, minLon, maxLon)) {
        return Relation.CELL_CROSSES_QUERY;
      }
      return Relation.CELL_INSIDE_QUERY;
    } else if (numCorners > 0) {
      return Relation.CELL_CROSSES_QUERY;
    }
    
    // we cross
    if (crossesSlowly(minLat, maxLat, minLon, maxLon)) {
      return Relation.CELL_CROSSES_QUERY;
    }
    
    return Relation.CELL_OUTSIDE_QUERY;
    */
  }
  
  /** Returns grid index of lat/lon, or -1 if the value is outside of the bounding box. */
  private int index(int latitude, int longitude) {
    if (latitude < minLat || latitude > maxLat || longitude < minLon || longitude > maxLon) {
      return -1; // outside of bounding box range
    }
    
    long latRel = latitude - (long) minLat;
    long lonRel = longitude - (long) minLon;
    
    int latIndex = (int) (latRel / latPerCell);
    int lonIndex = (int) (lonRel / lonPerCell);
    return latIndex * GRID_SIZE + lonIndex;
  }
}
