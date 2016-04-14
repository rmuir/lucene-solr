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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import org.apache.lucene.geo.Rectangle;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.SloppyMath;
import org.apache.lucene.util.StringHelper;
import org.apache.lucene.util.bkd.BKDReader;

import static org.apache.lucene.geo.GeoEncodingUtils.decodeLatitude;
import static org.apache.lucene.geo.GeoEncodingUtils.decodeLongitude;
import static org.apache.lucene.geo.GeoEncodingUtils.encodeLatitude;
import static org.apache.lucene.geo.GeoEncodingUtils.encodeLongitude;

/**
 * KNN search on top of 2D lat/lon indexed points.
 *
 * @lucene.experimental
 */
class NearestNeighbor {

  static class Cell implements Comparable<Cell> {
    final int readerIndex;
    final int nodeID;
    final byte[] minPacked;
    final byte[] maxPacked;

    /** The closest possible distance of all points in this cell */
    final double distanceMeters;

    public Cell(int readerIndex, int nodeID, byte[] minPacked, byte[] maxPacked, double distanceMeters) {
      this.readerIndex = readerIndex;
      this.nodeID = nodeID;
      this.minPacked = minPacked.clone();
      this.maxPacked = maxPacked.clone();
      this.distanceMeters = distanceMeters;
    }

    public int compareTo(Cell other) {
      return Double.compare(distanceMeters, other.distanceMeters);
    }

    @Override
    public String toString() {
      double minLat = decodeLatitude(minPacked, 0);
      double minLon = decodeLongitude(minPacked, Integer.BYTES);
      double maxLat = decodeLatitude(maxPacked, 0);
      double maxLon = decodeLongitude(maxPacked, Integer.BYTES);
      return "Cell(readerIndex=" + readerIndex + " lat=" + minLat + " TO " + maxLat + ", lon=" + minLon + " TO " + maxLon + "; distanceSortKey=" + distanceMeters + ")";
    }
  }

  private static class NearestVisitor implements IntersectVisitor {

    public int curDocBase;
    public Bits curLiveDocs;
    final int topN;
    final PriorityQueue<NearestHit> hitQueue;
    final double pointLat;
    final double pointLon;
    private int setBottomCounter;

    // these are pre-encoded with LatLonPoint's encoding
    final byte minLat[] = new byte[Integer.BYTES];
    final byte maxLat[] = new byte[Integer.BYTES];
    final byte minLon[] = new byte[Integer.BYTES];
    final byte maxLon[] = new byte[Integer.BYTES];
    // second set of longitude ranges to check (for cross-dateline case)
    final byte minLon2[] = new byte[Integer.BYTES];

    public NearestVisitor(PriorityQueue<NearestHit> hitQueue, int topN, double pointLat, double pointLon) {
      this.hitQueue = hitQueue;
      this.topN = topN;
      this.pointLat = pointLat;
      this.pointLon = pointLon;
      // initialize bounds to infinite
      Arrays.fill(maxLat, (byte) 0xFF); 
      Arrays.fill(maxLon, (byte) 0xFF); 
      Arrays.fill(minLat, (byte) 0x0); 
      Arrays.fill(minLon, (byte) 0x0);
      Arrays.fill(minLon2, (byte) 0x0);
    }

    @Override
    public void visit(int docID) {
      throw new AssertionError();
    }

    private void maybeUpdateBBox() {
      if (setBottomCounter < 1024 || (setBottomCounter & 0x3F) == 0x3F) {
        NearestHit hit = hitQueue.peek();
        Rectangle box = Rectangle.fromPointDistance(pointLat, pointLon, SloppyMath.haversinMeters(hit.distanceSortKey));
        //System.out.println("    update bbox to " + box);
        NumericUtils.intToSortableBytes(encodeLatitude(box.minLat), minLat, 0);
        NumericUtils.intToSortableBytes(encodeLatitude(box.maxLat), maxLat, 0);

        // crosses dateline: split
        if (box.crossesDateline()) {
          // box1
          NumericUtils.intToSortableBytes(Integer.MIN_VALUE, minLon, 0);
          NumericUtils.intToSortableBytes(encodeLongitude(box.maxLon), maxLon, 0);
          // box2
          NumericUtils.intToSortableBytes(encodeLongitude(box.minLon), minLon2, 0);
        } else {
          NumericUtils.intToSortableBytes(encodeLongitude(box.minLon), minLon, 0);
          NumericUtils.intToSortableBytes(encodeLongitude(box.maxLon), maxLon, 0);
          // disable box2
          NumericUtils.intToSortableBytes(Integer.MAX_VALUE, minLon2, 0);
        }
      }
      setBottomCounter++;
    }

    @Override
    public void visit(int docID, byte[] packedValue) {
      //System.out.println("visit docID=" + docID + " liveDocs=" + curLiveDocs);

      if (curLiveDocs != null && curLiveDocs.get(docID) == false) {
        return;
      }

      // bounding box check
      if (StringHelper.compare(Integer.BYTES, packedValue, 0, maxLat, 0) > 0 ||
          StringHelper.compare(Integer.BYTES, packedValue, 0, minLat, 0) < 0) {
        // latitude out of bounding box range
        return;
      }

      if ((StringHelper.compare(Integer.BYTES, packedValue, Integer.BYTES, maxLon, 0) > 0 ||
           StringHelper.compare(Integer.BYTES, packedValue, Integer.BYTES, minLon, 0) < 0)
          && StringHelper.compare(Integer.BYTES, packedValue, Integer.BYTES, minLon2, 0) < 0) {
        // longitude out of bounding box range
        return;
      }

      double docLatitude = decodeLatitude(packedValue, 0);
      double docLongitude = decodeLongitude(packedValue, Integer.BYTES);

      double distanceSortKey = SloppyMath.haversinSortKey(pointLat, pointLon, docLatitude, docLongitude);

      //System.out.println("    visit docID=" + docID + " distanceSortKey=" + distanceSortKey + " docLat=" + docLatitude + " docLon=" + docLongitude);

      int fullDocID = curDocBase + docID;

      if (hitQueue.size() == topN) {
        // queue already full
        NearestHit hit = hitQueue.peek();
        //System.out.println("      bottom distanceSortKey=" + hit.distanceMeters);
        // we don't collect docs in order here, so we must also test the tie-break case ourselves:
        if (distanceSortKey < hit.distanceSortKey || (distanceSortKey == hit.distanceSortKey && fullDocID < hit.docID)) {
          hitQueue.poll();
          hit.docID = fullDocID;
          hit.distanceSortKey = distanceSortKey;
          hitQueue.offer(hit);
          //System.out.println("      ** keep2, now bottom=" + hit);
          maybeUpdateBBox();
        }
        
      } else {
        NearestHit hit = new NearestHit();
        hit.docID = fullDocID;
        hit.distanceSortKey = distanceSortKey;
        hitQueue.offer(hit);
        //System.out.println("      ** keep1, now bottom=" + hit);
      }
    }

    @Override
    public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
      throw new AssertionError();
    }
  }

  /** Holds one hit from {@link LatLonPoint#nearest} */
  static class NearestHit {
    public int docID;
    public double distanceSortKey;

    @Override
    public String toString() {
      return "NearestHit(docID=" + docID + " distanceSortKey=" + distanceSortKey + ")";
    }
  }

  // TODO: can we somehow share more with, or simply directly use, the LatLonPointDistanceComparator?  It's really doing the same thing as
  // our hitQueue...

  public static NearestHit[] nearest(double pointLat, double pointLon, List<BKDReader> readers, List<Bits> liveDocs, List<Integer> docBases, final int n) throws IOException {

    //System.out.println("NEAREST: readers=" + readers + " liveDocs=" + liveDocs + " pointLat=" + pointLat + " pointLon=" + pointLon);
    // Holds closest collected points seen so far:
    // TODO: if we used lucene's PQ we could just updateTop instead of poll/offer:
    final PriorityQueue<NearestHit> hitQueue = new PriorityQueue<>(n, new Comparator<NearestHit>() {
        @Override
        public int compare(NearestHit a, NearestHit b) {
          // sort by opposite distanceSortKey natural order
          int cmp = Double.compare(a.distanceSortKey, b.distanceSortKey);
          if (cmp != 0) {
            return -cmp;
          }

          // tie-break by higher docID:
          return b.docID - a.docID;
        }
      });

    // Holds all cells, sorted by closest to the point:
    PriorityQueue<Cell> cellQueue = new PriorityQueue<>();

    NearestVisitor visitor = new NearestVisitor(hitQueue, n, pointLat, pointLon);
    List<BKDReader.IntersectState> states = new ArrayList<>();

    // Add root cell for each reader into the queue:
    for(int i=0;i<readers.size();i++) {
      BKDReader reader = readers.get(i);
      byte[] minPackedValue = reader.getMinPackedValue();
      byte[] maxPackedValue = reader.getMaxPackedValue();
      states.add(reader.getIntersectState(visitor));

      cellQueue.offer(new Cell(i, 1, reader.getMinPackedValue(), reader.getMaxPackedValue(),
                               approxBestDistance(minPackedValue, maxPackedValue, pointLat, pointLon)));
    }

    while (cellQueue.size() > 0) {
      Cell cell = cellQueue.poll();
      //System.out.println("  visit " + cell);

      // TODO: if we replace approxBestDistance with actualBestDistance, we can put an opto here to break once this "best" cell is fully outside of the hitQueue bottom's radius:
      BKDReader reader = readers.get(cell.readerIndex);

      if (reader.isLeafNode(cell.nodeID)) {
        //System.out.println("    leaf");
        // Leaf block: visit all points and possibly collect them:
        visitor.curDocBase = docBases.get(cell.readerIndex);
        visitor.curLiveDocs = liveDocs.get(cell.readerIndex);
        reader.visitLeafBlockValues(cell.nodeID, states.get(cell.readerIndex));
        //System.out.println("    now " + hitQueue.size() + " hits");
      } else {
        //System.out.println("    non-leaf");
        // Non-leaf block: split into two cells and put them back into the queue:

        if (StringHelper.compare(Integer.BYTES, cell.minPacked, 0, visitor.maxLat, 0) > 0 ||
            StringHelper.compare(Integer.BYTES, cell.maxPacked, 0, visitor.minLat, 0) < 0) {
          // latitude out of bounding box range
          continue;
        }

        if ((StringHelper.compare(Integer.BYTES, cell.minPacked, Integer.BYTES, visitor.maxLon, 0) > 0 ||
             StringHelper.compare(Integer.BYTES, cell.maxPacked, Integer.BYTES, visitor.minLon, 0) < 0)
            && StringHelper.compare(Integer.BYTES, cell.maxPacked, Integer.BYTES, visitor.minLon2, 0) < 0) {
          // longitude out of bounding box range
          continue;
        }
        
        byte[] splitPackedValue = cell.maxPacked.clone();
        reader.copySplitValue(cell.nodeID, splitPackedValue);
        cellQueue.offer(new Cell(cell.readerIndex, 2*cell.nodeID, cell.minPacked, splitPackedValue,
                                 approxBestDistance(cell.minPacked, splitPackedValue, pointLat, pointLon)));

        splitPackedValue = cell.minPacked.clone();
        reader.copySplitValue(cell.nodeID, splitPackedValue);
        cellQueue.offer(new Cell(cell.readerIndex, 2*cell.nodeID+1, splitPackedValue, cell.maxPacked,
                                 approxBestDistance(splitPackedValue, cell.maxPacked, pointLat, pointLon)));
      }
    }

    NearestHit[] hits = new NearestHit[hitQueue.size()];
    int downTo = hitQueue.size()-1;
    while (hitQueue.size() != 0) {
      hits[downTo] = hitQueue.poll();
      downTo--;
    }

    return hits;
  }

  // NOTE: incoming args never cross the dateline, since they are a BKD cell
  private static double approxBestDistance(byte[] minPackedValue, byte[] maxPackedValue, double pointLat, double pointLon) {
    double minLat = decodeLatitude(minPackedValue, 0);
    double minLon = decodeLongitude(minPackedValue, Integer.BYTES);
    double maxLat = decodeLatitude(maxPackedValue, 0);
    double maxLon = decodeLongitude(maxPackedValue, Integer.BYTES);
    return approxBestDistance(minLat, maxLat, minLon, maxLon, pointLat, pointLon);
  }

  // NOTE: incoming args never cross the dateline, since they are a BKD cell
  private static double approxBestDistance(double minLat, double maxLat, double minLon, double maxLon, double pointLat, double pointLon) {
    
    // TODO: can we make this the trueBestDistance?  I.e., minimum distance between the point and ANY point on the box?  we can speed things
    // up if so, but not enrolling any BKD cell whose true best distance is > bottom of the current hit queue

    if (pointLat >= minLat && pointLat <= maxLat && pointLon >= minLon && pointLon <= maxLon) {
      // point is inside the cell!
      return 0.0;
    }

    double d1 = SloppyMath.haversinSortKey(pointLat, pointLon, minLat, minLon);
    double d2 = SloppyMath.haversinSortKey(pointLat, pointLon, minLat, maxLon);
    double d3 = SloppyMath.haversinSortKey(pointLat, pointLon, maxLat, maxLon);
    double d4 = SloppyMath.haversinSortKey(pointLat, pointLon, maxLat, minLon);
    return Math.min(Math.min(d1, d2), Math.min(d3, d4));
  }
}
